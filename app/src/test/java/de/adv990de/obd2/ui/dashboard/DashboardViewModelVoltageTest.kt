package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fakeObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Der Lade-/Entlade-Balken im Zustand des Dashboards: der Trend (Regeln: `VoltageTrendTest`, `VoltageTrendTrackerTest`) steht im `DashboardUiState`, solange Werte fliessen (vor dem Start
 * mit Motor aus, danach bei laufendem Motor), und verschwindet mit jedem Verbindungsereignis — wie Gang und Sparklines. Ein GPS-Zwischenupdate bewegt ihn nicht. Beim Wechsel Motor an/aus bleibt
 * keine Luecke: der Trend steht ab dem ersten Zyklus des neuen Zustands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelVoltageTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Der Rueckruf, den das ViewModel beim Koordinator anmeldet (`ConnectionCoordinator.start`). */
    private var connectionEvents: ((ConnectionEvent) -> Unit)? = null

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Setup(
        val vm: DashboardViewModel,
        val events: MutableSharedFlow<SessionEvent>,
        /** Die monotone Uhr des ViewModels (in JVM-Tests liefert `SystemClock` immer 0). */
        val clock: LongArray,
        val scheduler: TestCoroutineScheduler,
    )

    private fun setUp(scheduler: TestCoroutineScheduler): Setup {
        val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
        val pipeline = mockk<ReadingsPipeline> {
            every { events(any()) } returns events
            coEvery { recordDerived(any()) } just Runs
        }
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
        }
        val prefs: AppPreferences = fakeAppPreferences()
        val clock = longArrayOf(0L)
        // Der Koordinator ist eine Attrappe; sein Ereignis-Rueckruf wird gemerkt, damit ein Test ein Verbindungsereignis (z. B. "Versuch 0") ausloesen kann.
        val coordinator = mockk<ConnectionCoordinator>(relaxed = true)
        every { coordinator.start(any(), any()) } answers { connectionEvents = secondArg() }
        val vm = DashboardViewModel(obdRepo, prefs, coordinator, pipeline, SavedStateHandle())
            .also { it.nowMillis = { clock[0] } }
        return Setup(vm, events, clock, scheduler)
    }

    /** Ein OBD-Zyklus bzw. GPS-Zwischenupdate zum Zeitpunkt [s]; die Uhr des ViewModels laeuft mit (es liest sie beim Verarbeiten, nicht beim Senden). */
    private suspend fun Setup.cycle(s: Long, isObdCycle: Boolean = true, rpm: Float = 2_000f, volt: Float = 13.6f) {
        val t = s * 1000
        clock[0] = t
        val values = listOf(
            SensorValue(PidRegistry.RPM, rpm, timestamp = t),
            SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volt, timestamp = t),
        )
        events.emit(SessionEvent.Values(values, gpsSpeedKmh = null, isObdCycle = isObdCycle))
        scheduler.runCurrent()
    }

    private fun Setup.trend() = vm.uiState.value.voltageTrend

    /** Faehrt [count] Zyklen im Sekundentakt ab [from] und liefert die Sekunde des ersten mit Trend (null, wenn keiner einen hatte). */
    private suspend fun Setup.firstTrendAt(from: Long, count: Int = 40): Long? {
        for (i in 0 until count) {
            cycle(from + i)
            if (trend() != null) return from + i
        }
        return null
    }

    @Test
    fun `with a running engine the trend stands in the state from the first cycle on`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()

        // Ein Verbinden bei laufendem Motor zaehlt als Wechsel: keine Einschwingzeit fuer die Anzeige, schon der erste Zyklus zeigt den Balken.
        assertEquals(0L, s.firstTrendAt(from = 0))
        assertEquals(13.6f, s.trend()!!.volts, 1e-4f)
        assertEquals(ChargeState.Charging, s.trend()!!.state)
    }

    @Test
    fun `with the engine off the trend shows after three samples and says the engine is off`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()

        s.cycle(0, rpm = 0f, volt = 11.9f)
        s.cycle(1, rpm = 0f, volt = 11.9f)
        assertNull("zwei Proben sind noch zu wenig", s.trend())
        s.cycle(2, rpm = 0f, volt = 11.9f)

        val trend = s.trend()!!
        assertEquals(11.9f, trend.volts, 1e-4f)
        assertEquals(ChargeState.Discharging, trend.state)
        assertEquals("Motor aus", false, trend.engineRunning)
    }

    @Test
    fun `starting the engine leaves no gap - the running trend stands at the first cycle and no pre-start value mixes in`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        repeat(6) { s.cycle(it.toLong(), rpm = 0f, volt = 11.9f) }
        assertNotNull("vor dem Start steht ein Stand", s.trend())

        // Der Motor laeuft ab Sekunde 6: der Balken zeigt sofort die Probe dieses Zyklus, nichts von den 11,9 V.
        s.cycle(6, rpm = 2_000f, volt = 13.6f)

        assertEquals(13.6f, s.trend()!!.volts, 1e-4f)
        assertEquals(true, s.trend()!!.engineRunning)
    }

    @Test
    fun `stopping the engine leaves no gap - the pre-start trend stands at the first cycle and no running value mixes in`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        repeat(20) { s.cycle(it.toLong(), rpm = 2_000f, volt = 13.6f) }
        assertEquals(true, s.trend()!!.engineRunning)

        s.cycle(20, rpm = 0f, volt = 12.0f)

        assertEquals(12.0f, s.trend()!!.volts, 1e-4f)
        assertEquals(false, s.trend()!!.engineRunning)
    }

    @Test
    fun `a GPS intermediate update moves nothing`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.firstTrendAt(from = 0)
        val before = s.trend()
        assertNotNull(before)

        // Dieselben Werte noch einmal, hier sogar mit 0 rpm und 9 V: ein Zwischenupdate wiederholt nur, was schon galt.
        s.cycle(30, isObdCycle = false, rpm = 0f, volt = 9f)

        assertEquals(before, s.trend())
    }

    @Test
    fun `idle, a lost connection, stale and a failure each clear the trend and the next session starts from scratch`() = runTest {
        val events = listOf<SessionEvent>(
            SessionEvent.Idle,
            SessionEvent.ConnectionLost(),
            SessionEvent.Stale,
            SessionEvent.Failed(UiText.DynamicString("boom")),
        )
        for (event in events) {
            val s = setUp(testScheduler)
            advanceUntilIdle()
            val firstAt = s.firstTrendAt(from = 0)!!
            repeat(5) { s.cycle(firstAt + 1 + it, volt = 13.6f) }
            assertNotNull("$event: vorher steht ein Trend", s.trend())

            s.events.emit(event)
            s.scheduler.runCurrent()

            assertNull("$event: der Trend ist weg", s.trend())
            // Die neue Sitzung folgt SOFORT (der Motor-Tor haelt die letzte Drehzahl 12 s). Bliebe der Tracker unzurueckgesetzt, laege im Fenster noch das 13,6-V-Mittel der alten Sitzung und der erste neue
            // Wert (12,0 V) ergaebe ein Mittel dazwischen; zurueckgesetzt beginnt das Fenster leer und der Balken zeigt genau diesen Wert.
            s.cycle(firstAt + 7, volt = 12.0f)
            assertEquals("$event: neue Sitzung beginnt von vorn", 12.0f, s.trend()!!.volts, 1e-4f)
        }
    }

    @Test
    fun `idle while a reconnection is running clears the trend too`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.firstTrendAt(from = 0)
        assertNotNull(s.trend())

        // "Versuch 0" (Auto-Connect beim Start) setzt nur das Wiederverbindungs-Flag und laesst den Rest des Zustands stehen: dann nimmt applyIdle den Zweig "Fehler oder Wiederverbindung".
        connectionEvents!!.invoke(ConnectionEvent.Attempting(attempt = 0, total = 5))
        assertNotNull("das Ereignis selbst laesst den Trend stehen", s.trend())

        s.events.emit(SessionEvent.Idle)
        s.scheduler.runCurrent()

        assertNull(s.trend())
    }
}
