package de.adv990de.obd2.ui.loggingconfig

import org.junit.Assert.assertEquals
import org.junit.Test

class LoggingConfigScreenTest {

    @Test
    fun `non-volt value formats with zero decimals`() {
        val result = formatLoggingRowValue(87f, "°C", useImperial = false)
        assertEquals("87 (°C)", result)
    }

    @Test
    fun `volt value formats with one decimal`() {
        val result = formatLoggingRowValue(14.2f, "V", useImperial = false)
        assertEquals("14.2 (V)", result)
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
