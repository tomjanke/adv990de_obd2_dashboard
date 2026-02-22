package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertUnitTest {

    @Test
    fun `km-h converted to mph when imperial true`() {
        val (value, unit) = convertUnit(100f, "km/h", imperial = true)
        assertEquals("mph", unit)
        assertEquals(62.1f, value, 0.1f)
    }

    @Test
    fun `celsius converted to fahrenheit when imperial true`() {
        val (value, unit) = convertUnit(0f, "°C", imperial = true)
        assertEquals("°F", unit)
        assertEquals(32f, value, 0.01f)
    }

    @Test
    fun `celsius 100 degrees converted correctly`() {
        val (value, unit) = convertUnit(100f, "°C", imperial = true)
        assertEquals("°F", unit)
        assertEquals(212f, value, 0.01f)
    }

    @Test
    fun `other units unchanged when imperial true`() {
        val (value, unit) = convertUnit(2500f, "rpm", imperial = true)
        assertEquals("rpm", unit)
        assertEquals(2500f, value, 0.001f)
    }

    @Test
    fun `km-h unchanged when imperial false`() {
        val (value, unit) = convertUnit(80f, "km/h", imperial = false)
        assertEquals("km/h", unit)
        assertEquals(80f, value, 0.001f)
    }

    @Test
    fun `celsius unchanged when imperial false`() {
        val (value, unit) = convertUnit(90f, "°C", imperial = false)
        assertEquals("°C", unit)
        assertEquals(90f, value, 0.001f)
    }
}
