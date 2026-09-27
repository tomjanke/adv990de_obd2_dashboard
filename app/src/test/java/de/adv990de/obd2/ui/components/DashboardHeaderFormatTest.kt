package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
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
}
