package de.adv990de.obd2.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedRuleTest {

    private fun below(threshold: Float = 12.5f, durationS: Long = 20, margin: Float = 0.2f) =
        SustainedRule(Breach.BELOW, threshold, durationS * 1000, margin)

    private fun SustainedRule.feed(vararg samples: Pair<Long, Float>) = samples.forEach { (s, v) -> sample(s * 1000, v) }

    @Test
    fun `a run below the threshold becomes active after the duration`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f)
        assertFalse(rule.isActive)
        rule.feed(20L to 12.0f)
        assertTrue(rule.isActive)
    }

    @Test
    fun `a value exactly at the threshold is no breach`() {
        val rule = below()
        rule.feed(0L to 12.5f, 10L to 12.5f, 20L to 12.5f, 30L to 12.5f, 40L to 12.5f)
        assertFalse(rule.isActive)
    }

    @Test
    fun `one outlier inside a run does not restart it`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 13.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
    }

    @Test
    fun `a recovery of five seconds ends the run and the count starts over`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 13.0f, 10L to 13.0f, 15L to 12.0f, 30L to 12.0f)
        assertFalse(rule.isActive)               // 15 s since the new start, the old run is gone
        rule.feed(35L to 12.0f)
        assertTrue(rule.isActive)                // 20 s since 15
    }

    @Test
    fun `an active rule stays active inside the clear margin and clears beyond it`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
        rule.feed(25L to 12.6f, 30L to 12.6f, 35L to 12.6f)    // above 12.5 but below 12.5 + 0.2
        assertTrue(rule.isActive)
        rule.feed(40L to 12.8f)                                 // beyond the margin, but the recovery has only just started
        assertTrue(rule.isActive)
        rule.feed(45L to 12.8f)                                 // beyond the margin for five seconds
        assertFalse(rule.isActive)
    }

    @Test
    fun `a violating sample while recovering cancels the recovery`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f, 20L to 12.0f)
        rule.feed(25L to 12.8f, 28L to 12.0f, 33L to 12.8f)    // recovery restarts at 33
        assertTrue(rule.isActive)
    }

    @Test
    fun `a gap of more than thirty seconds restarts the rule`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
        rule.feed(60L to 12.0f)
        assertFalse(rule.isActive)
    }

    @Test
    fun `above rules breach at the threshold and clear below the margin`() {
        val rule = SustainedRule(Breach.ABOVE, 95f, 10_000, 3f)
        rule.feed(0L to 95f, 5L to 96f, 10L to 96f)
        assertTrue(rule.isActive)
        rule.feed(15L to 93f, 20L to 93f, 25L to 93f)           // below 95, not yet below 92
        assertTrue(rule.isActive)
        rule.feed(30L to 91f)                                   // beyond the margin, the recovery starts
        assertTrue(rule.isActive)
        rule.feed(35L to 91f)                                   // beyond the margin for five seconds
        assertFalse(rule.isActive)
    }

    @Test
    fun `reset forgets everything`() {
        val rule = below()
        rule.feed(0L to 12.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
        rule.reset()
        assertFalse(rule.isActive)
        rule.feed(21L to 12.0f)
        assertFalse(rule.isActive)
    }

    @Test
    fun `a lone beyond-margin sample between in-band samples does not clear an active rule`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
        rule.feed(25L to 12.6f, 30L to 12.6f, 35L to 13.0f, 40L to 12.0f)
        assertTrue(rule.isActive)
    }

    @Test
    fun `a sample inside the band restarts the clear count`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to 12.0f, 20L to 12.0f)
        assertTrue(rule.isActive)
        rule.feed(25L to 12.8f)                                 // beyond the margin: the recovery starts at 25
        rule.feed(28L to 12.6f)                                 // inside the band: the count starts over
        rule.feed(30L to 12.8f, 34L to 12.8f)                   // the recovery restarts at 30, only 4 s so far
        assertTrue(rule.isActive)
        rule.feed(35L to 12.8f)                                 // 5 s beyond the margin
        assertFalse(rule.isActive)
    }

    @Test
    fun `a non-active run ends after five seconds of non-violating values even inside the band`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.6f, 10L to 12.6f, 15L to 12.0f, 30L to 12.0f)
        assertFalse(rule.isActive)                              // the run ended at 10, the new one started at 15: 15 s
        rule.feed(35L to 12.0f)
        assertTrue(rule.isActive)                               // 20 s since 15
    }

    @Test
    fun `a non-finite sample is ignored and does not end a run`() {
        val rule = below()
        rule.feed(0L to 12.0f, 5L to 12.0f, 10L to 12.0f, 15L to Float.NaN, 20L to Float.NaN, 25L to 12.0f)
        assertTrue(rule.isActive)                               // NaN is no recovery; the run from 0 s reaches 20 s at 25
    }
}
