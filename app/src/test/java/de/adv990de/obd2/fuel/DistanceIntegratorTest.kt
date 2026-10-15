package de.adv990de.obd2.fuel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Strecke aus den GPS-Geschwindigkeiten (km/h) ueber die Zeit (ms, monotone Uhr). Trapez zwischen zwei Messungen; unbekannte,
 * unplausible oder zu weit auseinanderliegende Messungen zaehlen nichts (lieber zu wenig als erfundene Kilometer).
 */
class DistanceIntegratorTest {

    private val integrator = DistanceIntegrator()

    @Test
    fun `the first sample only starts the interval - it counts nothing`() {
        assertEquals(0f, integrator.sample(72f, atMillis = 1_000L), 0f)
    }

    @Test
    fun `72 km per hour for 10 seconds is 0,2 km`() {
        integrator.sample(72f, 0L)

        assertEquals(0.2f, integrator.sample(72f, 10_000L), 1e-6f)
    }

    @Test
    fun `a ramp counts the mean speed (trapezoid)`() {
        integrator.sample(0f, 0L)
        // 0 -> 100 km/h in 10 s: mean 50 km/h => 50 * 10 / 3600 km. (0 liegt unter der Zaehlgrenze und zaehlt als stehend.)
        assertEquals(50f * 10f / 3600f, integrator.sample(100f, 10_000L), 1e-5f)
    }

    @Test
    fun `many short steps add up to the same distance as one long step`() {
        integrator.sample(90f, 0L)
        var sum = 0f
        for (i in 1..50) sum += integrator.sample(90f, i * 200L) // 10 s in 200-ms steps (GPS-Takt)

        assertEquals(0.25f, sum, 1e-4f)
    }

    @Test
    fun `an unknown speed ends the interval - the next sample starts a new one and counts nothing`() {
        integrator.sample(72f, 0L)
        assertEquals(0f, integrator.sample(null, 5_000L), 0f)

        assertEquals(0f, integrator.sample(72f, 6_000L), 0f)
        assertEquals(0.02f, integrator.sample(72f, 7_000L), 1e-6f)
    }

    @Test
    fun `NaN, infinite, negative and implausible speeds are unknown`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 251f, 1_000f)) {
            val fresh = DistanceIntegrator()
            fresh.sample(72f, 0L)
            assertEquals("Geschwindigkeit $bad zaehlt nichts", 0f, fresh.sample(bad, 1_000L), 0f)
            // Das Intervall ist danach beendet: auch der naechste gueltige Wert zaehlt noch nichts.
            assertEquals(0f, fresh.sample(72f, 2_000L), 0f)
        }
    }

    @Test
    fun `250 km per hour is still plausible`() {
        integrator.sample(250f, 0L)

        assertEquals(250f / 3600f, integrator.sample(250f, 1_000L), 1e-5f)
    }

    @Test
    fun `a gap of more than 10 seconds is not counted - no guess across a signal loss`() {
        integrator.sample(100f, 0L)

        assertEquals(0f, integrator.sample(100f, 10_001L), 0f)
        assertEquals(100f / 3600f, integrator.sample(100f, 11_001L), 1e-5f)
    }

    @Test
    fun `a gap of exactly 10 seconds is still counted`() {
        integrator.sample(36f, 0L)

        assertEquals(0.1f, integrator.sample(36f, 10_000L), 1e-6f)
    }

    @Test
    fun `speeds below 2 km per hour are GPS noise at a standstill and count as standing`() {
        integrator.sample(1.5f, 0L)

        assertEquals(0f, integrator.sample(1.9f, 10_000L), 0f)
    }

    @Test
    fun `starting to move from standstill counts half of the first step (trapezoid with 0)`() {
        integrator.sample(1f, 0L)

        assertEquals(20f / 3600f * 1f, integrator.sample(40f, 1_000L), 1e-6f)
    }

    @Test
    fun `time that does not advance counts nothing, a clock going backwards neither`() {
        integrator.sample(72f, 5_000L)
        assertEquals(0f, integrator.sample(72f, 5_000L), 0f)
        assertEquals(0f, integrator.sample(72f, 4_000L), 0f)
    }

    @Test
    fun `reset ends the interval`() {
        integrator.sample(72f, 0L)
        integrator.reset()

        assertEquals(0f, integrator.sample(72f, 1_000L), 0f)
        assertEquals(0.02f, integrator.sample(72f, 2_000L), 1e-6f)
    }
}
