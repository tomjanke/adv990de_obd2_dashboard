package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * [formatDashboardHeader] is a pure function — no Android/Compose dependency — so this is a plain
 * JVM test, no Hilt/Robolectric ceremony needed.
 *
 * The expected strings below were locked in from the *real* `java.time.format.DateTimeFormatter`
 * output on this project's JDK (Temurin 27, matching Gradle's launcher JVM — verified via
 * `./gradlew -version`), not guessed:
 * - The task brief's own draft epoch constant (`1790598720000L`) was wrong: it decodes to
 *   **Monday** 2026-09-28 14:32 CEST, not the intended Sunday 2026-09-27 as its comment claimed
 *   (off by one calendar day). Verified independently via Python's `zoneinfo` before writing this
 *   file. [millis] below is the corrected value for the actually-intended instant.
 * - The brief's guessed German weekday abbreviation ("So" without a period) does not match this
 *   JDK's real CLDR data for `Locale.GERMAN` + the `EEE` pattern letter in format (not stand-alone)
 *   context: it actually produces "So." WITH a trailing period. Verified via `jshell` running the
 *   exact same `DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMAN)` call `formatDashboardHeader`
 *   uses, then locked in here. English ("Sun", no period) matched the brief's guess.
 */
class DashboardHeaderFormatTest {

    private val fixedZone = ZoneId.of("Europe/Berlin")

    // Sonntag, 2026-09-27, 14:32 Europe/Berlin (CEST, UTC+2) -> epoch millis.
    private val millis = 1790512320000L

    @Test
    fun `formats a German locale date and time`() {
        assertEquals("So. 27.09. · 14:32", formatDashboardHeader(millis, Locale.GERMAN, fixedZone))
    }

    @Test
    fun `formats an English locale date and time`() {
        assertEquals("Sun 27.09. · 14:32", formatDashboardHeader(millis, Locale.ENGLISH, fixedZone))
    }

    // ── formatOutsideTemperature: same convertUnit convention as SensorCard ─────────────────

    @Test
    fun `metric temperature is rounded to whole degrees Celsius`() {
        assertEquals("18 °C", formatOutsideTemperature(18.4f, useImperial = false))
    }

    @Test
    fun `imperial setting converts to Fahrenheit like every other temperature in the app`() {
        // 20 °C * 9/5 + 32 = 68 °F
        assertEquals("68 °F", formatOutsideTemperature(20f, useImperial = true))
    }

    @Test
    fun `imperial conversion is rounded after converting, not before`() {
        // 18.4 °C = 65.12 °F -> "65". Rounding first (18 °C = 64.4 °F -> "64") would be off by one.
        assertEquals("65 °F", formatOutsideTemperature(18.4f, useImperial = true))
    }

    @Test
    fun `a half degree rounds up`() {
        assertEquals("18 °C", formatOutsideTemperature(17.5f, useImperial = false))
    }

    @Test
    fun `slightly below zero shows 0, never a negative zero`() {
        assertEquals("0 °C", formatOutsideTemperature(-0.4f, useImperial = false))
    }

    @Test
    fun `no temperature yields null so the row can omit it`() {
        assertNull(formatOutsideTemperature(null, useImperial = false))
        assertNull(formatOutsideTemperature(null, useImperial = true))
    }

    @Test
    fun `a non-finite temperature yields null instead of crashing roundToInt`() {
        // NaN.roundToInt() throws IllegalArgumentException — on the UI thread that would crash the
        // app. Same guard as SensorCard/formatLoggingRowValue.
        assertNull(formatOutsideTemperature(Float.NaN, useImperial = false))
        assertNull(formatOutsideTemperature(Float.POSITIVE_INFINITY, useImperial = false))
        assertNull(formatOutsideTemperature(Float.NEGATIVE_INFINITY, useImperial = true))
    }

    // ── millisUntilNextMinute: the clock ticks on the real minute boundary ──────────────────

    @Test
    fun `right after a minute boundary the next tick is almost a full minute away`() {
        assertEquals(59_999L, millisUntilNextMinute(millis + 1))
    }

    @Test
    fun `half a minute in, the next tick is half a minute away`() {
        assertEquals(30_000L, millisUntilNextMinute(millis + 30_000))
    }

    @Test
    fun `one millisecond before the boundary, the next tick is one millisecond away`() {
        assertEquals(1L, millisUntilNextMinute(millis + 59_999))
    }

    @Test
    fun `exactly on a boundary, the next tick is the following boundary`() {
        // millis is 14:32:00.000 — never a 0 ms delay that would spin the tick loop.
        assertEquals(60_000L, millisUntilNextMinute(millis))
    }

    @Test
    fun `the computed tick lands exactly on the next minute`() {
        val now = millis + 12_345
        assertEquals(0L, (now + millisUntilNextMinute(now)) % 60_000L)
    }
}
