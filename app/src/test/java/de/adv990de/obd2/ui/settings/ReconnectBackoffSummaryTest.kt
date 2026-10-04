package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.ui.dashboard.reconnectDelays
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wait-series text under the reconnect base-delay choice (e.g. "3 · 6 · 12 · 24 · 48 s (1:33 min
 * in total)"). The series is derived from the production [reconnectDelays], not re-computed, so
 * the display cannot drift from what the reconnect loop really waits.
 */
class ReconnectBackoffSummaryTest {

    @Test
    fun `base 3 gives 3-6-12-24-48 and 93 seconds in total`() {
        val summary = reconnectBackoffSummary(3)
        assertEquals(listOf(3, 6, 12, 24, 48), summary.delaysSeconds)
        assertEquals(93, summary.totalSeconds)
    }

    @Test
    fun `base 10 gives 10-20-40-80-160 and 310 seconds in total`() {
        val summary = reconnectBackoffSummary(10)
        assertEquals(listOf(10, 20, 40, 80, 160), summary.delaysSeconds)
        assertEquals(310, summary.totalSeconds)
    }

    @Test
    fun `base 30 gives 30-60-120-240-480 and 930 seconds in total`() {
        val summary = reconnectBackoffSummary(30)
        assertEquals(listOf(30, 60, 120, 240, 480), summary.delaysSeconds)
        assertEquals(930, summary.totalSeconds)
    }

    @Test
    fun `for every option the series equals the production reconnectDelays`() {
        for (base in AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS) {
            assertEquals(
                reconnectDelays(base).map { (it / 1_000L).toInt() },
                reconnectBackoffSummary(base).delaysSeconds
            )
        }
    }

    @Test
    fun `the total is the sum of the series`() {
        for (base in AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS) {
            val summary = reconnectBackoffSummary(base)
            assertEquals(summary.delaysSeconds.sum(), summary.totalSeconds)
        }
    }

    @Test
    fun `formatMinutesSeconds pads the seconds to two digits`() {
        assertEquals("0:00", formatMinutesSeconds(0))
        assertEquals("0:09", formatMinutesSeconds(9))
        assertEquals("0:59", formatMinutesSeconds(59))
        assertEquals("1:00", formatMinutesSeconds(60))
        assertEquals("1:33", formatMinutesSeconds(93))
        assertEquals("5:10", formatMinutesSeconds(310))
        assertEquals("15:30", formatMinutesSeconds(930))
        assertEquals("60:00", formatMinutesSeconds(3_600))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `formatMinutesSeconds rejects a negative duration`() {
        formatMinutesSeconds(-1)
    }
}
