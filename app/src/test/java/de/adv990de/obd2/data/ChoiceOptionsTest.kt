package de.adv990de.obd2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [snapToOption] and the fixed option lists of the connection settings.
 *
 * The two lists below mirror the user's wording exactly ("Reconnect bei eingefrorenen Werten":
 * 10/20/30/60/120, "Reconnect-Basisverzoegerung": 3/10/30) — the list-equality tests are the
 * guard against an accidental edit of those values.
 */
class ChoiceOptionsTest {

    private val watchdog = listOf(10, 20, 30, 60, 120)
    private val baseDelay = listOf(3, 10, 30)

    // ── snapToOption ──────────────────────────────────────────────────────────

    @Test
    fun `an exact option is returned unchanged`() {
        for (option in watchdog) assertEquals(option, snapToOption(option, watchdog))
        for (option in baseDelay) assertEquals(option, snapToOption(option, baseDelay))
    }

    @Test
    fun `a value between two options snaps to the nearer one`() {
        assertEquals(20, snapToOption(24, watchdog))
        assertEquals(30, snapToOption(26, watchdog))
        assertEquals(60, snapToOption(70, watchdog))   // |70-60| = 10 < |120-70| = 50
        assertEquals(3, snapToOption(5, baseDelay))
        assertEquals(10, snapToOption(7, baseDelay))
    }

    @Test
    fun `an exact tie snaps to the larger option`() {
        assertEquals(60, snapToOption(45, watchdog))    // 30 and 60 are equally near
        assertEquals(30, snapToOption(25, watchdog))    // 20 and 30 are equally near
        assertEquals(30, snapToOption(20, baseDelay))   // 10 and 30 are equally near
    }

    @Test
    fun `a value below the smallest option snaps to the smallest`() {
        assertEquals(10, snapToOption(9, watchdog))
        assertEquals(10, snapToOption(0, watchdog))
        assertEquals(10, snapToOption(-5, watchdog))
        assertEquals(3, snapToOption(1, baseDelay))
    }

    @Test
    fun `a value above the largest option snaps to the largest`() {
        assertEquals(120, snapToOption(121, watchdog))
        assertEquals(120, snapToOption(300, watchdog))
        assertEquals(30, snapToOption(31, baseDelay))
    }

    @Test
    fun `the integer extremes snap without overflowing`() {
        // value - option would overflow Int for these; the distance is computed in Long.
        assertEquals(10, snapToOption(Int.MIN_VALUE, watchdog))
        assertEquals(120, snapToOption(Int.MAX_VALUE, watchdog))
    }

    @Test
    fun `a single option is always returned`() {
        assertEquals(7, snapToOption(100, listOf(7)))
        assertEquals(7, snapToOption(-100, listOf(7)))
    }

    @Test
    fun `the order of the options does not matter`() {
        val shuffled = listOf(120, 10, 60, 30, 20)
        assertEquals(60, snapToOption(45, shuffled))
        assertEquals(20, snapToOption(24, shuffled))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty option list is rejected`() {
        snapToOption(5, emptyList())
    }

    // ── option lists ──────────────────────────────────────────────────────────

    @Test
    fun `the watchdog options are exactly the user-specified values`() {
        assertEquals(listOf(10, 20, 30, 60, 120), AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS)
    }

    @Test
    fun `the reconnect base delay options are exactly the user-specified values`() {
        assertEquals(listOf(3, 10, 30), AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS)
    }

    @Test
    fun `each default is one of its options`() {
        // Otherwise an untouched install would snap its own default to a different value.
        assertTrue(
            AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS in
                AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS
        )
        assertTrue(
            AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS in
                AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS
        )
    }

    @Test
    fun `both option lists are strictly ascending`() {
        // The segments are laid out in list order — an unsorted list would scramble the row.
        for (options in listOf(
            AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS,
            AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS,
        )) {
            assertEquals(options.sorted().distinct(), options)
        }
    }
}
