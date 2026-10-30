package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import android.content.Context
import de.adv990de.obd2.ObdServiceController
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.InitResult
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.ui.UiText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException

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
        userDisconnects: MutableStateFlow<Int> = MutableStateFlow(0),
        adapterReset: InitResult? = null,
    ): ObdRepository = mockk {
        every { isConnectedFlow } returns MutableStateFlow(connected)
        every { lastConnectedDevice } returns MutableStateFlow(lastConnected)
        every { userDisconnectCount } returns userDisconnects
        every { adapterInit } returns adapterReset
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
    ) = ConnectionCoordinator(
        repo, prefs, logRepo, adapter,
        ObdServiceController(repo, prefs, context, logRepo, coordinatorScope),
    )

    // --- Test 1 ---

    @Test
    fun `auto-connect uses the saved device and starts the service`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connectIfDisconnected(device) } just Runs
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, fakeAppPreferences(lastDevice = device), context = context)
            .start(coordinatorScope, events::add)
        advanceUntilIdle()

        coVerify { repo.connectIfDisconnected(device) }
        verify { context.startForegroundService(any()) }
        assertEquals(
            listOf(ConnectionEvent.Attempting(attempt = 0, total = RECONNECT_ATTEMPT_COUNT), ConnectionEvent.Connected),
            events
        )
    }

    @Test
    fun `auto-connect logs the adapter's answer to the reset in the connected line`() = runTest {
        val repo = makeRepo(adapterReset = InitResult("ELM327 v1.0", 940))
        coEvery { repo.connectIfDisconnected(device) } just Runs
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        makeCoordinator(repo, fakeAppPreferences(lastDevice = device), logRepo = logRepo)
            .start(coordinatorScope, {})
        advanceUntilIdle()

        coVerify { logRepo.log(LogLevel.INFO, "Connected to TestAdapter (ATZ: 'ELM327 v1.0', 940 ms)") }
    }

    @Test
    fun `the reconnect loop logs the adapter's answer to the reset in the connected line`() = runTest {
        val repo = makeRepo(lastConnected = device, adapterReset = InitResult("OK", 4))
        coEvery { repo.reconnect() } just Runs
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), logRepo = logRepo)
        coordinator.start(coordinatorScope, {})

        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify { logRepo.log(LogLevel.INFO, "Connected to TestAdapter (ATZ: 'OK', 4 ms)") }
    }

    // --- Test 2 ---

    @Test
    fun `without a saved device auto-connect stays silent`() = runTest {
        val repo = makeRepo()
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, fakeAppPreferences(lastDevice = null)).start(coordinatorScope, events::add)
        advanceUntilIdle()

        assertTrue("Ohne Geraet darf kein Ereignis entstehen: $events", events.isEmpty())
        coVerify(exactly = 0) { repo.connectIfDisconnected(any()) }
    }

    // --- Test 3 ---

    @Test
    fun `a failed auto-connect hands over to the reconnect loop`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connectIfDisconnected(device) } throws IllegalStateException("adapter busy")
        coEvery { repo.reconnect() } just Runs
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, fakeAppPreferences(lastDevice = device)).start(coordinatorScope, events::add)
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

        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), context = context)
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
            repo, fakeAppPreferences(lastDevice = null), adapter = makeAdapter(enabled = false), context = context
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
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify(exactly = RECONNECT_ATTEMPT_COUNT) { repo.reconnect() }
        assertEquals(
            ConnectionEvent.Failed(UiText.StringResource(R.string.err_reconnect_fail)),
            events.last()
        )
        verify { context.stopService(any()) }
    }

    // --- B25: the reason of every failed attempt is logged (the UI shows only the final verdict) ---

    @Test
    fun `every failed reconnect attempt logs its cause`() = runTest {
        val repo = makeRepo(lastConnected = device)
        coEvery { repo.reconnect() } throws IllegalStateException("socket closed")
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), logRepo = logRepo)
        coordinator.start(coordinatorScope) {}
        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify(exactly = RECONNECT_ATTEMPT_COUNT) {
            logRepo.log(LogLevel.WARN, match { it.contains("socket closed") })
        }
    }

    // --- B3: a user disconnect stops a running reconnect loop ---

    @Test
    fun `a user disconnect during the wait aborts the loop at once`() = runTest {
        val userDisconnects = MutableStateFlow(0)
        val repo = makeRepo(lastConnected = device, userDisconnects = userDisconnects)
        coEvery { repo.reconnect() } just Runs
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        runCurrent()                                  // attempt 1 is announced; the loop sits in its 3 s wait
        assertTrue("Die Schleife wartet nicht: $events", events.any { it is ConnectionEvent.Attempting })

        userDisconnects.value = 1                     // "Trennen" in the notification
        runCurrent()

        assertEquals(ConnectionEvent.Aborted, events.last())
        assertTrue("sofort, nicht erst nach der Wartezeit: ${currentTime} ms", currentTime < 3_000)
        coVerify(exactly = 0) { repo.reconnect() }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `a user disconnect between the request and the start of the round aborts it`() = runTest {
        val userDisconnects = MutableStateFlow(0)
        val repo = makeRepo(lastConnected = device, userDisconnects = userDisconnects)
        coEvery { repo.reconnect() } just Runs
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null))
        coordinator.start(coordinatorScope, events::add)

        coordinator.requestReconnect()                // the request carries the count of THIS moment ...
        userDisconnects.value = 1                     // ... the user disconnects before the loop got to run
        advanceUntilIdle()

        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Aborted), events)
        coVerify(exactly = 0) { repo.reconnect() }
    }

    @Test
    fun `a user disconnect wins over the no-device failure - the user asked to be disconnected`() = runTest {
        val userDisconnects = MutableStateFlow(0)
        val repo = makeRepo(lastConnected = null, userDisconnects = userDisconnects)
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)

        coordinator.requestReconnect()
        userDisconnects.value = 1
        advanceUntilIdle()

        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Aborted), events)
    }

    @Test
    fun `a user disconnect while an attempt is running prevents Connected and the service start`() = runTest {
        val userDisconnects = MutableStateFlow(0)
        val repo = makeRepo(lastConnected = device, userDisconnects = userDisconnects)
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.reconnect() } coAnswers { gate.await() }
        val context = mockk<Context>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), context = context)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceTimeBy(3_001); runCurrent()            // the attempt is in flight, suspended inside reconnect()

        userDisconnects.value = 1
        gate.complete(Unit)                           // the connect finishes anyway (the user's disconnect follows)
        advanceUntilIdle()

        assertEquals(ConnectionEvent.Aborted, events.last())
        assertTrue("kein Connected nach dem Trennen: $events", events.none { it == ConnectionEvent.Connected })
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `a user disconnect BEFORE the request does not suppress a later reconnect`() = runTest {
        val repo = makeRepo(lastConnected = device, userDisconnects = MutableStateFlow(1))
        coEvery { repo.reconnect() } just Runs
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null))
        coordinator.start(coordinatorScope, events::add)

        coordinator.requestReconnect()                // a later link loss, after the user once disconnected
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.reconnect() }
        assertEquals(ConnectionEvent.Connected, events.last())
    }

    // --- Test 7 ---

    @Test
    fun `a connection established meanwhile cancels the loop`() = runTest {
        // Der Nutzer hat in den Einstellungen selbst verbunden, waehrend der Trigger unterwegs war.
        val repo = makeRepo(connected = true, lastConnected = device)
        val events = mutableListOf<ConnectionEvent>()

        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = device))
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.reconnect() }
        assertTrue("Es darf kein Versuch gemeldet werden: $events", events.isEmpty())
    }

    // --- B4: an Android refusal to start the foreground service must not undo a connection that stands ---

    private fun refusingContext(error: Exception = IllegalStateException("startForegroundService() not allowed")): Context =
        mockk(relaxed = true) { every { startForegroundService(any()) } throws error }

    @Test
    fun `auto-connect still reports Connected when Android refuses the foreground service`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connectIfDisconnected(device) } just Runs
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, fakeAppPreferences(lastDevice = device), context = refusingContext(), logRepo = logRepo)
            .start(coordinatorScope, events::add)
        advanceUntilIdle()

        assertEquals(
            "die Verbindung steht, das ist das Ergebnis — nicht der Dienst",
            listOf(ConnectionEvent.Attempting(attempt = 0, total = RECONNECT_ATTEMPT_COUNT), ConnectionEvent.Connected),
            events
        )
        coVerify(exactly = 0) { logRepo.log(LogLevel.ERROR, match { it.startsWith("Connection error") }) }
        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("Foreground service could not be started") }) }
    }

    @Test
    fun `a reconnect that succeeds is not logged as a failed attempt when the service start is refused`() = runTest {
        val repo = makeRepo(lastConnected = device)
        coEvery { repo.reconnect() } just Runs
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(
            repo, fakeAppPreferences(lastDevice = null), context = refusingContext(SecurityException("no BLUETOOTH_CONNECT")), logRepo = logRepo,
        )
        coordinator.start(coordinatorScope, events::add)

        coordinator.requestReconnect()
        advanceUntilIdle()

        assertEquals(ConnectionEvent.Connected, events.last())
        coVerify(exactly = 1) { repo.reconnect() }      // kein zweiter Versuch ueber eine stehende Verbindung
        coVerify(exactly = 0) { logRepo.log(LogLevel.WARN, match { it.contains("failed:") }) }
        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("SecurityException") }) }
    }

    // --- reconnectDelays() (Backlog-Cleanup, Sweep 2026-09-28/29) ---
    //
    // RECONNECT_DELAYS was hardcoded as listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L) --
    // reconnectDelays(baseDelaySeconds) generates the same doubling shape from a configurable
    // base, so the coordinator-level tests above (which stub reconnectBaseDelaySeconds to
    // AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS = 3) see byte-for-byte the same delays
    // as before this change.

    // --- Test 12 ---

    @Test
    fun `reconnectDelays with the default base reproduces the previous hardcoded values`() {
        assertEquals(
            listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L),
            reconnectDelays(AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS)
        )
    }

    // --- Test 13 ---

    @Test
    fun `reconnectDelays scales every step with the configured base`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), reconnectDelays(1))
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L), reconnectDelays(10))
    }

    // ── Abbruch und spaete Nutzer-Trennung ─────────────────────────────────────

    @Test
    fun `a cancelled auto-connect is no connection error and starts no reconnect round`() = runTest {
        val repo = makeRepo()
        coEvery { repo.connectIfDisconnected(device) } throws CancellationException("gone")
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()

        makeCoordinator(repo, fakeAppPreferences(lastDevice = device), logRepo = logRepo).start(coordinatorScope, events::add)
        advanceUntilIdle()

        assertEquals("nur die Ankuendigung des Auto-Connects, keine Schleife", listOf<ConnectionEvent>(ConnectionEvent.Attempting(0, RECONNECT_ATTEMPT_COUNT)), events)
        coVerify(exactly = 0) { logRepo.log(LogLevel.ERROR, any()) }
    }

    @Test
    fun `a cancelled reconnect attempt is neither a failed attempt nor the end result Failed`() = runTest {
        val repo = makeRepo(lastConnected = device)
        coEvery { repo.reconnect() } throws CancellationException("gone")
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, fakeAppPreferences(lastDevice = null), logRepo = logRepo)
        coordinator.start(coordinatorScope, events::add)

        coordinator.requestReconnect()
        advanceUntilIdle()

        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Attempting(1, RECONNECT_ATTEMPT_COUNT)), events)
        coVerify(exactly = 0) { logRepo.log(LogLevel.WARN, match { "failed" in it }) }
        coVerify(exactly = 0) { logRepo.log(LogLevel.ERROR, any()) }
    }

    @Test
    fun `a user disconnect while the round still reads its settings aborts before the first attempt`() = runTest {
        // Zwischen dem Beginn der Runde und dem ersten Versuch liegt das Lesen der Einstellung: trennt der Nutzer dort, darf kein Versuch angekuendigt werden.
        val userDisconnects = MutableStateFlow(0)
        val repo = makeRepo(lastConnected = device, userDisconnects = userDisconnects)
        coEvery { repo.reconnect() } just Runs
        val gate = CompletableDeferred<Unit>()
        val prefs = fakeAppPreferences(lastDevice = null)
        every { prefs.reconnectBaseDelaySeconds } returns flow { gate.await(); emit(3) }
        val events = mutableListOf<ConnectionEvent>()
        val coordinator = makeCoordinator(repo, prefs)
        coordinator.start(coordinatorScope, events::add)
        coordinator.requestReconnect()
        runCurrent()                                  // die Runde wartet auf die Einstellung

        userDisconnects.value = 1                     // "Trennen" genau jetzt
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Aborted), events)
        coVerify(exactly = 0) { repo.reconnect() }
    }
}
