package de.adv990de.obd2.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThresholdAlarmTest {

    private fun ThresholdAlarm.feed(vararg samples: Pair<Long, Float>) = samples.forEach { (s, v) -> sample(s * 1000, v) }
    private fun ThresholdAlarm.every5s(value: Float, fromS: Long, toS: Long) { var t = fromS; while (t <= toS) { sample(t * 1000, value); t += 5 } }

    // ── Spannung ──────────────────────────────────────────────────────────────

    @Test
    fun `12_1 volts for ten seconds is a warning`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.feed(0L to 12.1f, 5L to 12.1f)
        assertNull(alarm.severity())
        alarm.feed(10L to 12.1f)
        assertEquals(AlarmSeverity.WARNING, alarm.severity())
    }

    @Test
    fun `12_4 volts for twenty seconds is only a notice`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.every5s(12.4f, 0, 15)
        assertNull(alarm.severity())
        alarm.feed(20L to 12.4f)
        assertEquals(AlarmSeverity.NOTICE, alarm.severity())
    }

    @Test
    fun `12_6 volts is a notice only after three minutes (discharge rule)`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.every5s(12.6f, 0, 175)
        assertNull(alarm.severity())
        alarm.feed(180L to 12.6f)
        assertEquals(AlarmSeverity.NOTICE, alarm.severity())
    }

    @Test
    fun `13_5 volts never alarms`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.every5s(13.5f, 0, 600)
        assertNull(alarm.severity())
    }

    @Test
    fun `the warning wins over the notice`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.every5s(12.0f, 0, 25)
        assertEquals(AlarmSeverity.WARNING, alarm.severity())
    }

    @Test
    fun `chosen thresholds apply`() {
        val alarm = voltageAlarm(AlarmSettings(voltageWarningDeciV = 118))   // warning only below 11.8 V
        alarm.every5s(12.0f, 0, 15)
        assertNull(alarm.severity())                                          // 12.0 V is no warning now, and no notice before 20 s
        alarm.feed(20L to 12.0f)
        assertEquals(AlarmSeverity.NOTICE, alarm.severity())
    }

    @Test
    fun `the last value is kept for the display and reset forgets it`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.feed(0L to 12.3f)
        assertEquals(12.3f, alarm.lastValue)
        alarm.reset()
        assertNull(alarm.lastValue)
    }

    @Test
    fun `a sample that is not a finite number leaves the last value and the rules untouched`() {
        val alarm = voltageAlarm(AlarmSettings())
        alarm.sample(0L, Float.NaN)
        assertNull("a first NaN is no value", alarm.lastValue)

        alarm.feed(1L to 12.1f)
        alarm.sample(5_000L, Float.NaN)
        alarm.sample(6_000L, Float.POSITIVE_INFINITY)

        assertEquals(12.1f, alarm.lastValue)
    }

    // ── Kuehlmittel ───────────────────────────────────────────────────────────

    @Test
    fun `96 degrees for ten seconds is a coolant notice`() {
        val alarm = coolantAlarm(AlarmSettings())
        alarm.feed(0L to 96f, 5L to 96f)
        assertNull(alarm.severity())
        alarm.feed(10L to 96f)
        assertEquals(AlarmSeverity.NOTICE, alarm.severity())
    }

    @Test
    fun `101 degrees for five seconds is a coolant warning`() {
        val alarm = coolantAlarm(AlarmSettings())
        alarm.feed(0L to 101f, 4L to 101f)
        assertNull(alarm.severity())
        alarm.feed(5L to 101f)
        assertEquals(AlarmSeverity.WARNING, alarm.severity())
    }

    @Test
    fun `90 degrees, the normal control point, never alarms`() {
        val alarm = coolantAlarm(AlarmSettings())
        alarm.every5s(90f, 0, 600)
        assertNull(alarm.severity())
    }

    @Test
    fun `chosen coolant thresholds apply`() {
        val alarm = coolantAlarm(AlarmSettings(coolantNoticeC = 98, coolantWarningC = 110))
        alarm.every5s(96f, 0, 60)
        assertNull(alarm.severity())
        alarm.every5s(105f, 65, 80)
        assertEquals(AlarmSeverity.NOTICE, alarm.severity())
    }
}
