package de.adv990de.obd2.ui.components

import de.adv990de.obd2.fuel.FuelLevel
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Balkenfarben der Tankanzeige sind die Ampel-Tokens (`StatusColors`), nie Theme-Farben und nie Literale in der Oberflaeche. */
class FuelBarColorTest {

    @Test
    fun `green is the fixed traffic-light green`() {
        assertEquals(StatusColors.TrafficLightGreen, fuelBarColor(FuelLevel.Green))
    }

    @Test
    fun `yellow is the warning yellow`() {
        assertEquals(StatusColors.Warning, fuelBarColor(FuelLevel.Yellow))
    }

    @Test
    fun `red and blinking red are the critical red - the blinking is the animation, not another colour`() {
        assertEquals(StatusColors.Critical, fuelBarColor(FuelLevel.Red))
        assertEquals(StatusColors.Critical, fuelBarColor(FuelLevel.RedBlinking))
    }

    @Test
    fun `the three colours are distinguishable`() {
        val colours = listOf(FuelLevel.Green, FuelLevel.Yellow, FuelLevel.Red).map(::fuelBarColor)

        assertEquals(3, colours.toSet().size)
        assertNotEquals(fuelBarColor(FuelLevel.Green), fuelBarColor(FuelLevel.Red))
    }

    @Test
    fun `the blink half period is 500 ms and the off phase keeps a faint bar`() {
        assertEquals(500L, FUEL_BLINK_HALF_PERIOD_MS)
        assertTrue(FUEL_BLINK_OFF_ALPHA in 0.05f..0.4f)
    }
}
