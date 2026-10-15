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

    // --- Segmente (Nutzerwunsch 2026-10-15): 10 Segmente, die Anzeige sinkt segmentweise, nicht stufenlos ---

    private fun lit(rangeKm: Int, drivenKm: Float) = FuelGaugeState(rangeKm, drivenKm).litSegments

    @Test
    fun `the gauge has ten segments`() {
        assertEquals(10, FUEL_SEGMENT_COUNT)
    }

    @Test
    fun `a full tank lights all ten segments, an empty one none`() {
        assertEquals(10, lit(200, 0f))
        assertEquals(0, lit(200, 200f))
        assertEquals(0, lit(200, 999f))
    }

    @Test
    fun `a segment stays lit while any of its tenth is left - the first one goes out only after a tenth of the range is driven`() {
        assertEquals(10, lit(200, 0.2f))   // 99,9 % bleibt voll
        assertEquals(10, lit(200, 19.8f))  // 90,1 %
        assertEquals(9, lit(200, 20f))     // genau 90 % = 9 Segmente
        assertEquals(9, lit(200, 20.2f))   // 89,9 %
        assertEquals(6, lit(200, 99.8f))   // 50,1 % -> ceil(5,01) = 6
        assertEquals(5, lit(200, 100f))    // genau 50 %
    }

    @Test
    fun `the last segment stays lit down to an empty tank, so the blinking stage below 5 percent still has something to blink`() {
        assertEquals(1, lit(200, 190f))    // 5 % (rot)
        assertEquals(1, lit(200, 190.2f))  // 4,9 % (rot blinkend)
        assertEquals(1, lit(200, 199.8f))  // 0,1 %
        assertEquals(0, lit(200, 200f))
    }

    @Test
    fun `the display drops by whole segments, never in between`() {
        // Fuenf Kilometer je Schritt ueber die ganze Reichweite: die Zahl leuchtender Segmente faellt nur um ganze Stufen und nie wieder hoch.
        val series = (0..200 step 5).map { lit(200, it.toFloat()) }

        assertEquals(10, series.first())
        assertEquals(0, series.last())
        series.zipWithNext().forEach { (a, b) ->
            assertTrue("$a -> $b: nie mehr leuchtende Segmente, nie ein Sprung um mehr als eines bei 5 km (2,5 %)", b <= a && a - b <= 1)
        }
    }

    @Test
    fun `every segment boundary lands exactly - no float noise lights one segment too many`() {
        // Jede Reichweite von 50 bis 1000 km, jede Grenze k/10 mit Dezimal-Kilometern (Reichweite 51, gefahren 40,8 km = genau 2 Zehntel Rest):
        // remaining / range * 10 ergibt dort in Float 2,0000002 — ohne Toleranz leuchtete ein Segment zu viel.
        for (range in 50..1000) {
            for (k in 0..10) {
                val drivenKm = range - range * k / 10f
                assertEquals("Reichweite $range km, gefahren $drivenKm km (Rest = $k Zehntel)", k, lit(range, drivenKm))
            }
        }
    }

    @Test
    fun `known float overshoots at a tenth boundary still show the exact segment count`() {
        // Gemessen (Float-Rechnung nachgestellt): Anteil * 10 liegt hier ueber der ganzen Zahl, ohne Toleranz waere es ein Segment mehr.
        assertEquals(2, lit(51, 40.8f))   // Rest 10,2 km = 2 Zehntel -> 2,0000002
        assertEquals(1, lit(52, 46.8f))   // 5,2 km = 1 Zehntel -> 1,0000001
        assertEquals(7, lit(52, 15.6f))   // 36,4 km = 7 Zehntel -> 7,0000005
        assertEquals(3, lit(53, 37.1f))   // 15,9 km = 3 Zehntel -> 3,0000005
        assertEquals(4, lit(56, 33.6f))   // 22,4 km = 4 Zehntel -> 4,0000005
    }

    @Test
    fun `a bit more than k tenths left lights k plus one segments`() {
        for (range in 50..1000 step 10) {
            for (k in 0..9) {
                val remaining = range * k / 10f + 0.05f * range / 100f // 0,05 % der Reichweite ueber der Grenze
                assertEquals("Reichweite $range km, k=$k", k + 1, lit(range, range - remaining))
            }
        }
    }

    @Test
    fun `fuelLitSegments clamps and rejects unknown fractions`() {
        assertEquals(10, fuelLitSegments(1.7f))
        assertEquals(0, fuelLitSegments(-0.3f))
        assertEquals(0, fuelLitSegments(Float.NaN))
        assertEquals(5, fuelLitSegments(0.5f, count = 5 * 2))
        assertEquals(3, fuelLitSegments(0.5f, count = 5)) // ceil(2,5)
    }
}
