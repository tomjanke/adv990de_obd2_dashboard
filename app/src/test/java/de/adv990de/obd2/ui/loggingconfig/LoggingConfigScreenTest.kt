package de.adv990de.obd2.ui.loggingconfig

import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LoggingConfigScreenTest {

    @Test
    fun `non-volt value formats with zero decimals`() {
        val result = formatLoggingRowValue(87f, "°C", useImperial = false)
        assertEquals("87 (°C)", result)
    }

    @Test
    fun `volt value formats with one decimal point on an English-locale device`() = withDefaultLocale(Locale.US) {
        assertEquals("14.2 (V)", formatLoggingRowValue(14.2f, "V", useImperial = false))
    }

    @Test
    fun `volt value formats with a decimal comma on a German-locale device`() = withDefaultLocale(Locale.GERMANY) {
        assertEquals("14,2 (V)", formatLoggingRowValue(14.2f, "V", useImperial = false))
    }

    @Test
    fun `NaN value falls back to double-dash`() {
        val result = formatLoggingRowValue(Float.NaN, "rpm", useImperial = false)
        assertEquals("-- (rpm)", result)
    }

    @Test
    fun `infinite value falls back to double-dash`() {
        val result = formatLoggingRowValue(Float.POSITIVE_INFINITY, "km/h", useImperial = false)
        assertEquals("-- (km/h)", result)
    }

    @Test
    fun `imperial conversion applies before formatting, matching convertUnit`() {
        // Same case as ConvertUnitTest's "km-h converted to mph when imperial true":
        // convertUnit(100f, "km/h", imperial = true) -> 62.1 mph (±0.1), 0 decimals here.
        val result = formatLoggingRowValue(100f, "km/h", useImperial = true)
        assertEquals("62 (mph)", result)
    }
}
