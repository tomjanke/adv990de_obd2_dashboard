package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmController
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.alarm.AlarmSeverity
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fakeObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Das ViewModel reicht die Ereignisse der Sitzung an den `AlarmController` weiter (Regeln und Meldungen: `AlarmTrackerTest`, `AlarmControllerTest`): nur echte OBD-Zyklen
 * werten aus, die Fehlercodes gehen durch, das Ende der Sitzung loescht Spannung und Kuehlmittel — nicht den Fehler-Alarm —, und die Einstellung wirkt live. Die Alarme
 * stehen bewusst NICHT im `DashboardUiState`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelAlarmTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Setup(
        val vm: DashboardViewModel,
        val events: MutableSharedFlow<SessionEvent>,
        val settings: MutableStateFlow<AlarmSettings>,
        /** Die monotone Uhr des ViewModels (in JVM-Tests liefert `SystemClock` immer 0). */
        val clock: LongArray,
        val scheduler: TestCoroutineScheduler,
    )

    private fun setUp(
        scheduler: TestCoroutineScheduler,
        alarmController: AlarmController = AlarmController(AlarmNotifier.None, AlarmLog.None),
    ): Setup {
        val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
        val settings = MutableStateFlow(AlarmSettings())
        val pipeline = mockk<ReadingsPipeline> {
            every { events(any()) } returns events
            coEvery { recordDerived(any()) } just Runs
        }
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
        }
        val prefs: AppPreferences = fakeAppPreferences().also { every { it.alarmSettings } returns settings }
        val clock = longArrayOf(0L)
        val vm = DashboardViewModel(
            obdRepo, prefs, mockk(relaxed = true), pipeline, SavedStateHandle(),
            alarmController = alarmController,
        ).also { it.nowMillis = { clock[0] } }
        return Setup(vm, events, settings, clock, scheduler)
    }

    /**
     * Ein OBD-Zyklus zum Zeitpunkt [s] Sekunden; die Uhr des ViewModels laeuft mit. Das ViewModel liest die Uhr erst beim VERARBEITEN des Ereignisses, nicht beim Senden:
     * jedes Ereignis wird deshalb abgearbeitet, bevor die Uhr weiterspringt.
     */
    private suspend fun Setup.cycle(s: Long, isObdCycle: Boolean = true, rpm: Float = 2_000f, volt: Float? = null, coolant: Float? = null) {
        val t = s * 1000
        clock[0] = t
        val values = buildList {
            add(SensorValue(PidRegistry.RPM, rpm, timestamp = t))
            if (volt != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volt, timestamp = t))
            if (coolant != null) add(SensorValue(PidRegistry.COOLANT_TEMP, coolant, timestamp = t))
        }
        events.emit(SessionEvent.Values(values, gpsSpeedKmh = null, isObdCycle = isObdCycle))
        scheduler.runCurrent()
    }

    private fun conf(code: String) = DtcEntry(code, isPending = false)

    /** Merkt sich, was an den Notifier ging; [failing] laesst jeden Aufruf nach dem Aufzeichnen werfen. */
    private class RecordingNotifier(private val failing: Boolean = false) : AlarmNotifier {
        val shown = mutableListOf<AlarmKind>()
        val cleared = mutableListOf<AlarmKind>()
        override fun show(alarm: Alarm) { shown += alarm.kind; if (failing) throw IllegalStateException("notifier down") }
        override fun clear(kind: AlarmKind) { cleared += kind; if (failing) throw IllegalStateException("notifier down") }
    }

    private suspend fun Setup.faultAlarm() {
        events.emit(SessionEvent.FaultCodes(emptyList(), isBaseline = true))
        events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
    }

    private fun Setup.kinds() = vm.alarms.value.map { it.kind to it.severity }

    // --- Zyklen ---

    @Test
    fun `an OBD cycle with a hot coolant for six seconds raises the coolant alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)
        advanceUntilIdle()

        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), s.kinds())
    }

    @Test
    fun `a GPS intermediate update does not evaluate - the same values and a running clock are no alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, isObdCycle = false, coolant = 101f)               // GPS repeats the old values six seconds later
        advanceUntilIdle()
        assertTrue("six seconds of clock alone are no six seconds of measurement", s.vm.alarms.value.isEmpty())

        s.cycle(6, coolant = 101f)                                   // a real cycle counts: the breach began at 0
        advanceUntilIdle()
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), s.kinds())
    }

    // --- Fehlercodes ---

    @Test
    fun `fault codes - a baseline and two reads with a new code raise the fault alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.faultAlarm()
        advanceUntilIdle()

        assertEquals(listOf(Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))), s.vm.alarms.value)
    }

    @Test
    fun `a new baseline read replaces the old baseline - its codes are not new`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.events.emit(SessionEvent.FaultCodes(emptyList(), isBaseline = true))
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = true))     // the code was there when connecting again
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        advanceUntilIdle()

        assertTrue("with the flag dropped the baseline read would be an ordinary read and the code would alarm", s.vm.alarms.value.isEmpty())
    }

    @Test
    fun `a failed baseline read makes the next successful read the silent baseline`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.events.emit(SessionEvent.FaultCodes(emptyList(), isBaseline = true))
        s.events.emit(SessionEvent.FaultCodes(null, isBaseline = true))                      // connecting again: the read failed, the old baseline is void
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        advanceUntilIdle()

        assertTrue(s.vm.alarms.value.isEmpty())
    }

    @Test
    fun `a stale phase keeps the fault baseline - a code that appears afterwards raises the fault alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.events.emit(SessionEvent.FaultCodes(emptyList(), isBaseline = true))
        s.events.emit(SessionEvent.Stale)                             // the session goes on, no new baseline read follows
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        advanceUntilIdle()

        assertEquals(listOf(Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))), s.vm.alarms.value)
    }

    @Test
    fun `the baseline of a new session replaces what the lost one knew`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.events.emit(SessionEvent.FaultCodes(emptyList(), isBaseline = true))
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))    // a candidate of the first session
        s.events.emit(SessionEvent.ConnectionLost)
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = true))     // the code is part of the new session's baseline
        s.events.emit(SessionEvent.FaultCodes(listOf(conf("P0301")), isBaseline = false))
        advanceUntilIdle()

        assertTrue("the baseline event, not the session end, is what resets the watcher", s.vm.alarms.value.isEmpty())
    }

    // --- Ende der Sitzung ---

    /** Voltage and coolant alarms plus the fault alarm; then [end] ends the session: only the fault alarm remains. */
    private suspend fun TestScope.sessionEndKeepsOnlyTheFault(end: SessionEvent) {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        for (sec in 0L..25L step 5) s.cycle(sec, volt = 12.0f, coolant = 101f)
        s.faultAlarm()
        advanceUntilIdle()
        assertEquals(
            setOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE, AlarmKind.FAULT_CODE),
            s.vm.alarms.value.map { it.kind }.toSet(),
        )

        s.events.emit(end)
        advanceUntilIdle()

        assertEquals(listOf(AlarmKind.FAULT_CODE), s.vm.alarms.value.map { it.kind })
    }

    @Test
    fun `Idle clears voltage and coolant but not the fault alarm`() = runTest { sessionEndKeepsOnlyTheFault(SessionEvent.Idle) }

    @Test
    fun `Stale clears voltage and coolant but not the fault alarm`() = runTest { sessionEndKeepsOnlyTheFault(SessionEvent.Stale) }

    @Test
    fun `ConnectionLost clears voltage and coolant but not the fault alarm`() = runTest { sessionEndKeepsOnlyTheFault(SessionEvent.ConnectionLost) }

    @Test
    fun `Failed clears voltage and coolant but not the fault alarm`() = runTest {
        sessionEndKeepsOnlyTheFault(SessionEvent.Failed(UiText.DynamicString("boom")))
    }

    @Test
    fun `the alarms do not live in the dashboard state - a disconnect resets the state and leaves the fault alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.faultAlarm()
        advanceUntilIdle()
        assertEquals(1, s.vm.alarms.value.size)

        s.events.emit(SessionEvent.Idle)                              // applyIdle() resets the state to DashboardUiState.disconnected()
        advanceUntilIdle()

        assertEquals(DashboardUiState.disconnected(), s.vm.uiState.value)
        assertEquals(listOf(AlarmKind.FAULT_CODE), s.vm.alarms.value.map { it.kind })
    }

    // --- Einstellungen und Quittieren ---

    @Test
    fun `switching the coolant alarm off in the settings clears it live`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)
        advanceUntilIdle()
        assertEquals(1, s.vm.alarms.value.size)

        s.settings.value = AlarmSettings(coolantEnabled = false)
        advanceUntilIdle()

        assertTrue(s.vm.alarms.value.isEmpty())
    }

    @Test
    fun `the settings that were stored before the first cycle apply to it`() = runTest {
        val s = setUp(testScheduler)
        s.settings.value = AlarmSettings(coolantEnabled = false)
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)
        advanceUntilIdle()

        assertTrue(s.vm.alarms.value.isEmpty())
    }

    @Test
    fun `acknowledgeAlarm hides the alarm`() = runTest {
        val s = setUp(testScheduler)
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)
        advanceUntilIdle()
        assertEquals(1, s.vm.alarms.value.size)

        s.vm.acknowledgeAlarm(AlarmKind.COOLANT)
        advanceUntilIdle()

        assertTrue(s.vm.alarms.value.isEmpty())
    }

    // --- Beiwerk darf die Live-Anzeige nie beenden ---

    @Test
    fun `a notifier that throws does not stop the session event collector`() = runTest {
        val notifier = RecordingNotifier(failing = true)
        val s = setUp(testScheduler, AlarmController(notifier, AlarmLog.None))
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)                                    // the alarm is raised, show throws
        assertEquals(listOf(AlarmKind.COOLANT), notifier.shown)
        assertEquals(1, s.vm.alarms.value.size)

        s.cycle(7, rpm = 3_000f, coolant = 101f)                      // the collector is alive: the next event still updates the state
        advanceUntilIdle()

        assertEquals(3_000f, s.vm.uiState.value.rpmValue, 0.01f)
    }

    // --- Das ViewModel endet ---

    private fun clearViewModel(vm: DashboardViewModel) {
        val onCleared = DashboardViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)
    }

    @Test
    fun `clearing the view model takes the numeric notifications back - the fault notification stays`() = runTest {
        val notifier = RecordingNotifier()
        val s = setUp(testScheduler, AlarmController(notifier, AlarmLog.None))
        advanceUntilIdle()
        s.cycle(0, coolant = 101f)
        s.cycle(6, coolant = 101f)
        s.faultAlarm()
        advanceUntilIdle()
        assertEquals(setOf(AlarmKind.COOLANT, AlarmKind.FAULT_CODE), notifier.shown.toSet())
        assertTrue(notifier.cleared.isEmpty())

        clearViewModel(s.vm)

        assertEquals(listOf(AlarmKind.COOLANT), notifier.cleared)
    }
}
