package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {

    @Test
    fun `convertUnit returns value and unit unchanged when imperial is false`() {
        val (value, unit) = convertUnit(100f, "km/h", imperial = false)
        assertEquals(100f, value, 0.01f)
        assertEquals("km/h", unit)
    }

    @Test
    fun `convertUnit converts km-h to mph when imperial is true`() {
        // 100 km/h * 0.621371 = 62.1371 mph
        val (value, unit) = convertUnit(100f, "km/h", imperial = true)
        assertEquals(62.1371f, value, 0.01f)
        assertEquals("mph", unit)
    }

    @Test
    fun `convertUnit converts degC to degF when imperial is true`() {
        // 91°C * 9/5 + 32 = 195.8°F
        val (value, unit) = convertUnit(91f, "°C", imperial = true)
        assertEquals(195.8f, value, 0.01f)
        assertEquals("°F", unit)
    }

    @Test
    fun `convertUnit returns value and unit unchanged for unmatched unit even when imperial is true`() {
        val (value, unit) = convertUnit(14.7f, "V", imperial = true)
        assertEquals(14.7f, value, 0.01f)
        assertEquals("V", unit)
    }
}
