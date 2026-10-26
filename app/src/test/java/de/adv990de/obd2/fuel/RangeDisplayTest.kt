package de.adv990de.obd2.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Zahl in der Tankzeile ist eine Schaetzung (der Zaehler zaehlt nur GPS-Kilometer, die Reichweite ist von Hand oder aus dem Logbuch): sie wird auf 5 km
 * **abgerundet** (zur sicheren Seite, keine falsche Genauigkeit). Die Reserve ist eine Marke in km Restreichweite (Entwurf `2026-10-25-range-design.md`, D2/D3).
 */
class RangeDisplayTest {

    @Test
    fun `the number is rounded down to five kilometers`() {
        assertEquals(185, displayRangeKm(187f))
        assertEquals(185, displayRangeKm(185f))
        assertEquals(180, displayRangeKm(184.9f))
        assertEquals(1000, displayRangeKm(1000f))
    }

    @Test
    fun `below five kilometers the number is zero`() {
        assertEquals(0, displayRangeKm(4.9f))
        assertEquals(5, displayRangeKm(5f))
        assertEquals(5, displayRangeKm(9.9f))
        assertEquals(10, displayRangeKm(10f))
        assertEquals(0, displayRangeKm(0f))
    }

    @Test
    fun `a negative or unknown remainder shows zero, never a negative number`() {
        assertEquals(0, displayRangeKm(-3f))
        assertEquals(0, displayRangeKm(Float.NaN))
        assertEquals(0, displayRangeKm(Float.POSITIVE_INFINITY))
        assertEquals(0, displayRangeKm(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `the reserve mark sits at reserve divided by the full range`() {
        assertEquals(40f / 260f, reserveFraction(rangeFullKm = 260, reserveKm = 40)!!, 1e-6f)
        assertEquals(0.5f, reserveFraction(rangeFullKm = 200, reserveKm = 100)!!, 1e-6f)
    }

    @Test
    fun `no reserve set means no mark`() {
        assertNull(reserveFraction(rangeFullKm = 260, reserveKm = 0))
        assertNull(reserveFraction(rangeFullKm = 260, reserveKm = -5))
    }

    @Test
    fun `a reserve as large as the range or larger has no useful mark`() {
        assertNull(reserveFraction(rangeFullKm = 100, reserveKm = 100))
        assertNull(reserveFraction(rangeFullKm = 100, reserveKm = 250))
    }

    @Test
    fun `exactly at the reserve counts as in the reserve`() {
        assertTrue(inReserve(remainingKm = 40f, reserveKm = 40))
        assertTrue(inReserve(remainingKm = 39.9f, reserveKm = 40))
        assertFalse(inReserve(remainingKm = 40.1f, reserveKm = 40))
    }

    @Test
    fun `without a reserve nothing is in the reserve - not even an empty tank`() {
        assertFalse(inReserve(remainingKm = 0f, reserveKm = 0))
        assertFalse(inReserve(remainingKm = 10f, reserveKm = 0))
    }

    @Test
    fun `an unknown remainder is not in the reserve`() {
        assertFalse(inReserve(remainingKm = Float.NaN, reserveKm = 40))
    }

    @Test
    fun `the limits of the reserve setting are 5 to 500 km`() {
        assertEquals(5, FuelReserveLimits.MIN_KM)
        assertEquals(500, FuelReserveLimits.MAX_KM)
    }

    @Test
    fun `the gauge state defaults to no reserve and behaves as before`() {
        val state = FuelGaugeState(rangeFullKm = 320, kmSinceFill = 70f)

        assertEquals(0, state.reserveKm)
        assertNull(state.reserveFraction)
        assertFalse(state.inReserve)
        assertEquals(250, state.displayKm)
    }

    @Test
    fun `the gauge state exposes the derived values for the row`() {
        val state = FuelGaugeState(rangeFullKm = 260, kmSinceFill = 225f, reserveKm = 40)

        assertEquals(35f, state.remainingKm, 1e-4f)
        assertEquals(35, state.displayKm)
        assertEquals(40f / 260f, state.reserveFraction!!, 1e-6f)
        assertTrue(state.inReserve)
    }
}
