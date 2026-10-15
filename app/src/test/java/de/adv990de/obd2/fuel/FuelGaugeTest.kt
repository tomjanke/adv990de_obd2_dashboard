package de.adv990de.obd2.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Tankanzeige ist ein Streckenzaehler: Rest = Reichweite bei vollem Tank minus gefahrene Kilometer.
 * Die Stufen sind die des Nutzers (2026-10-15): 100-40 % gruen, 39-20 % gelb, 19-5 % rot, unter 5 % rot blinkend.
 * "39 %" heisst: unter 40 — die Grenzen sind deshalb Untergrenzen (>= 40, >= 20, >= 5), keine Luecken bei 39,5 %.
 */
class FuelGaugeTest {

    private fun level(rangeKm: Int, drivenKm: Float) = FuelGaugeState(rangeKm, drivenKm).level

    @Test
    fun `a full tank is green and shows 100 percent`() {
        val state = FuelGaugeState(rangeFullKm = 320, kmSinceFill = 0f)

        assertEquals(FuelLevel.Green, state.level)
        assertEquals(1f, state.fraction, 0f)
        assertEquals(100, state.percent)
        assertEquals(320f, state.remainingKm, 0f)
    }

    @Test
    fun `exactly 40 percent is still green, just below it is yellow`() {
        assertEquals(FuelLevel.Green, level(200, 120f))   // 80 km = 40 %
        assertEquals(FuelLevel.Yellow, level(200, 120.2f)) // 79.8 km = 39,9 %
    }

    @Test
    fun `exactly 20 percent is still yellow, just below it is red`() {
        assertEquals(FuelLevel.Yellow, level(200, 160f))   // 40 km = 20 %
        assertEquals(FuelLevel.Red, level(200, 160.2f))    // 39,8 km = 19,9 %
    }

    @Test
    fun `exactly 5 percent is still red, just below it is red and blinking`() {
        assertEquals(FuelLevel.Red, level(200, 190f))          // 10 km = 5 %
        assertEquals(FuelLevel.RedBlinking, level(200, 190.2f)) // 9,8 km = 4,9 %
    }

    @Test
    fun `an empty tank is red and blinking`() {
        assertEquals(FuelLevel.RedBlinking, level(200, 200f))
    }

    @Test
    fun `driving past the range stays at empty - never negative, never above full`() {
        val over = FuelGaugeState(200, 260f)

        assertEquals(0f, over.remainingKm, 0f)
        assertEquals(0f, over.fraction, 0f)
        assertEquals(0, over.percent)
        assertEquals(FuelLevel.RedBlinking, over.level)
    }

    @Test
    fun `a negative or non-finite counter is treated as nothing driven`() {
        assertEquals(1f, FuelGaugeState(200, -5f).fraction, 0f)
        assertEquals(1f, FuelGaugeState(200, Float.NaN).fraction, 0f)
        assertEquals(1f, FuelGaugeState(200, Float.POSITIVE_INFINITY).fraction, 0f)
    }

    @Test
    fun `a range of zero or less is not a gauge`() {
        val failure = runCatching { FuelGaugeState(0, 0f) }

        assertTrue("erwartet IllegalArgumentException, war $failure", failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `the percentage is rounded for the description`() {
        assertEquals(63, FuelGaugeState(300, 111f).percent)   // 189 km = 63 %
        assertEquals(38, FuelGaugeState(200, 124.6f).percent) // 75,4 km = 37,7 %
    }

    @Test
    fun `only blinking levels blink`() {
        assertFalse(FuelLevel.Green.blinks)
        assertFalse(FuelLevel.Yellow.blinks)
        assertFalse(FuelLevel.Red.blinks)
        assertTrue(FuelLevel.RedBlinking.blinks)
    }

    @Test
    fun `fuelLevel of an unknown fraction (NaN) is the alarm level, never green`() {
        assertEquals(FuelLevel.RedBlinking, fuelLevel(Float.NaN))
    }

    @Test
    fun `the range limits are 50 to 1000 km`() {
        assertEquals(50, FuelRangeLimits.MIN_KM)
        assertEquals(1000, FuelRangeLimits.MAX_KM)
    }
}
