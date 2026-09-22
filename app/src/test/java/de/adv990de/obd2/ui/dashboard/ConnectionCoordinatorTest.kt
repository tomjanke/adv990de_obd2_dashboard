package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import android.content.Context
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.UiText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests fuer den herausgeloesten Verbindungs-Koordinator.
 *
 * Der Grund fuer die Trennung steht hier in Reinform: Diese Klasse braucht weder
 * SessionRepository noch CrashReporter noch GPS — und eine Aenderung am Messwert-Pfad kann diese
 * Tests nicht mehr umwerfen. Bis 1.10.2 lagen beide Zustaendigkeiten in einem ViewModel, und eine
 * geaenderte Zeile im Messwert-Pfad liess 18 Tests fallen, von denen keiner die Verbindung betraf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    // Eigener Scope statt `backgroundScope`: Der Koordinator laeuft im Scope des ViewModels, und
    // dieser hier teilt sich ueber Dispatchers.setMain den Scheduler von runTest — nur dann treibt
    // advanceUntilIdle() die Coroutinen des Koordinators wirklich an.
    private val testDispatcher = StandardTestDispatcher()
    private val coordinatorScope = CoroutineScope(testDispatcher)

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }

    @After fun teardown() {
        coordinatorScope.cancel()
        Dispatchers.resetMain()
    }

    private val device = mockk<ScannedDevice> { every { name } returns "TestAdapter" }

    private fun makeRepo(
        connected: Boolean = false,
        lastConnected: ScannedDevice? = null,
    ): ObdRepository = mockk {
        every { isConnectedFlow } returns MutableStateFlow(connected)
        every { lastConnectedDevice } returns MutableStateFlow(lastConnected)
    }

    // `useGpsSpeed` has no per-test variant here on purpose: shouldIncludeLocationServiceType()
    // (Tests 8-11) is tested directly as a pure function, so none of these coordinator-level tests
    // need to vary it — but ConnectionCoordinator.startObdForegroundService() still reads it on
    // every successful connect, so the stub must exist or the non-relaxed mock throws.
    private fun makePrefs(lastDevice: ScannedDevice? = null): AppPreferences = mockk {
        every { this@mockk.lastDevice } returns flowOf(lastDevice)
        every { this@mockk.useGpsSpeed } returns flowOf(false)
    }

    private fun makeAdapter(enabled: Boolean = true): BluetoothAdapter = mockk {
        every { isEnabled } returns enabled
    }

    private fun makeCoordinator(
        repo: ObdRepository,
        prefs: AppPreferences,
        adapter: BluetoothAdapter = makeAdapter(),
        context: Context = mockk(relaxed = true),
        logRepo: AppLogRepository = mockk(relaxed = true),
    ) = ConnectionCoordinator(repo, prefs, logRepo, adapter, context)

    // --- Test 1 ---

    @Test
    fun `auto-connect uses the saved device and starts the service`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connect(device) } just Runs
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, makePrefs(lastDevice = device), context = context)
            .start(coordinatorScope, events::add)
        advanceUntilIdle()

        coVerify { repo.connect(device) }
        verify { context.startForegroundService(any()) }
        assertEquals(
            listOf(ConnectionEvent.Attempting(attempt = 0, total = RECONNECT_DELAYS.size), ConnectionEvent.Connected),
            events
        )
    }

    // --- Test 2 ---

    @Test
    fun `without a saved device auto-connect stays silent`() = runTest {
        val repo = makeRepo()
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, makePrefs(lastDevice = null)).start(coordinatorScope, events::add)
        advanceUntilIdle()

        assertTrue("Ohne Geraet darf kein Ereignis entstehen: $events", events.isEmpty())
        coVerify(exactly = 0) { repo.connect(any()) }
    }

    // --- Test 3 ---

    @Test
    fun `a failed auto-connect hands over to the reconnect loop`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connect(device) } throws IllegalStateException("adapter busy")
        coEvery { repo.reconnect() } just Runs
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, makePrefs(lastDevice = device)).start(coordinatorScope, events::add)
        advanceUntilIdle()

        // Der erste Versuch der Schleife traegt die Nummer 1 — der Auto-Connect selbst die 0.
        assertTrue(
            "Die Schleife hat nicht uebernommen: $events",
            events.any { it is ConnectionEvent.Attempting && it.attempt == 1 }
        )
    }

    // --- Test 4 ---

    @Test
    fun `without any known device the loop fails immediately instead of waiting`() = runTest {
        val repo = makeRepo()
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        val coordinator = makeCoordinator(repo, makePrefs(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        assertEquals(
            listOf(ConnectionEvent.Failed(UiText.StringResource(R.string.err_no_device))),
            events
        )
        verify { context.stopService(any()) }
        coVerify(exactly = 0) { repo.reconnect() }
    }

    // --- Test 5 ---

    @Test
    fun `a disabled bluetooth adapter aborts the loop with its own message`() = runTest {
        val repo = makeRepo(lastConnected = device)
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        // Kein gespeichertes Geraet: Der Auto-Connect haelt sich heraus, damit allein die
        // ausdrueckliche Aufforderung die Schleife treibt. Das Geraet der letzten Verbindung steht
        // im Repository — genau der Fall "Verbindung waehrend der Fahrt verloren".
        val coordinator = makeCoordinator(
            repo, makePrefs(lastDevice = null), adapter = makeAdapter(enabled = false), context = context
        )
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        assertEquals(
            ConnectionEvent.Failed(UiText.StringResource(R.string.obd_err_bluetooth_disabled)),
            events.last()
        )
        verify { context.stopService(any()) }
        coVerify(exactly = 0) { repo.reconnect() }
    }

    // --- Test 6 ---

    @Test
    fun `the loop gives up after all attempts and stops the service`() = runTest {
        val repo = makeRepo(lastConnected = device)
        coEvery { repo.reconnect() } throws IllegalStateException("still gone")
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        // Wie oben: nur die ausdrueckliche Aufforderung treibt die Schleife, sonst zaehlte der
        // gescheiterte Auto-Connect einen zweiten Durchlauf mit.
        val coordinator = makeCoordinator(repo, makePrefs(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify(exactly = RECONNECT_DELAYS.size) { repo.reconnect() }
        assertEquals(
            ConnectionEvent.Failed(UiText.StringResource(R.string.err_reconnect_fail)),
            events.last()
        )
        verify { context.stopService(any()) }
    }

    // --- Test 7 ---

    @Test
    fun `a connection established meanwhile cancels the loop`() = runTest {
        // Der Nutzer hat in den Einstellungen selbst verbunden, waehrend der Trigger unterwegs war.
        val repo = makeRepo(connected = true, lastConnected = device)
        val events = mutableListOf<ConnectionEvent>()

        val coordinator = makeCoordinator(repo, makePrefs(lastDevice = device))
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.reconnect() }
        assertTrue("Es darf kein Versuch gemeldet werden: $events", events.isEmpty())
    }

    // --- Test 8 ---

    @Test
    fun `shouldIncludeLocationServiceType is true when GPS speed is on and background location is granted`() {
        assertTrue(shouldIncludeLocationServiceType(useGpsSpeed = true, hasBackgroundLocation = true))
    }

    // --- Test 9 ---

    @Test
    fun `shouldIncludeLocationServiceType is false when GPS speed is off`() {
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasBackgroundLocation = true))
    }

    // --- Test 10 ---

    @Test
    fun `shouldIncludeLocationServiceType is false when background location is not granted`() {
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = true, hasBackgroundLocation = false))
    }

    // --- Test 11 ---

    @Test
    fun `shouldIncludeLocationServiceType is false when neither condition holds`() {
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasBackgroundLocation = false))
    }
}
