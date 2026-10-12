package de.adv990de.obd2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * QS-Pflege (SCR): Verlauf, Fehlerhistorie und App-Logs hatten drei eigene feste Muster (`dd.MM.yyyy`, `dd.MM.yyyy HH:mm`,
 * `dd.MM.yyyy HH:mm:ss`), das letzte sogar einmal beim Laden der Klasse gebaut — eine Sprachaenderung zur Laufzeit erreichte es nie.
 * Jetzt: eine Stelle, Format nach Sprache des Geraets, Zeitzone und Sprache von aussen (Testbarkeits-Konvention des Projekts).
 */
class DateTimeFormatsTest {

    private val instant = Instant.parse("2026-10-12T14:30:05Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a date follows the locale - German day first, US month name first`() {
        assertEquals("12.10.2026", formatDate(instant, Locale.GERMANY, utc))
        assertEquals("Oct 12, 2026", formatDate(instant, Locale.US, utc))
    }

    @Test
    fun `a date carries no time of day`() {
        assertFalse(formatDate(instant, Locale.GERMANY, utc).contains("14"))
    }

    @Test
    fun `a date and time shows the date first, then the time without seconds`() {
        val text = formatDateTime(instant, Locale.GERMANY, utc)
        assertTrue(text, text.startsWith("12.10.2026"))
        assertTrue(text, text.endsWith("14:30"))
        assertFalse("keine Sekunden: $text", text.contains("14:30:05"))
    }

    @Test
    fun `a date and time with seconds adds the seconds`() {
        val text = formatDateTimeSeconds(instant, Locale.GERMANY, utc)
        assertTrue(text, text.startsWith("12.10.2026"))
        assertTrue(text, text.endsWith("14:30:05"))
    }

    @Test
    fun `the US time uses the 12-hour clock of that locale`() {
        val text = formatDateTime(instant, Locale.US, utc)
        assertTrue(text, text.contains("2:30"))
        assertTrue(text, text.contains("PM"))
    }

    @Test
    fun `the time zone moves the hour and can move the day`() {
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9 -> 23:30 desselben Tages
        assertTrue(formatDateTime(instant, Locale.GERMANY, tokyo).endsWith("23:30"))
        val auckland = ZoneId.of("Pacific/Auckland") // UTC+13 im Oktober -> 13.10., 03:30
        val text = formatDateTime(instant, Locale.GERMANY, auckland)
        assertTrue(text, text.startsWith("13.10.2026"))
        assertTrue(text, text.endsWith("03:30"))
    }

    @Test
    fun `a locale change is picked up - nothing is frozen at class load`() {
        // Das alte `private val timestampFormatter` der App-Logs wurde einmal gebaut und blieb bei der Sprache von damals.
        assertNotEquals(formatDate(instant, Locale.GERMANY, utc), formatDate(instant, Locale.US, utc))
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val german = formatDate(instant, zone = utc)
            Locale.setDefault(Locale.US)
            val american = formatDate(instant, zone = utc)
            assertNotEquals(german, american)
        } finally {
            Locale.setDefault(before)
        }
    }
}
