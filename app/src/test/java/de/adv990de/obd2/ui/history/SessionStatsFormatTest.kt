package de.adv990de.obd2.ui.history

import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Die Zahlen des Blocks „Messwerte“: wie die Kacheln (Volt mit einer Nachkommastelle, sonst ganze Zahl, Gerätesprache), mit Einheit und festem Leerzeichen davor. */
class SessionStatsFormatTest {

    private val nbsp = " "

    @Test
    fun `coolant is a whole number with its unit`() {
        assertEquals("92${nbsp}°C", formatStatValue(92.4f, "°C", imperial = false))
    }

    @Test
    fun `volts have one decimal - comma in German, point in English`() {
        assertEquals("14,2${nbsp}V", withDefaultLocale(Locale.GERMANY) { formatStatValue(14.24f, "V", imperial = false) })
        assertEquals("14.2${nbsp}V", withDefaultLocale(Locale.US) { formatStatValue(14.24f, "V", imperial = false) })
    }

    @Test
    fun `imperial units turn the coolant into Fahrenheit`() {
        assertEquals("212${nbsp}°F", formatStatValue(100f, "°C", imperial = true))
        assertEquals("32${nbsp}°F", formatStatValue(0f, "°C", imperial = true))
    }

    @Test
    fun `minus 40 is minus 40 in both scales`() {
        assertEquals("-40${nbsp}°C", withDefaultLocale(Locale.US) { formatStatValue(-40f, "°C", imperial = false) })
        assertEquals("-40${nbsp}°F", withDefaultLocale(Locale.US) { formatStatValue(-40f, "°C", imperial = true) })
    }

    @Test
    fun `volts stay volts when the units are imperial`() {
        assertEquals("13.8${nbsp}V", withDefaultLocale(Locale.US) { formatStatValue(13.8f, "V", imperial = true) })
    }

    @Test
    fun `a small negative value that rounds to zero shows no minus sign`() {
        assertEquals("0${nbsp}°C", withDefaultLocale(Locale.US) { formatStatValue(-0.3f, "°C", imperial = false) })
    }
}
