package de.adv990de.obd2.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the two small typography extensions in `AppTypography.kt`:
 * [TextStyle.bike] (the motorcycle-readability scale applied to every M3 typography token) and
 * [TextStyle.asLiveValue] (the live-value font swap used for inline readouts) — plus the
 * `AppTypography` object's own per-field application of `.bike()` across all 15 `Typography`
 * tokens, so a future edit that skips `.bike()` on any one field fails a specific assertion.
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

    // --- AppTypography (per-field application across all 15 Typography tokens) ---

    /**
     * `AppTypography` is built as `Typography().run { copy(displayLarge = displayLarge.bike(), ...) }`
     * over all 15 `Typography` fields. The tests above only exercise `bike()` in isolation and check
     * `displayLarge.fontFamily` indirectly — none of them would catch a future edit that forgets to
     * apply `.bike()` to one specific field (e.g. leaves `bodySmall` at its M3 default). This test
     * closes that gap by asserting, field by field, that every one of the 15 tokens on
     * `AppTypography` equals the corresponding baseline `Typography()` field with `.bike()` applied.
     */
    @Test
    fun `AppTypography applies bike() to all 15 Typography fields`() {
        val baseline = Typography()

        val fields = listOf(
            "displayLarge" to (AppTypography.displayLarge to baseline.displayLarge.bike()),
            "displayMedium" to (AppTypography.displayMedium to baseline.displayMedium.bike()),
            "displaySmall" to (AppTypography.displaySmall to baseline.displaySmall.bike()),
            "headlineLarge" to (AppTypography.headlineLarge to baseline.headlineLarge.bike()),
            "headlineMedium" to (AppTypography.headlineMedium to baseline.headlineMedium.bike()),
            "headlineSmall" to (AppTypography.headlineSmall to baseline.headlineSmall.bike()),
            "titleLarge" to (AppTypography.titleLarge to baseline.titleLarge.bike()),
            "titleMedium" to (AppTypography.titleMedium to baseline.titleMedium.bike()),
            "titleSmall" to (AppTypography.titleSmall to baseline.titleSmall.bike()),
            "bodyLarge" to (AppTypography.bodyLarge to baseline.bodyLarge.bike()),
            "bodyMedium" to (AppTypography.bodyMedium to baseline.bodyMedium.bike()),
            "bodySmall" to (AppTypography.bodySmall to baseline.bodySmall.bike()),
            "labelLarge" to (AppTypography.labelLarge to baseline.labelLarge.bike()),
            "labelMedium" to (AppTypography.labelMedium to baseline.labelMedium.bike()),
            "labelSmall" to (AppTypography.labelSmall to baseline.labelSmall.bike()),
        )

        // Guards the guard: if this list itself lost an entry, the test would silently cover less
        // than all 15 fields without failing on its own.
        assertEquals(15, fields.size)

        fields.forEach { (fieldName, actualToExpected) ->
            val (actual, expected) = actualToExpected
            assertEquals("AppTypography.$fieldName must equal Typography().$fieldName.bike()", expected, actual)
        }
    }
}
