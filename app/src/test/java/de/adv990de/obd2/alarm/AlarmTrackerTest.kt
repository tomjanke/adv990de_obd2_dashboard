package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTrackerTest {

    /** Ein OBD-Zyklus zum Zeitpunkt [ms] Millisekunden; null = der Wert fehlt in diesem Zyklus. */
    private fun AlarmTracker.cycleMs(ms: Long, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) {
        val values = buildList {
            if (rpm != null) add(SensorValue(PidRegistry.RPM, rpm, timestamp = ms))
            if (volt != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volt, timestamp = ms))
            if (coolant != null) add(SensorValue(PidRegistry.COOLANT_TEMP, coolant, timestamp = ms))
        }
        onCycle(ms, values)
    }

    /** Ein OBD-Zyklus zum Zeitpunkt [s] Sekunden; null = der Wert fehlt in diesem Zyklus. */
    private fun AlarmTracker.cycle(s: Long, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) =
        cycleMs(s * 1000, rpm, volt, coolant)

    private fun AlarmTracker.kinds() = active().map { it.kind to it.severity }

    @Test
    fun `without a running engine the voltage never alarms`() {
        val tracker = AlarmTracker()
        for (s in 0L..120L step 5) tracker.cycle(s, rpm = 0f, volt = 11.9f)
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `the first fifteen seconds after the engine start are not evaluated`() {
        val tracker = AlarmTracker()
        for (s in 0L..20L step 5) tracker.cycle(s, volt = 12.0f)
        assertTrue(tracker.active().isEmpty())                       // the run only starts at 15 s
        tracker.cycle(25, volt = 12.0f)                              // 10 s after the start of the run: warning
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
    }

    @Test
    fun `the voltage alarm carries the last voltage`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)
        assertEquals(12.0f, tracker.active().single().value)
    }

    @Test
    fun `a cycle without a voltage value does not count as a sample`() {
        val tracker = AlarmTracker()
        for (s in 0L..15L step 5) tracker.cycle(s, volt = 12.0f)    // breach starts at 15
        tracker.cycle(18)                                           // no voltage in this cycle
        tracker.cycle(25, volt = 12.0f)
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
    }

    @Test
    fun `a hot coolant with the engine running is an alarm after five seconds`() {
        val tracker = AlarmTracker()
        tracker.cycle(0, coolant = 101f)
        tracker.cycle(4, coolant = 101f)
        assertTrue(tracker.active().isEmpty())
        tracker.cycle(5, coolant = 101f)
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        assertEquals(101f, tracker.active().single().value)
    }

    @Test
    fun `a hot coolant with the engine off is no alarm (heat soak)`() {
        val tracker = AlarmTracker()
        for (s in 0L..60L step 5) tracker.cycle(s, rpm = 0f, coolant = 105f)
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `a missing rpm is held for RPM_HOLD_MS`() {
        val tracker = AlarmTracker()
        tracker.cycle(0, coolant = 101f)
        tracker.cycle(5, coolant = 101f)                            // the warning starts at 5 s (last rpm: 5 s)
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        tracker.cycleMs(5_000 + EngineGate.RPM_HOLD_MS, rpm = null, coolant = 101f)       // exactly the hold time after the last rpm: still running
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        tracker.cycleMs(5_000 + EngineGate.RPM_HOLD_MS + 1, rpm = null, coolant = 101f)   // just after: the engine counts as off
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `a single rpm timeout does not end an acknowledged coolant alarm or show it again`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)           // warning at 5 s, last rpm at 6 s
        tracker.acknowledge(AlarmKind.COOLANT)
        assertTrue(tracker.visible().isEmpty())
        // Nur die Drehzahl lief ins Zeitlimit: der Zyklus kommt 6,5 s nach dem letzten mit Drehzahl, ohne Drehzahl, mit Kühlmittel.
        tracker.cycleMs(12_500, rpm = null, coolant = 101f)
        assertEquals("the alarm did not end", listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        assertTrue("and it stays acknowledged", tracker.visible().isEmpty())
        tracker.cycleMs(13_500, coolant = 101f)                      // the rpm is back
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        assertTrue("no second showing after the engine counted as running all along", tracker.visible().isEmpty())
    }

    @Test
    fun `a single rpm timeout does not restart the settle time of an active voltage alarm`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)     // warning at 25 s, last rpm at 25 s
        tracker.acknowledge(AlarmKind.VOLTAGE)
        assertTrue(tracker.visible().isEmpty())
        tracker.cycleMs(31_500, rpm = null, volt = 12.0f)            // 6.5 s after the last rpm: one rpm timeout
        assertEquals("the alarm did not end", listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
        assertTrue("and it stays acknowledged", tracker.visible().isEmpty())
        tracker.cycleMs(32_500, volt = 12.0f)                        // the rpm is back: no new 15 s of settle time
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
        assertTrue(tracker.visible().isEmpty())
    }

    @Test
    fun `stopping the engine ends a coolant alarm`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)
        assertEquals(1, tracker.active().size)
        tracker.cycle(7, rpm = 0f, coolant = 101f)
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `switching an alarm off ends it and ignores its values`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)
        tracker.updateSettings(AlarmSettings(coolantEnabled = false))
        assertTrue(tracker.active().isEmpty())
        for (s in 7L..20L) tracker.cycle(s, coolant = 101f)
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `changing a threshold restarts the rule`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        tracker.updateSettings(AlarmSettings(coolantNoticeC = 92))  // the warning stays at 100 °C
        assertTrue(tracker.active().isEmpty())                       // restarted: the old warning is gone
        for (s in 7L..11L) tracker.cycle(s, coolant = 101f)
        assertTrue(tracker.active().isEmpty())                       // 4 s since the restart: no warning yet
        tracker.cycle(12, coolant = 101f)                            // 5 s since the restart
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
    }

    @Test
    fun `the session end clears the numeric alarms`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)
        tracker.onSessionEnded()
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `voltage and coolant can alarm at the same time`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f, coolant = 101f)
        assertEquals(setOf(AlarmKind.VOLTAGE, AlarmKind.COOLANT), tracker.active().map { it.kind }.toSet())
    }

    @Test
    fun `a voltage alarm ends when its value stops arriving for more than thirty seconds`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
        for (s in 30L..55L step 5) tracker.cycle(s)                  // rpm keeps arriving, the voltage does not
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())   // exactly 30 s: still active
        tracker.cycle(60)                                            // 35 s since the last voltage
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `a coolant alarm ends when its value stops arriving for more than thirty seconds`() {
        val tracker = AlarmTracker()
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)           // warning at 5 s, last value at 6 s
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        for (s in 7L..36L) tracker.cycle(s)                          // rpm keeps arriving, the coolant does not
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())   // exactly 30 s: still active
        tracker.cycle(37)                                            // 31 s since the last coolant value
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `a missing value for less than thirty seconds keeps an active alarm`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f, coolant = 101f)
        assertEquals(setOf(AlarmKind.VOLTAGE, AlarmKind.COOLANT), tracker.active().map { it.kind }.toSet())
        for (s in 30L..55L step 5) tracker.cycle(s)                  // up to exactly 30 s without a value
        assertEquals(setOf(AlarmKind.VOLTAGE, AlarmKind.COOLANT), tracker.active().map { it.kind }.toSet())
    }

    private fun AlarmTracker.faultReads(vararg reads: List<DtcEntry>) = reads.forEach { onFaultRead(it, isBaseline = false) }
    private fun conf(code: String) = DtcEntry(code, isPending = false)
    private fun pend(code: String) = DtcEntry(code, isPending = true)

    @Test
    fun `a confirmed new code is a red fault alarm after two reads`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")))
        assertTrue(tracker.active().isEmpty())
        tracker.faultReads(listOf(conf("P0301")))
        assertEquals(Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301")), tracker.active().single())
    }

    @Test
    fun `only pending codes make a yellow notice`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(pend("P0171")), listOf(pend("P0171")))
        assertEquals(AlarmSeverity.NOTICE, tracker.active().single().severity)
    }

    @Test
    fun `more codes join the alarm and a confirmed code raises it to a warning`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(pend("P0171")), listOf(pend("P0171")))
        tracker.faultReads(listOf(pend("P0171"), conf("P0301")), listOf(pend("P0171"), conf("P0301")))
        val alarm = tracker.active().single()
        assertEquals(AlarmSeverity.WARNING, alarm.severity)
        assertEquals(listOf("P0171", "P0301"), alarm.codes)
    }

    @Test
    fun `acknowledging a fault alarm ends it and the next code starts a fresh one`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        tracker.acknowledge(AlarmKind.FAULT_CODE)
        assertTrue(tracker.active().isEmpty())
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")), listOf(conf("P0301"), conf("P0420")))
        assertEquals(listOf("P0420"), tracker.active().single().codes)
    }

    @Test
    fun `the fault alarm survives the end of the session`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        tracker.onSessionEnded()
        assertEquals(1, tracker.active().size)
    }

    @Test
    fun `the candidate memory of the fault watcher survives the end of the session`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(listOf(conf("P0301")), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")))         // P0420 is new: a candidate, not yet an alarm
        assertTrue(tracker.active().isEmpty())
        tracker.onSessionEnded()                                          // e.g. a stale phase: the session itself goes on
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")))         // the second consecutive read confirms it
        assertEquals(listOf("P0420"), tracker.active().single().codes)
    }

    @Test
    fun `a stale phase does not drop the baseline - the code that appears afterwards alarms`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(listOf(conf("P0301")), isBaseline = true)    // the read when connecting
        tracker.onSessionEnded()                                          // Stale ends no session: no new baseline read follows
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")))         // a stall or restart set P0420
        assertTrue(tracker.active().isEmpty())                            // not yet: two reads in a row are needed ...
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")))
        assertEquals("... but it must not have been absorbed as the baseline", listOf("P0420"), tracker.active().single().codes)
    }

    @Test
    fun `a new session starts with a fresh baseline`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(listOf(conf("P0301")), isBaseline = true)
        tracker.onSessionEnded()
        tracker.onFaultRead(listOf(conf("P0301"), conf("P0420")), isBaseline = true)    // the code is part of the new baseline
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")), listOf(conf("P0301"), conf("P0420")))
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `switching the fault alarm off drops it and its baseline`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        tracker.updateSettings(AlarmSettings(faultsEnabled = false))
        assertTrue(tracker.active().isEmpty())
        tracker.faultReads(listOf(conf("P0420")), listOf(conf("P0420")))                // ignored while off
        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun `acknowledging a numeric alarm hides it while the same alert lasts`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)
        assertEquals(1, tracker.visible().size)
        tracker.acknowledge(AlarmKind.VOLTAGE)
        assertTrue(tracker.visible().isEmpty())
        assertEquals(1, tracker.active().size)                                           // still true, only not shown
        tracker.cycle(30, volt = 11.9f)
        assertTrue(tracker.visible().isEmpty())                                          // the value changes, the alert does not
    }

    @Test
    fun `an escalation or a returning alarm is shown again after an acknowledgement`() {
        val tracker = AlarmTracker()
        for (s in 0L..20L) tracker.cycle(s, coolant = 96f)                              // notice after 10 s
        assertEquals(AlarmSeverity.NOTICE, tracker.visible().single().severity)
        tracker.acknowledge(AlarmKind.COOLANT)
        assertTrue(tracker.visible().isEmpty())
        for (s in 21L..30L) tracker.cycle(s, coolant = 101f)                            // warning after 5 s above 100
        assertEquals(AlarmSeverity.WARNING, tracker.visible().single().severity)
        tracker.acknowledge(AlarmKind.COOLANT)
        for (s in 31L..50L) tracker.cycle(s, coolant = 85f)                             // recovers, alarm ends
        assertTrue(tracker.active().isEmpty())
        for (s in 51L..60L) tracker.cycle(s, coolant = 101f)                            // returns
        assertEquals(1, tracker.visible().size)
    }

    @Test
    fun `visible alarms are sorted by severity and then by kind`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f, coolant = 96f)         // voltage warning, coolant notice
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))                // fault warning
        assertEquals(
            listOf(AlarmKind.VOLTAGE, AlarmKind.FAULT_CODE, AlarmKind.COOLANT),
            tracker.visible().map { it.kind },
        )
    }

    @Test
    fun `an acknowledged warning that drops to a notice stays hidden and an escalation shows it again`() {
        val tracker = AlarmTracker()
        for (s in 0L..35L step 5) tracker.cycle(s, volt = 12.0f)            // warning at 25 s, notice at 35 s
        assertEquals(AlarmSeverity.WARNING, tracker.visible().single().severity)
        tracker.acknowledge(AlarmKind.VOLTAGE)
        for (s in 40L..45L step 5) tracker.cycle(s, volt = 12.6f)           // 12.6 V: the warning clears after 5 s, the notice holds
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.NOTICE), tracker.kinds())
        assertTrue(tracker.visible().isEmpty())                              // a lower level of the same alarm stays hidden
        for (s in 50L..60L step 5) tracker.cycle(s, volt = 12.0f)           // below 12.2 V for 10 s: the warning is back
        assertEquals(AlarmSeverity.WARNING, tracker.visible().single().severity)
    }

    @Test
    fun `a session end leaves no acknowledgement behind for the next session`() {
        val tracker = AlarmTracker()
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)
        tracker.acknowledge(AlarmKind.VOLTAGE)
        tracker.onSessionEnded()
        assertTrue(tracker.visible().isEmpty())
        for (s in 0L..25L step 5) tracker.cycle(s, volt = 12.0f)
        assertEquals(listOf(AlarmKind.VOLTAGE to AlarmSeverity.WARNING), tracker.kinds())
        assertEquals(1, tracker.visible().size)                              // the new alarm is not hidden by the old acknowledgement
    }

    @Test
    fun `a failed fault read changes nothing at the tracker level`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")))
        tracker.onFaultRead(null, isBaseline = false)
        assertTrue(tracker.active().isEmpty())
        tracker.faultReads(listOf(conf("P0301")))                            // the second consecutive success
        assertEquals(listOf("P0301"), tracker.active().single().codes)
    }

    @Test
    fun `the first fault read after switching the faults back on is a silent baseline`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        tracker.updateSettings(AlarmSettings(faultsEnabled = false))
        tracker.updateSettings(AlarmSettings(faultsEnabled = true))
        tracker.faultReads(listOf(conf("P0301"), conf("P0420")), listOf(conf("P0301"), conf("P0420")))
        assertTrue(tracker.active().isEmpty())                               // both reads are the baseline, nothing is new
    }

    @Test
    fun `a pending code joining a warning fault alarm keeps it a warning`() {
        val tracker = AlarmTracker()
        tracker.onFaultRead(emptyList(), isBaseline = true)
        tracker.faultReads(listOf(conf("P0301")), listOf(conf("P0301")))
        tracker.faultReads(listOf(conf("P0301"), pend("P0171")), listOf(conf("P0301"), pend("P0171")))
        val alarm = tracker.active().single()
        assertEquals(AlarmSeverity.WARNING, alarm.severity)
        assertEquals(listOf("P0301", "P0171"), alarm.codes)
    }

    @Test
    fun `acknowledging a kind without an alarm changes nothing`() {
        val tracker = AlarmTracker()
        tracker.acknowledge(AlarmKind.COOLANT)
        for (s in 0L..6L) tracker.cycle(s, coolant = 101f)
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        assertEquals(1, tracker.visible().size)                              // the later alarm is shown, not silenced
    }
}
