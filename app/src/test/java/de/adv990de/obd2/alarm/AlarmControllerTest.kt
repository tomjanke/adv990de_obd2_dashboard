package de.adv990de.obd2.alarm

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Der Controller meldet nur, was sich AENDERT: neuer Alarm, hoehere Stufe, neue Codes (`show`, jedes Mal mit Ton) oder erloschene Art (`clear`) — nie wegen eines neuen
 * Werts bei gleicher Stufe. Die Regeln selbst stehen im `AlarmTracker` (eigene Tests); hier geht es um die Meldungen, die Quittierung und die Reihenfolge der Aufrufe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmControllerTest {

    private sealed interface Call {
        data class Show(val alarm: Alarm) : Call
        data class Clear(val kind: AlarmKind) : Call
    }

    private class RecordingNotifier : AlarmNotifier {
        val calls = mutableListOf<Call>()

        /** Was ein Aufruf wirft (nach dem Aufzeichnen); `null` = nichts. */
        var showFailure: (Alarm) -> Throwable? = { null }
        var clearFailure: (AlarmKind) -> Throwable? = { null }

        override fun show(alarm: Alarm) {
            calls += Call.Show(alarm)
            showFailure(alarm)?.let { throw it }
        }

        override fun clear(kind: AlarmKind) {
            calls += Call.Clear(kind)
            clearFailure(kind)?.let { throw it }
        }

        fun shown() = calls.filterIsInstance<Call.Show>().map { it.alarm }
        fun cleared() = calls.filterIsInstance<Call.Clear>().map { it.kind }
    }

    private class RecordingLog : AlarmLog {
        val entries = mutableListOf<Pair<LogLevel, String>>()
        override suspend fun log(level: LogLevel, message: String) { entries += level to message }
    }

    private class Fixture(val log: AlarmLog = RecordingLog(), tracker: AlarmTracker = AlarmTracker()) {
        val notifier = RecordingNotifier()
        val controller = AlarmController(notifier, log, tracker)

        /** Ein OBD-Zyklus zum Zeitpunkt [ms] Millisekunden; null = der Wert fehlt in diesem Zyklus. */
        suspend fun cycleMs(ms: Long, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) {
            val values = buildList {
                if (rpm != null) add(SensorValue(PidRegistry.RPM, rpm, timestamp = ms))
                if (volt != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volt, timestamp = ms))
                if (coolant != null) add(SensorValue(PidRegistry.COOLANT_TEMP, coolant, timestamp = ms))
            }
            controller.onCycle(ms, values)
        }

        /** Ein OBD-Zyklus zum Zeitpunkt [s] Sekunden; null = der Wert fehlt in diesem Zyklus. */
        suspend fun cycle(s: Long, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) = cycleMs(s * 1000, rpm, volt, coolant)

        suspend fun cycles(seconds: LongProgression, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) =
            seconds.forEach { cycle(it, rpm, volt, coolant) }

        suspend fun faultReads(vararg reads: List<DtcEntry>) = reads.forEach { controller.onFaultCodes(it, isBaseline = false) }

        /** Spannung und Kuehlmittel werden im selben Zyklus (25 s) zur Warnung: die Spannung ab 15 s (Einschwingzeit), das Kuehlmittel ab 20 s. */
        suspend fun bothAlarmsInOneCycle() {
            cycles(0L..14L)
            cycles(15L..19L, volt = 12.0f)
            cycles(20L..24L, volt = 12.0f, coolant = 101f)
            cycle(25L, volt = 12.0f, coolant = 101f)
        }
    }

    private fun conf(code: String) = DtcEntry(code, isPending = false)
    private fun pend(code: String) = DtcEntry(code, isPending = true)

    private fun Fixture.rec() = log as RecordingLog

    private fun Fixture.warnings(prefix: String) = rec().entries.filter { it.first == LogLevel.WARN && it.second.startsWith(prefix) }.map { it.second }

    // --- Ausloesen: genau einmal ---

    @Test
    fun `a voltage alarm shows exactly once - not for every sample`() = runTest {
        val f = Fixture()
        f.cycles(0L..25L step 5, volt = 12.0f)                       // the warning starts at 25 s
        assertEquals(listOf(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, 12.0f)), f.notifier.shown())
        f.cycles(30L..60L step 5, volt = 11.9f)                      // the alarm lasts, the value keeps changing
        assertEquals(1, f.notifier.calls.size)
        assertEquals(1, f.rec().entries.size)
    }

    @Test
    fun `a cycle that changes nothing tells nobody anything`() = runTest {
        val f = Fixture()
        f.cycles(0L..10L, coolant = 80f)
        assertTrue(f.notifier.calls.isEmpty())
        assertTrue(f.rec().entries.isEmpty())
    }

    @Test
    fun `a higher severity shows again`() = runTest {
        val f = Fixture()
        f.cycles(0L..20L, coolant = 96f)                             // notice after 10 s
        f.cycles(21L..30L, coolant = 101f)                           // warning after 5 s above 100
        assertEquals(
            listOf(AlarmSeverity.NOTICE, AlarmSeverity.WARNING),
            f.notifier.shown().map { it.severity },
        )
    }

    @Test
    fun `the same severity with a new value does not show again`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.cycles(7L..12L, coolant = 104f)
        assertEquals(1, f.notifier.shown().size)
        assertEquals(101f, f.notifier.shown().single().value)        // the notification stays as it was shown
    }

    @Test
    fun `a drop in severity is no new alarm - a later rise is`() = runTest {
        val f = Fixture()
        f.cycles(0L..35L step 5, volt = 12.0f)                       // warning at 25 s
        assertEquals(listOf(AlarmSeverity.WARNING), f.notifier.shown().map { it.severity })
        f.cycles(40L..45L step 5, volt = 12.6f)                      // the warning clears, the notice holds
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.NOTICE), f.controller.alarms.value.map { it.kind to it.severity })
        assertEquals("a drop does not alert again", 1, f.notifier.shown().size)
        f.cycles(50L..60L step 5, volt = 12.0f)                      // the warning is back
        assertEquals(listOf(AlarmSeverity.WARNING, AlarmSeverity.WARNING), f.notifier.shown().map { it.severity })
    }

    // --- Erloeschen ---

    @Test
    fun `an alarm that ends clears its notification and logs it`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.cycles(7L..30L, coolant = 85f)                             // below 100 - 3 °C: the alarm ends
        assertEquals(listOf(AlarmKind.COOLANT), f.notifier.cleared())
        assertEquals(LogLevel.INFO to "Alarm cleared: COOLANT", f.rec().entries.last())
        assertTrue(f.controller.alarms.value.isEmpty())
    }

    @Test
    fun `an alarm that goes away and returns shows again`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.cycles(7L..30L, coolant = 85f)
        f.cycles(31L..40L, coolant = 101f)
        assertEquals(2, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    // --- Ein Drehzahl-Zeitlimit ---

    @Test
    fun `one rpm timeout neither clears nor shows a numeric alarm again`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)                             // warning at 5 s, last rpm at 6 s
        assertEquals(1, f.notifier.shown().size)
        // Nur die Drehzahl lief ins Zeitlimit: der Zyklus kommt 6,5 s nach dem letzten mit Drehzahl, ohne Drehzahl, mit Kuehlmittel.
        f.cycleMs(12_500, rpm = null, coolant = 101f)
        f.cycleMs(13_500, coolant = 101f)                            // the rpm is back
        f.cycles(14L..30L, coolant = 101f)                           // long enough for a restarted five-second rule to fire again
        assertEquals("no second alert", 1, f.notifier.shown().size)
        assertTrue("no clear: the alarm never ended", f.notifier.cleared().isEmpty())
        assertEquals(1, f.rec().entries.size)                        // the one "raised" line, no "cleared"
        assertEquals(listOf(AlarmKind.COOLANT), f.controller.alarms.value.map { it.kind })
    }

    @Test
    fun `one rpm timeout does not bring back an acknowledged alarm`() = runTest {
        val f = Fixture()
        f.cycles(0L..25L step 5, volt = 12.0f)                       // voltage warning at 25 s
        f.controller.acknowledge(AlarmKind.VOLTAGE)
        assertEquals(listOf(AlarmKind.VOLTAGE), f.notifier.cleared())    // the clear of the acknowledgement
        f.cycleMs(31_500, rpm = null, volt = 12.0f)                  // one rpm timeout
        f.cycles(33L..60L, volt = 12.0f)                             // a restarted 15 s settle time would be over by now and alarm again
        assertEquals("no second alert", 1, f.notifier.shown().size)
        assertEquals("only the clear of the acknowledgement", listOf(AlarmKind.VOLTAGE), f.notifier.cleared())
        assertTrue(f.controller.alarms.value.isEmpty())
    }

    // --- Fehler-Alarm ---

    @Test
    fun `a new fault code shows once and more codes show again with the longer list`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.faultReads(listOf(conf("P0301")))
        assertTrue("one read is not enough", f.notifier.calls.isEmpty())
        f.faultReads(listOf(conf("P0301")))
        assertEquals(listOf(listOf("P0301")), f.notifier.shown().map { it.codes })
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        assertEquals("the same codes do not alert again", 1, f.notifier.shown().size)
        f.faultReads(listOf(conf("P0301"), conf("P0420")), listOf(conf("P0301"), conf("P0420")))
        assertEquals(listOf(listOf("P0301"), listOf("P0301", "P0420")), f.notifier.shown().map { it.codes })
    }

    @Test
    fun `acknowledging the fault alarm ends it with exactly one clear`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        f.controller.acknowledge(AlarmKind.FAULT_CODE)
        assertEquals(listOf(AlarmKind.FAULT_CODE), f.notifier.cleared())
        assertTrue(f.controller.alarms.value.isEmpty())
        // Der Alarm endete, das Log schliesst das Auslosen ab (kein "acknowledged"-Eintrag): raised, cleared.
        assertEquals(
            listOf(LogLevel.WARN to "Alarm raised: FAULT_CODE WARNING P0301", LogLevel.INFO to "Alarm cleared: FAULT_CODE"),
            f.rec().entries,
        )
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))      // nothing new: no second alert
        assertEquals(1, f.notifier.shown().size)
        assertEquals(1, f.notifier.cleared().size)
    }

    // --- Quittieren eines Zahlen-Alarms ---

    @Test
    fun `acknowledging a numeric alarm clears the notification and hides it - without showing it again`() = runTest {
        val f = Fixture()
        f.cycles(0L..25L step 5, volt = 12.0f)
        assertEquals(1, f.controller.alarms.value.size)
        f.controller.acknowledge(AlarmKind.VOLTAGE)
        assertEquals(listOf(AlarmKind.VOLTAGE), f.notifier.cleared())
        assertTrue(f.controller.alarms.value.isEmpty())
        f.cycles(30L..60L step 5, volt = 11.9f)                      // the same alarm lasts
        assertEquals("no second alert for the acknowledged alarm", 1, f.notifier.shown().size)
        assertEquals("one clear, from the acknowledgement", 1, f.notifier.cleared().size)
        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals("nothing is logged for an acknowledgement", 1, f.rec().entries.size)
    }

    @Test
    fun `an escalation after an acknowledgement shows the alarm and alerts again`() = runTest {
        val f = Fixture()
        f.cycles(0L..20L, coolant = 96f)                             // notice
        f.controller.acknowledge(AlarmKind.COOLANT)
        assertTrue(f.controller.alarms.value.isEmpty())
        f.cycles(21L..30L, coolant = 101f)                           // warning
        assertEquals(listOf(AlarmSeverity.NOTICE, AlarmSeverity.WARNING), f.notifier.shown().map { it.severity })
        assertEquals(listOf(AlarmSeverity.WARNING), f.controller.alarms.value.map { it.severity })
    }

    @Test
    fun `an acknowledged alarm that ends is logged as cleared and shows again when it returns`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.controller.acknowledge(AlarmKind.COOLANT)
        f.cycles(7L..30L, coolant = 85f)
        assertEquals(LogLevel.INFO to "Alarm cleared: COOLANT", f.rec().entries.last())
        f.cycles(31L..40L, coolant = 101f)
        assertEquals(2, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    @Test
    fun `acknowledging a kind without an alarm does nothing`() = runTest {
        val f = Fixture()
        f.controller.acknowledge(AlarmKind.COOLANT)
        f.controller.acknowledge(AlarmKind.FAULT_CODE)
        assertTrue(f.notifier.calls.isEmpty())
        assertTrue(f.rec().entries.isEmpty())
        f.cycles(0L..6L, coolant = 101f)                             // the later alarm is neither hidden nor silent
        assertEquals(1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    // --- Ende der Sitzung ---

    @Test
    fun `the session end clears voltage and coolant but not the fault alarm`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 101f)
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        assertEquals(3, f.notifier.shown().size)

        f.controller.onSessionEnded()

        assertEquals(setOf(AlarmKind.VOLTAGE, AlarmKind.COOLANT), f.notifier.cleared().toSet())
        assertEquals(2, f.notifier.cleared().size)
        assertEquals(listOf(AlarmKind.FAULT_CODE), f.controller.alarms.value.map { it.kind })
        assertEquals(
            setOf("Alarm cleared: VOLTAGE", "Alarm cleared: COOLANT"),
            f.rec().entries.filter { it.first == LogLevel.INFO }.map { it.second }.toSet(),
        )
    }

    // --- Einstellungen ---

    @Test
    fun `switching an alarm off clears it and its notification`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.controller.updateSettings(AlarmSettings(coolantEnabled = false))
        assertEquals(listOf(AlarmKind.COOLANT), f.notifier.cleared())
        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals(LogLevel.INFO to "Alarm cleared: COOLANT", f.rec().entries.last())
    }

    @Test
    fun `switching the fault alarm off clears it`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        f.controller.updateSettings(AlarmSettings(faultsEnabled = false))
        assertEquals(listOf(AlarmKind.FAULT_CODE), f.notifier.cleared())
        assertTrue(f.controller.alarms.value.isEmpty())
    }

    @Test
    fun `settings that change nothing tell nobody anything`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.controller.updateSettings(AlarmSettings())
        assertEquals(1, f.notifier.calls.size)
        assertEquals(1, f.rec().entries.size)
    }

    // --- Anzeige und Log ---

    @Test
    fun `the alarms are sorted by severity and then by kind`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 96f)        // voltage warning, coolant notice
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))   // fault warning
        assertEquals(
            listOf(AlarmKind.VOLTAGE, AlarmKind.FAULT_CODE, AlarmKind.COOLANT),
            f.controller.alarms.value.map { it.kind },
        )
    }

    @Test
    fun `the log lines name the kind, the severity and the value or the codes`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 96f)
        f.faultReads(listOf(conf("P0301"), pend("P0171")), listOf(conf("P0301"), pend("P0171")))
        val raised = f.rec().entries.filter { it.first == LogLevel.WARN }.map { it.second }
        assertEquals(
            listOf(
                "Alarm raised: COOLANT NOTICE 96",
                "Alarm raised: VOLTAGE WARNING 12.0",
                "Alarm raised: FAULT_CODE WARNING P0171, P0301",       // the order of the watcher
            ),
            raised,
        )
    }

    // --- Reihenfolge der Aufrufe (Mutex) ---

    /** Ein Log, dessen erster Eintrag haengt, bis [gate] frei ist; geschrieben wird erst NACH dem Warten. */
    private class GatedLog(private val gate: CompletableDeferred<Unit>) : AlarmLog {
        val entries = mutableListOf<String>()
        private var first = true
        override suspend fun log(level: LogLevel, message: String) {
            if (first) {
                first = false
                gate.await()
            }
            entries += message
        }
    }

    private fun fixtureWithGatedLog(): Triple<Fixture, GatedLog, CompletableDeferred<Unit>> {
        val gate = CompletableDeferred<Unit>()
        val log = GatedLog(gate)
        return Triple(Fixture(log), log, gate)
    }

    @Test
    fun `a call that arrives while the log of another one hangs runs after it`() = runTest {
        val (f, log, gate) = fixtureWithGatedLog()
        val first = launch { f.cycles(0L..5L, coolant = 101f) }      // the alarm shows at 5 s, the log line "raised" hangs
        runCurrent()
        assertEquals(listOf<Call>(Call.Show(Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, 101f))), f.notifier.calls)

        val second = launch { f.controller.onSessionEnded() }        // arrives while the first call is still in its log write
        runCurrent()
        assertEquals("the second call has not started", 1, f.notifier.calls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join(); second.join()

        assertEquals(
            listOf("Alarm raised: COOLANT WARNING 101", "Alarm cleared: COOLANT"),
            log.entries,
        )
        assertEquals(listOf(AlarmKind.COOLANT), f.notifier.cleared())
    }

    @Test
    fun `the same cycle twice while the log hangs shows once`() = runTest {
        val (f, log, gate) = fixtureWithGatedLog()
        val first = launch { f.cycles(0L..5L, coolant = 101f) }
        runCurrent()
        val second = launch { f.cycle(5L, coolant = 101f) }           // the same sample arrives again during the log write
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()
        first.join(); second.join()

        assertEquals(1, f.notifier.shown().size)
        assertEquals(listOf("Alarm raised: COOLANT WARNING 101"), log.entries)
    }

    @Test
    fun `a call cancelled in its log write leaves the state consistent and the lock free`() = runTest {
        val (f, log, gate) = fixtureWithGatedLog()
        val first = launch { f.cycles(0L..5L, coolant = 101f) }
        runCurrent()
        first.cancel()                                                // e.g. the view model is cleared while the line is written
        runCurrent()

        f.cycle(6L, coolant = 101f)                                   // does not hang, and does not alert a second time
        assertEquals(1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
        assertTrue("the cancelled line is lost, nothing is written twice", log.entries.isEmpty())
        gate.complete(Unit)
    }

    // --- Beiwerk darf die Live-Anzeige nie beenden: Fehler der Benachrichtigung ---

    @Test
    fun `a notifier that throws on show does not stop the call - the alarms are updated and the next kind is still shown`() = runTest {
        val f = Fixture()
        f.notifier.showFailure = { alarm -> if (alarm.kind == AlarmKind.COOLANT) IllegalStateException("boom") else null }

        f.bothAlarmsInOneCycle()                                      // returns normally

        assertEquals("both were attempted", listOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE), f.notifier.shown().map { it.kind })
        assertEquals(setOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE), f.controller.alarms.value.map { it.kind }.toSet())
        assertEquals(
            listOf(
                LogLevel.WARN to "Alarm raised: COOLANT WARNING 101",
                LogLevel.WARN to "Alarm raised: VOLTAGE WARNING 12.0",
                LogLevel.WARN to "Alarm notification failed: IllegalStateException: boom",
            ),
            f.rec().entries,
        )
    }

    @Test
    fun `a failed notification is lost but not retried - the banner stays and the next cycle does not alert again`() = runTest {
        val f = Fixture()
        f.notifier.showFailure = { IllegalStateException("boom") }
        f.cycles(0L..6L, coolant = 101f)
        f.cycles(7L..12L, coolant = 104f)
        assertEquals("no rollback of what was reported, no retry", 1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    @Test
    fun `repeated notifier failures log one warning per session - a later session logs again`() = runTest {
        val f = Fixture()
        f.notifier.showFailure = { IllegalStateException("boom") }
        f.notifier.clearFailure = { IllegalStateException("boom") }
        f.cycles(0L..6L, coolant = 101f)                              // show throws: the line is written
        f.controller.acknowledge(AlarmKind.COOLANT)                   // clear throws
        f.cycles(7L..30L, coolant = 85f)                              // the alarm ends, clear throws
        f.cycles(31L..40L, coolant = 101f)                            // raised again, show throws
        assertEquals(listOf("Alarm notification failed: IllegalStateException: boom"), f.warnings("Alarm notification failed"))

        f.controller.onSessionEnded()                                 // the clear of the session end throws as well: still the same session
        assertEquals(1, f.warnings("Alarm notification failed").size)

        f.cycles(0L..6L, coolant = 101f)                              // a new session
        assertEquals(2, f.warnings("Alarm notification failed").size)
    }

    @Test
    fun `a notifier that throws on clear is contained too - the cleared line is still written`() = runTest {
        val f = Fixture()
        f.notifier.clearFailure = { IllegalStateException("gone") }
        f.cycles(0L..6L, coolant = 101f)
        f.cycles(7L..30L, coolant = 85f)                              // the alarm ends, clear throws

        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals(listOf(AlarmKind.COOLANT), f.notifier.cleared())
        assertEquals(LogLevel.INFO to "Alarm cleared: COOLANT", f.rec().entries.first { it.first == LogLevel.INFO })
        assertEquals(listOf("Alarm notification failed: IllegalStateException: gone"), f.warnings("Alarm notification failed"))
    }

    @Test
    fun `the clear of an acknowledgement is contained - the banner is hidden anyway`() = runTest {
        val f = Fixture()
        f.notifier.clearFailure = { IllegalStateException("gone") }
        f.cycles(0L..25L step 5, volt = 12.0f)

        f.controller.acknowledge(AlarmKind.VOLTAGE)                   // returns normally

        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals(1, f.warnings("Alarm notification failed").size)
    }

    @Test
    fun `a failing clear does not skip the show of the same publish`() = runTest {
        val f = Fixture()
        f.notifier.clearFailure = { IllegalStateException("gone") }
        f.cycles(0L..14L, coolant = 101f)                             // coolant warning at 5 s
        f.cycles(15L..19L, volt = 12.0f, coolant = 101f)              // the voltage breach begins at 15 s
        f.cycles(20L..25L, volt = 12.0f, coolant = 85f)               // the coolant recovers from 20 s (5 s to end), the voltage warning comes at 25 s: the same cycle

        assertEquals(
            listOf("show COOLANT", "clear COOLANT", "show VOLTAGE"),
            f.notifier.calls.map { call ->
                when (call) {
                    is Call.Show -> "show ${call.alarm.kind}"
                    is Call.Clear -> "clear ${call.kind}"
                }
            },
        )
        assertEquals(listOf(AlarmKind.VOLTAGE), f.controller.alarms.value.map { it.kind })
    }

    @Test
    fun `a failing clear does not skip the other clears of the same publish`() = runTest {
        val f = Fixture()
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 101f)
        f.notifier.calls.clear()
        f.notifier.clearFailure = { kind -> if (kind == AlarmKind.COOLANT) IllegalStateException("gone") else null }

        f.controller.onSessionEnded()                                 // COOLANT and VOLTAGE end in this one publish

        assertEquals(setOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE), f.notifier.cleared().toSet())
        assertEquals(2, f.notifier.cleared().size)
        assertEquals(1, f.warnings("Alarm notification failed").size)
    }

    @Test
    fun `a CancellationException from show is not swallowed and the lock is released`() = runTest {
        val f = Fixture()
        f.notifier.showFailure = { CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.cycles(0L..6L, coolant = 101f) }
        assertTrue("a cancellation is no failure of the notifier", f.warnings("Alarm notification failed").isEmpty())

        f.notifier.showFailure = { null }
        f.cycle(7L, coolant = 101f)                                   // does not hang
    }

    @Test
    fun `a CancellationException from clear is not swallowed`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.notifier.clearFailure = { CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.controller.onSessionEnded() }
        assertTrue(f.warnings("Alarm notification failed").isEmpty())
    }

    // --- Beiwerk darf die Live-Anzeige nie beenden: Fehler der Auswertung (Tracker) ---

    @Test
    fun `a tracker that throws is contained - the call returns and one warning is logged per session`() = runTest {
        val tracker = spyk(AlarmTracker())
        every { tracker.onCycle(any(), any()) } throws IllegalStateException("boom")
        val f = Fixture(tracker = tracker)

        f.cycles(0L..6L, coolant = 101f)                              // seven failing calls: each returns normally

        assertTrue(f.notifier.calls.isEmpty())
        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals(listOf("Alarm evaluation failed: IllegalStateException: boom"), f.warnings("Alarm evaluation failed"))

        f.controller.onSessionEnded()                                 // ends the session: the flag is reset
        f.cycles(0L..2L, coolant = 101f)
        assertEquals(2, f.warnings("Alarm evaluation failed").size)
    }

    @Test
    fun `every entry point contains a tracker failure`() = runTest {
        val tracker = spyk(AlarmTracker())
        every { tracker.updateSettings(any()) } throws IllegalStateException("settings")
        every { tracker.onFaultRead(any(), any()) } throws IllegalStateException("faults")
        every { tracker.onSessionEnded() } throws IllegalStateException("end")
        every { tracker.acknowledge(any()) } throws IllegalStateException("ack")
        val f = Fixture(tracker = tracker)

        f.controller.updateSettings(AlarmSettings())
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.controller.acknowledge(AlarmKind.COOLANT)
        f.controller.onSessionEnded()

        assertEquals(1, f.warnings("Alarm evaluation failed").size)
        assertTrue(f.notifier.calls.isEmpty())
    }

    @Test
    fun `after a tracker failure publish still runs on whatever state exists`() = runTest {
        val tracker = spyk(AlarmTracker())
        // the failure comes after the state change: the alarm exists, the call throws anyway
        every { tracker.onCycle(any(), any()) } answers { callOriginal(); throw IllegalStateException("late") }
        val f = Fixture(tracker = tracker)

        f.cycles(0L..6L, coolant = 101f)

        assertEquals(1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
        assertEquals(1, f.warnings("Alarm evaluation failed").size)
    }

    @Test
    fun `a tracker that cannot report its alarms skips the publish - nothing is corrupted and the next call catches up`() = runTest {
        val tracker = spyk(AlarmTracker())
        val f = Fixture(tracker = tracker)
        f.cycles(0L..4L, coolant = 101f)
        every { tracker.active() } throws IllegalStateException("broken")
        f.cycle(5L, coolant = 101f)                                   // the alarm exists in the tracker but cannot be read

        assertTrue(f.notifier.calls.isEmpty())
        assertTrue(f.controller.alarms.value.isEmpty())
        assertEquals(1, f.warnings("Alarm evaluation failed").size)

        every { tracker.active() } answers { callOriginal() }
        f.cycle(6L, coolant = 101f)
        assertEquals("caught up, shown once", 1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    @Test
    fun `a tracker that cannot report does not end what was shown - the banner stays and nothing alerts twice`() = runTest {
        val tracker = spyk(AlarmTracker())
        val f = Fixture(tracker = tracker)
        f.cycles(0L..6L, coolant = 101f)                              // the alarm is shown
        every { tracker.active() } throws IllegalStateException("broken")
        f.cycle(7L, coolant = 101f)

        assertTrue("an unreadable tracker is no 'ended'", f.notifier.cleared().isEmpty())
        assertEquals(1, f.controller.alarms.value.size)
        assertEquals(1, f.warnings("Alarm evaluation failed").size)

        every { tracker.active() } answers { callOriginal() }
        f.cycle(8L, coolant = 101f)
        assertEquals("the alarm was never forgotten, so it is not raised again", 1, f.notifier.shown().size)
        assertEquals(1, f.controller.alarms.value.size)
    }

    @Test
    fun `a CancellationException from the tracker is not swallowed`() = runTest {
        val tracker = spyk(AlarmTracker())
        every { tracker.onCycle(any(), any()) } throws CancellationException("cancelled")
        val f = Fixture(tracker = tracker)
        assertFailsWith<CancellationException> { f.cycle(0L, coolant = 101f) }
        assertTrue(f.warnings("Alarm evaluation failed").isEmpty())
    }

    @Test
    fun `evaluation and notification failures have one warning each`() = runTest {
        val tracker = spyk(AlarmTracker())
        every { tracker.onCycle(any(), any()) } answers { callOriginal(); throw IllegalStateException("eval") }
        val f = Fixture(tracker = tracker)
        f.notifier.showFailure = { IllegalStateException("notify") }

        f.cycles(0L..12L, coolant = 101f)

        assertEquals(listOf("Alarm evaluation failed: IllegalStateException: eval"), f.warnings("Alarm evaluation failed"))
        assertEquals(listOf("Alarm notification failed: IllegalStateException: notify"), f.warnings("Alarm notification failed"))
    }

    // --- release: das ViewModel endet ---

    @Test
    fun `release clears the numeric notifications - not the fault one`() = runTest {
        val f = Fixture()
        f.controller.onFaultCodes(emptyList(), isBaseline = true)
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 101f)
        f.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        assertEquals(3, f.notifier.shown().size)

        f.controller.release()

        assertEquals(setOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE), f.notifier.cleared().toSet())
        assertEquals(2, f.notifier.cleared().size)
        assertEquals(1, f.controller.alarms.value.count { it.kind == AlarmKind.FAULT_CODE })
    }

    @Test
    fun `release does nothing for kinds that are not shown`() = runTest {
        val f = Fixture()
        f.controller.release()
        assertTrue(f.notifier.calls.isEmpty())

        f.cycles(0L..6L, coolant = 101f)
        f.notifier.calls.clear()
        f.controller.release()
        assertEquals(listOf(AlarmKind.COOLANT), f.notifier.cleared())
    }

    @Test
    fun `release is contained when the notifier throws - every numeric kind is still attempted`() = runTest {
        val f = Fixture()
        f.cycles(0L..25L step 5, volt = 12.0f, coolant = 101f)
        f.notifier.calls.clear()
        f.notifier.clearFailure = { IllegalStateException("gone") }

        f.controller.release()                                        // returns normally

        assertEquals(setOf(AlarmKind.COOLANT, AlarmKind.VOLTAGE), f.notifier.cleared().toSet())
    }

    @Test
    fun `release does not swallow a CancellationException`() = runTest {
        val f = Fixture()
        f.cycles(0L..6L, coolant = 101f)
        f.notifier.clearFailure = { CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.controller.release() }
    }
}
