package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Erfundene Koordinaten; die Erwartungen kommen aus der Geometrie (Kugelradius 6 371 008,8 m), nicht aus einer echten Strecke. */
class TrackGeometryTest {

    private fun p(lat: Double, lon: Double) = TrackPoint(timeMs = null, lat = lat, lon = lon, ele = null, accuracyM = null, speedKmh = null)

    @Test
    fun `one degree of longitude on the equator is 111,2 km within a tenth of a percent`() {
        val d = distanceM(p(0.0, 0.0), p(0.0, 1.0))
        assertEquals(111_195.0, d, 111_195.0 * 0.001)
    }

    @Test
    fun `one degree of latitude is about 111,2 km`() {
        assertEquals(111_195.0, distanceM(p(10.0, 5.0), p(11.0, 5.0)), 111_195.0 * 0.001)
    }

    @Test
    fun `one degree of longitude at 60 degrees north is half as long`() {
        assertEquals(111_195.0 / 2, distanceM(p(60.0, 0.0), p(60.0, 1.0)), 111_195.0 * 0.002)
    }

    @Test
    fun `distance is zero for the same point and symmetric`() {
        val a = p(48.0, 11.0)
        val b = p(48.1, 11.2)
        assertEquals(0.0, distanceM(a, a), 0.0)
        assertEquals(distanceM(a, b), distanceM(b, a), 1e-6)
    }

    @Test
    fun `distance across the antimeridian is the short way`() {
        assertEquals(111_195.0, distanceM(p(0.0, 179.5), p(0.0, -179.5)), 111_195.0 * 0.001)
    }

    @Test
    fun `distance to a polyline of no points is null`() {
        assertNull(distanceToPolylineM(p(0.0, 0.0), emptyList()))
    }

    @Test
    fun `distance to a polyline of one point is the distance to that point`() {
        val hit = distanceToPolylineM(p(0.0, 0.001), listOf(p(0.0, 0.0)))!!
        assertEquals(distanceM(p(0.0, 0.001), p(0.0, 0.0)), hit.distanceM, 1e-6)
        assertEquals(0, hit.segmentIndex)
        assertEquals(0.0, hit.fraction, 0.0)
    }

    @Test
    fun `foot point lies inside the segment`() {
        // Strecke 0,000 bis 0,002 Grad Laenge am Aequator, Punkt 0,001 Grad nach Norden daneben, in der Mitte
        val line = listOf(p(0.0, 0.0), p(0.0, 0.002))
        val hit = distanceToPolylineM(p(0.001, 0.001), line)!!
        assertEquals(111.195, hit.distanceM, 0.2)
        assertEquals(0, hit.segmentIndex)
        assertEquals(0.5, hit.fraction, 0.01)
    }

    @Test
    fun `point before the start is measured to the start and has fraction zero`() {
        val line = listOf(p(0.0, 0.0), p(0.0, 0.002))
        val hit = distanceToPolylineM(p(0.0, -0.001), line)!!
        assertEquals(111.195, hit.distanceM, 0.2)
        assertEquals(0, hit.segmentIndex)
        assertEquals(0.0, hit.fraction, 0.0)
    }

    @Test
    fun `point behind the end is measured to the end and has fraction one`() {
        val line = listOf(p(0.0, 0.0), p(0.0, 0.002))
        val hit = distanceToPolylineM(p(0.0, 0.003), line)!!
        assertEquals(111.195, hit.distanceM, 0.2)
        assertEquals(0, hit.segmentIndex)
        assertEquals(1.0, hit.fraction, 0.0)
    }

    @Test
    fun `the nearest of several segments wins`() {
        val line = listOf(p(0.0, 0.0), p(0.0, 0.002), p(0.002, 0.002), p(0.002, 0.004))
        val hit = distanceToPolylineM(p(0.0021, 0.003), line)!!
        assertEquals(2, hit.segmentIndex)
        assertEquals(0.5, hit.fraction, 0.01)
        assertTrue(hit.distanceM < 15.0)
    }

    @Test
    fun `a zero length segment does not divide by zero`() {
        val line = listOf(p(0.0, 0.0), p(0.0, 0.0), p(0.0, 0.002))
        val hit = distanceToPolylineM(p(0.001, 0.0), line)!!
        assertEquals(111.195, hit.distanceM, 0.2)
        assertTrue(hit.fraction in 0.0..1.0)
    }

    @Test
    fun `a point on the line has distance zero`() {
        val line = listOf(p(10.0, 10.0), p(10.0, 10.01))
        assertEquals(0.0, distanceToPolylineM(p(10.0, 10.005), line)!!.distanceM, 0.01)
    }

    @Test
    fun `the projection shortens longitude with the latitude`() {
        // 0,001 Grad Laenge bei 60 Grad Nord sind halb so viele Meter wie am Aequator
        val hit = distanceToPolylineM(p(60.0, 0.002), listOf(p(60.0, 0.0), p(60.0, 0.001)))!!
        assertEquals(111.195 / 2, hit.distanceM, 0.3)
    }

    @Test
    fun `with two equally near segments the first one wins`() {
        // der Punkt liegt gleich weit vom gemeinsamen Eckpunkt: Ende des ersten und Anfang des zweiten Teilstuecks
        val line = listOf(p(0.0, 0.0), p(0.0, 0.002), p(0.0, 0.004))
        val hit = distanceToPolylineM(p(0.001, 0.002), line)!!
        assertEquals(0, hit.segmentIndex)
        assertEquals(1.0, hit.fraction, 0.0)
    }
}
