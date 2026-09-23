package de.adv990de.obd2.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the two small typography extensions in `AppTypography.kt`:
 * [TextStyle.bike] (the motorcycle-readability scale applied to every M3 typography token) and
 * [TextStyle.asLiveValue] (the live-value font swap used for inline readouts).
 */
class AppTypographyTest {

    private val DELTA = 0.001f

    // Same value as the private BIKE_SCALE const in AppTypography.kt — kept here as a named
    // literal so a future change to that const shows up as a failing assertion, not a silent
    // no-op (the test does not read the const directly, it is private to that file).
    private val bikeScale = 1.18f

    // --- bike() ---

    @Test
    fun `bike scales fontSize by BIKE_SCALE`() {
        val input = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

        val result = input.bike()

        assertEquals(16f * bikeScale, result.fontSize.value, DELTA)
    }

    @Test
    fun `bike scales lineHeight by BIKE_SCALE`() {
        val input = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

        val result = input.bike()

        assertEquals(24f * bikeScale, result.lineHeight.value, DELTA)
    }

    @Test
    fun `bike swaps fontFamily to the Space Grotesk family`() {
        val input = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

        val result = input.bike()

        // SpaceGroteskFamily is private to AppTypography.kt; AppTypography.displayLarge is the
        // public, documented result of applying bike() to the M3 default, so its fontFamily is
        // structurally (and referentially) the same FontFamily bike() assigns here.
        assertEquals(AppTypography.displayLarge.fontFamily, result.fontFamily)
    }

    // --- asLiveValue() ---

    @Test
    fun `asLiveValue swaps fontFamily to PlexMonoFamily and enables tnum without changing fontSize`() {
        val input = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)

        val result = input.asLiveValue()

        assertEquals(PlexMonoFamily, result.fontFamily)
        assertEquals("tnum", result.fontFeatureSettings)
        assertEquals(20f, result.fontSize.value, DELTA)
    }
}
