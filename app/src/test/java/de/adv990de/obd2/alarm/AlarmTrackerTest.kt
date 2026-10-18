package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTrackerTest {

    /** Ein OBD-Zyklus zum Zeitpunkt [s] Sekunden; null = der Wert fehlt in diesem Zyklus. */
    private fun AlarmTracker.cycle(s: Long, rpm: Float? = 2_000f, volt: Float? = null, coolant: Float? = null) {
        val t = s * 1000
        val values = buildList {
            if (rpm != null) add(SensorValue(PidRegistry.RPM, rpm, timestamp = t))
            if (volt != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volt, timestamp = t))
            if (coolant != null) add(SensorValue(PidRegistry.COOLANT_TEMP, coolant, timestamp = t))
        }
        onCycle(t, values)
    }

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
    fun `a missing rpm is held for five seconds`() {
        val tracker = AlarmTracker()
        tracker.cycle(0, coolant = 101f)
        tracker.cycle(3, rpm = null, coolant = 101f)
        tracker.cycle(5, rpm = null, coolant = 101f)
        assertEquals(listOf(AlarmKind.COOLANT to AlarmSeverity.WARNING), tracker.kinds())
        tracker.cycle(6, rpm = null, coolant = 101f)                // 6 s after the last rpm: engine counts as off
        assertTrue(tracker.active().isEmpty())
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
        tracker.updateSettings(AlarmSettings(coolantWarningC = 105, coolantNoticeC = 98))
        assertTrue(tracker.active().isEmpty())                       // restarted, and 101 < 105 anyway
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
}
