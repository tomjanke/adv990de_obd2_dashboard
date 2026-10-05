package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann ein Messwert einen Punkt in die Sparkline bekommt (B30). */
class SparklineSamplingTest {

    @Test
    fun `the first sample of a PID is always taken`() {
        assertTrue(shouldAppendSparklineSample(lastTimestampMs = null, timestampMs = 1_000L))
    }

    @Test
    fun `a replay with the very same timestamp is not a new sample`() {
        // ReadingsPipeline.gpsRefresh stellt bei jedem GPS-Fix (bis 5 Hz) die letzten OBD-Werte erneut zu.
        assertFalse(shouldAppendSparklineSample(lastTimestampMs = 1_000L, timestampMs = 1_000L))
    }

    @Test
    fun `a newer sample closer than the minimum interval is skipped`() {
        assertFalse(shouldAppendSparklineSample(1_000L, 1_000L + SPARKLINE_MIN_INTERVAL_MS - 1))
    }

    @Test
    fun `a newer sample at or after the minimum interval is taken`() {
        assertTrue(shouldAppendSparklineSample(1_000L, 1_000L + SPARKLINE_MIN_INTERVAL_MS))
        assertTrue(shouldAppendSparklineSample(1_000L, 9_000L))
    }

    @Test
    fun `a timestamp from before the last one - the wall clock was set back - is taken instead of freezing the graph`() {
        assertTrue(shouldAppendSparklineSample(lastTimestampMs = 50_000L, timestampMs = 10_000L))
    }
}
