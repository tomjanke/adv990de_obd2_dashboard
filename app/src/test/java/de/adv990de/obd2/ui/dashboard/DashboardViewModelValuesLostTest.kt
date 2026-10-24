package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fakeObdRepository
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fehlende Fahrzeugwerte im Zustand des Dashboards (Spezifikation 2026-10-24): die Ursache ("eingefroren" oder "keine aktuellen Werte"), die GPS-Geschwindigkeit,
 * die in diesem Zustand weiterlaeuft (D3), der Tankzaehler, der dabei weiterzaehlt (D4), und die Ursache des Neuverbindens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelValuesLostTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Der Rueckruf, den das ViewModel beim Koordinator anmeldet (`ConnectionCoordinator.start`). */
    private var connectionEvents: ((ConnectionEvent) -> Unit)? = null

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Setup(
        val vm: DashboardViewModel,
        val events: MutableSharedFlow<SessionEvent>,
        val clock: LongArray,
        val scheduler: TestCoroutineScheduler,
        /** `ObdRepository.isConnectedFlow`: wer ihn auf wahr setzt, laesst das ViewModel den Link als aufgebaut fuehren (`onLinkUp`). */
        val connected: MutableStateFlow<Boolean>,
    )

    private fun setUp(scheduler: TestCoroutineScheduler, fuel: FuelDistanceSink = FuelDistanceSink.None): Setup {
        val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
        val pipeline = mockk<ReadingsPipeline> {
            every { events(any()) } returns events
            coEvery { recordDerived(any()) } just Runs
        }
        val connected = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns connected
            every { supportedPidIds } returns MutableStateFlow(null)
        }
        val clock = longArrayOf(0L)
        val coordinator = mockk<ConnectionCoordinator>(relaxed = true)
        every { coordinator.start(any(), any()) } answers { connectionEvents = secondArg() }
        val vm = DashboardViewModel(obdRepo, fakeAppPreferences(), coordinator, pipeline, SavedStateHandle(), fuelDistanceSink = fuel)
            .also { it.nowMillis = { clock[0] } }
        return Setup(vm, events, clock, scheduler, connected)
    }

    private suspend fun Setup.send(event: SessionEvent) {
        events.emit(event)
        scheduler.runCurrent()
    }

    /** Ein OBD-Zyklus zur Sekunde [s] (die Uhr des ViewModels laeuft mit) mit der rohen GPS-Geschwindigkeit [gps]. */
    private suspend fun Setup.cycle(s: Long, gps: Float? = null, isObdCycle: Boolean = true) {
        clock[0] = s * 1000
        send(
            SessionEvent.Values(
                listOf(
                    SensorValue(PidRegistry.RPM, 2_000f, timestamp = s * 1000),
                    SensorValue(PidRegistry.COOLANT_TEMP, 85f, timestamp = s * 1000),
                ),
                gpsSpeedKmh = gps,
                isObdCycle = isObdCycle,
            )
        )
    }

    private suspend fun Setup.gpsSpeed(s: Long, kmh: Float?) {
        clock[0] = s * 1000
        send(SessionEvent.GpsSpeed(kmh))
    }

    private val Setup.state get() = vm.uiState.value

    // --- Ursache und Phase ---

    @Test
    fun `a hold turns the display stale at once and names the cause frozen`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        assertEquals(DashboardPhase.Live, dashboardPhase(s.state.isConnected, s.state.awaitingFirstValues, s.state.isStale))

        s.send(SessionEvent.Frozen)

        assertTrue(s.state.isStale)
        assertEquals(ValuesLostCause.FROZEN, s.state.valuesLost)
        assertEquals(DashboardPhase.Stale, dashboardPhase(s.state.isConnected, s.state.awaitingFirstValues, s.state.isStale))
        assertTrue("die Fahrzeugwerte sind weg", s.state.readings.isEmpty())
        assertEquals(0f, s.state.rpmValue, 0f)
    }

    @Test
    fun `an adapter that falls silent is stale with the cause silent`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)

        s.send(SessionEvent.Stale)

        assertTrue(s.state.isStale)
        assertEquals(ValuesLostCause.SILENT, s.state.valuesLost)
    }

    @Test
    fun `the stale limit during a hold keeps the cause frozen`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        s.send(SessionEvent.Frozen)

        s.send(SessionEvent.Stale)

        assertEquals(ValuesLostCause.FROZEN, s.state.valuesLost)
        assertTrue(s.state.isStale)
    }

    @Test
    fun `the next cycle with values ends it - nothing of the lost state stays`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, gps = 50f)
        s.send(SessionEvent.Frozen)
        s.gpsSpeed(3, 60f)

        s.cycle(5, gps = 61f)

        assertFalse(s.state.isStale)
        assertNull(s.state.valuesLost)
        assertNull("die GPS-Anzeige gehoert nur zu 'veraltet'", s.state.gpsSpeedKmh)
        assertEquals(2_000f, s.state.rpmValue, 0f)
    }

    // --- D3: GPS-Geschwindigkeit ---

    @Test
    fun `in the stale state the GPS speed of the last cycle is shown, then follows the GPS`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, gps = 48f)

        s.send(SessionEvent.Frozen)
        assertEquals("ohne ein neues GPS-Ereignis steht der letzte Wert", 48f, s.state.gpsSpeedKmh)

        s.gpsSpeed(2, 55f)
        assertEquals(55f, s.state.gpsSpeedKmh)

        s.gpsSpeed(3, null)
        assertNull("ohne Fix zeigt die Kachel Striche", s.state.gpsSpeedKmh)
    }

    @Test
    fun `a GPS update never turns the stale state live`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        s.send(SessionEvent.Stale)

        s.gpsSpeed(2, 70f)

        assertTrue(s.state.isStale)
        assertEquals(ValuesLostCause.SILENT, s.state.valuesLost)
        assertTrue(s.state.readings.isEmpty())
    }

    @Test
    fun `a GPS speed event outside the stale state is not shown`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, gps = 40f)

        s.gpsSpeed(1, 45f)

        assertNull(s.state.gpsSpeedKmh)
    }

    @Test
    fun `idle, a lost connection and a failure clear the GPS display and the cause`() = runTest {
        val events = listOf<SessionEvent>(
            SessionEvent.Idle,
            SessionEvent.ConnectionLost(),
            SessionEvent.Failed(UiText.DynamicString("boom")),
        )
        for (event in events) {
            val s = setUp(testScheduler)
            advanceUntilIdle()
            s.cycle(0, gps = 50f)
            s.send(SessionEvent.Frozen)
            assertEquals(ValuesLostCause.FROZEN, s.state.valuesLost)

            s.send(event)

            assertNull("$event: keine Ursache mehr", s.state.valuesLost)
            assertNull("$event: keine GPS-Anzeige mehr", s.state.gpsSpeedKmh)
        }
    }

    @Test
    fun `an idle that keeps a running connect attempt still clears the lost state and the GPS display`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, gps = 50f)
        s.send(SessionEvent.Frozen)
        connectionEvents!!(ConnectionEvent.Attempting(0, 5))     // ein Verbindungsversuch laeuft: Idle raeumt nur die Werte ab, nicht den Versuch
        assertTrue(s.state.isReconnecting)

        s.send(SessionEvent.Idle)

        assertTrue("der Versuch bleibt", s.state.isReconnecting)
        assertFalse(s.state.isStale)
        assertNull("ohne Werte keine Ursache", s.state.valuesLost)
        assertNull("und keine GPS-Anzeige", s.state.gpsSpeedKmh)
    }

    // --- D4: Tankanzeige ---

    @Test
    fun `the fuel counter keeps counting the GPS distance in the stale state`() = runTest {
        val sink = mockk<FuelDistanceSink>(relaxed = true)
        val s = setUp(testScheduler, sink)
        advanceUntilIdle()
        val km = mutableListOf<Float>()
        every { sink.addDistance(capture(km)) } just Runs
        s.cycle(0, gps = 72f)

        s.send(SessionEvent.Frozen)
        s.gpsSpeed(10, 72f)
        s.gpsSpeed(20, 72f)

        assertEquals("72 km/h ueber 20 s sind 0,4 km, auch ohne Fahrzeugwerte", 0.4f, km.sum(), 1e-3f)
        verify(exactly = 0) { sink.flush() }
    }

    @Test
    fun `plain stale also keeps counting - the session is still connected`() = runTest {
        val sink = mockk<FuelDistanceSink>(relaxed = true)
        val s = setUp(testScheduler, sink)
        advanceUntilIdle()
        val km = mutableListOf<Float>()
        every { sink.addDistance(capture(km)) } just Runs
        s.cycle(0, gps = 36f)

        s.send(SessionEvent.Stale)
        s.gpsSpeed(10, 36f)

        assertEquals(0.1f, km.sum(), 1e-3f)
    }

    @Test
    fun `a lost connection still ends the interval - nothing is invented across the gap`() = runTest {
        val sink = mockk<FuelDistanceSink>(relaxed = true)
        val s = setUp(testScheduler, sink)
        advanceUntilIdle()
        val km = mutableListOf<Float>()
        every { sink.addDistance(capture(km)) } just Runs
        s.cycle(0, gps = 72f)

        s.send(SessionEvent.ConnectionLost())
        s.cycle(5, gps = 72f)

        assertTrue("$km", km.isEmpty())
        verify(atLeast = 1) { sink.flush() }
    }

    // --- Ursache des Neuverbindens ---

    @Test
    fun `a reconnect after frozen values keeps its cause through the numbered attempts and ends with the connection`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        s.send(SessionEvent.Frozen)

        s.send(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES))
        assertTrue(s.state.isReconnecting)
        assertEquals(ConnectionLostCause.FROZEN_VALUES, s.state.reconnectCause)
        assertNull("die Sperre gehoert zur beendeten Sitzung", s.state.valuesLost)

        s.send(SessionEvent.Idle)                              // der Messwert-Strom meldet das Ende der Verbindung gleich danach
        assertEquals(ConnectionLostCause.FROZEN_VALUES, s.state.reconnectCause)

        connectionEvents!!(ConnectionEvent.Attempting(1, 5))
        assertEquals(1, s.state.reconnectAttempt)
        assertEquals("der Versuch ersetzt den Zustand, nicht den Grund", ConnectionLostCause.FROZEN_VALUES, s.state.reconnectCause)

        connectionEvents!!(ConnectionEvent.Connected)
        assertFalse(s.state.isReconnecting)
        assertNull(s.state.reconnectCause)
    }

    @Test
    fun `a plain lost connection names the link as the cause`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)

        s.send(SessionEvent.ConnectionLost())

        assertEquals(ConnectionLostCause.LINK, s.state.reconnectCause)
    }

    @Test
    fun `a failed or aborted reconnect and fresh values leave no cause behind`() = runTest {
        for (end in listOf<(Setup) -> Unit>(
            { connectionEvents!!(ConnectionEvent.Failed(UiText.DynamicString("weg"))) },
            { connectionEvents!!(ConnectionEvent.Aborted) },
        )) {
            val s = setUp(testScheduler)
            advanceUntilIdle()
            s.cycle(0)
            s.send(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES))
            connectionEvents!!(ConnectionEvent.Attempting(2, 5))

            end(s)

            assertNull(s.state.reconnectCause)
            assertFalse(s.state.isReconnecting)
        }

        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        s.send(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES))
        s.cycle(9)
        assertNull("frische Werte beenden das Neuverbinden", s.state.reconnectCause)
        assertFalse(s.state.isReconnecting)
    }

    @Test
    fun `the link coming up ends the reconnect cause too`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0)
        s.send(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES))
        assertEquals(ConnectionLostCause.FROZEN_VALUES, s.state.reconnectCause)

        s.connected.value = true
        s.scheduler.runCurrent()

        assertTrue(s.state.isConnected)
        assertFalse(s.state.isReconnecting)
        assertNull(s.state.reconnectCause)
    }
}
