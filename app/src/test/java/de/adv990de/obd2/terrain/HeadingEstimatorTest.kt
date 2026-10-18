package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Peilung = GPS-Kurs ueber Grund, gueltig ab 8 km/h und bei ausreichender Kursgenauigkeit; darunter 30 s gehalten. */
class HeadingEstimatorTest {

    private fun fix(t: Long, speed: Float? = 50f, bearing: Float? = 45f, accuracy: Float? = 10f) =
        GpsFix(t, speed, altitudeM = null, verticalAccuracyM = null, bearingDeg = bearing, bearingAccuracyDeg = accuracy)

    @Test
    fun `a moving fix gives its course`() {
        assertEquals(45f, HeadingEstimator().update(0, fix(0))!!, 0.001f)
    }

    @Test
    fun `eight km per h is the threshold`() {
        val h = HeadingEstimator()
        assertNull(h.update(0, fix(0, speed = 7.9f)))
        assertEquals(45f, h.update(1_000, fix(1_000, speed = 8f))!!, 0.001f)
    }

    @Test
    fun `below the threshold the last course is held for thirty seconds`() {
        val h = HeadingEstimator()
        h.update(0, fix(0, bearing = 90f))
        assertEquals(90f, h.update(30_000, fix(30_000, speed = 2f, bearing = 270f))!!, 0.001f)
        assertNull(h.update(30_001, fix(30_001, speed = 2f, bearing = 270f)))
    }

    @Test
    fun `an accuracy of forty five degrees is accepted, more is not`() {
        val h = HeadingEstimator()
        assertEquals(45f, h.update(0, fix(0, accuracy = 45f))!!, 0.001f)
        val other = HeadingEstimator()
        assertNull(other.update(0, fix(0, accuracy = 45.1f)))
    }

    @Test
    fun `a missing accuracy does not invalidate the course`() {
        assertEquals(45f, HeadingEstimator().update(0, fix(0, accuracy = null))!!, 0.001f)
    }

    @Test
    fun `a missing course or speed gives nothing new`() {
        val h = HeadingEstimator()
        assertNull(h.update(0, fix(0, bearing = null)))
        assertNull(h.update(1_000, fix(1_000, speed = null)))
        assertNull(h.update(2_000, null))
    }

    @Test
    fun `no fix holds the last course too`() {
        val h = HeadingEstimator()
        h.update(0, fix(0, bearing = 180f))
        assertEquals(180f, h.update(10_000, null)!!, 0.001f)
        assertNull(h.update(31_000, null))
    }

    @Test
    fun `the course is normalised to zero up to below three hundred sixty`() {
        assertEquals(0f, HeadingEstimator().update(0, fix(0, bearing = 360f))!!, 0.001f)
        assertEquals(350f, HeadingEstimator().update(0, fix(0, bearing = -10f))!!, 0.001f)
        assertEquals(10f, HeadingEstimator().update(0, fix(0, bearing = 370f))!!, 0.001f)
    }

    @Test
    fun `a non finite course is not a course`() {
        assertNull(HeadingEstimator().update(0, fix(0, bearing = Float.NaN)))
    }

    @Test
    fun `reset forgets the course`() {
        val h = HeadingEstimator()
        h.update(0, fix(0))
        h.reset()
        assertNull(h.update(1_000, null))
    }
}
