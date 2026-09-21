package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSourceTest {

    private fun speed(value: Float) = SensorValue(PidRegistry.SPEED, value)
    private fun rpm(value: Float = 2000f) = SensorValue(PidRegistry.RPM, value)

    // --- Test 1 ---

    @Test
    fun `vehicle speed wins over gps`() {
        val readings = listOf(rpm(), speed(50f))

        val result = mergeGpsSpeed(readings, gpsKmh = 47f)

        assertEquals(readings, result)
    }

    // --- Test 2 ---

    @Test
    fun `gps fills in when the vehicle provides no speed`() {
        val result = mergeGpsSpeed(listOf(rpm()), gpsKmh = 47f)

        val added = result.single { it.pid.id == PidRegistry.SPEED.id }
        assertEquals(47f, added.value, 0.001f)
        assertEquals("Eigenes Label, damit die Quelle sichtbar bleibt", GPS_SPEED.labelRes, added.pid.labelRes)
        assertTrue("Die GPS-Definition darf nicht in der Registry stehen",
            PidRegistry.all.values.none { it.labelRes == GPS_SPEED.labelRes })
    }

    // --- Test 3 ---

    @Test
    fun `without a gps value the readings stay untouched`() {
        val readings = listOf(rpm())

        assertEquals(readings, mergeGpsSpeed(readings, gpsKmh = null))
    }

    // --- Test 4 ---

    @Test
    fun `implausible gps values are ignored`() {
        val readings = listOf(rpm())

        assertEquals(readings, mergeGpsSpeed(readings, gpsKmh = -1f))
        assertEquals(readings, mergeGpsSpeed(readings, gpsKmh = Float.NaN))
    }

    // --- Test 5 ---

    @Test
    fun `a NaN vehicle speed does not block the gps fallback`() {
        // Der Adapter hat geantwortet, der Wert ist aber unbrauchbar — dann zaehlt GPS.
        val result = mergeGpsSpeed(listOf(speed(Float.NaN)), gpsKmh = 30f)

        assertTrue(result.any { it.pid.id == PidRegistry.SPEED.id && it.value == 30f })
    }
}
