package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nur erfundene Koordinaten (ein Rechteck bei 50 N, 10 E), nie echte Wege. */
class TrackSimplifyTest {

    private fun p(lat: Double, lon: Double) = TrackPoint(null, lat, lon, null, null, null)

    /** Eine Gerade von West nach Ost mit [n] Punkten. */
    private fun line(n: Int) = List(n) { p(50.0, 10.0 + it * 0.0001) }

    @Test
    fun `a track within the limit comes back unchanged`() {
        val points = line(5)
        assertSame(points, simplifyTrack(points, 5))
        assertSame(points, simplifyTrack(points, 100))
    }

    @Test
    fun `start and end always stay and the result has at most the limit`() {
        val points = line(500)
        val out = simplifyTrack(points, 20)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
        assertTrue(out.size <= 20)
    }

    @Test
    fun `the corner of an L is the point that stays`() {
        // West -> Ost bis zur Ecke, dann nach Norden: bei drei Punkten bleibt Anfang, Ecke, Ende
        val east = List(50) { p(50.0, 10.0 + it * 0.0001) }
        val north = List(50) { p(50.0 + (it + 1) * 0.0001, 10.0 + 49 * 0.0001) }
        val points = east + north
        val corner = east.last()
        val out = simplifyTrack(points, 3)
        assertEquals(listOf(points.first(), corner, points.last()), out)
    }

    @Test
    fun `the order of the points is kept`() {
        // eine Zickzack-Linie: Punkte der Ausgabe muessen in der Reihenfolge der Eingabe stehen
        val points = List(200) { p(50.0 + (if (it % 2 == 0) 0.0 else 0.0005), 10.0 + it * 0.0001) }
        val out = simplifyTrack(points, 30)
        val indexes = out.map { o -> points.indexOf(o) }
        assertEquals(indexes.sorted(), indexes)
        assertEquals(indexes.distinct(), indexes)
    }

    @Test
    fun `the biggest deviations are chosen first`() {
        // Gerade mit zwei Ausreissern: bei vier Punkten bleiben beide Ausreisser
        val points = line(100).toMutableList()
        points[30] = p(50.002, points[30].lon)
        points[70] = p(49.997, points[70].lon)
        val out = simplifyTrack(points, 4)
        assertEquals(listOf(points.first(), points[30], points[70], points.last()), out)
    }

    @Test
    fun `fewer than two points is not a valid limit`() {
        val failure = runCatching { simplifyTrack(line(10), 1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a limit of two keeps start and end`() {
        val points = line(10)
        assertEquals(listOf(points.first(), points.last()), simplifyTrack(points, 2))
    }

    @Test
    fun `identical points do not break it`() {
        val points = List(100) { p(50.0, 10.0) }
        val out = simplifyTrack(points, 10)
        assertTrue(out.size in 2..10)
        assertEquals(points.first(), out.first())
    }

    @Test
    fun `an empty or single point track comes back as it is`() {
        assertEquals(emptyList<TrackPoint>(), simplifyTrack(emptyList(), 10))
        val one = listOf(p(50.0, 10.0))
        assertEquals(one, simplifyTrack(one, 10))
    }

    @Test
    fun `a point beyond the end of a segment is measured to the end, not to the infinite line`() {
        // A -> C ist senkrecht (nach Norden); M1 liegt auf der Verlaengerung hinter C, M2 knapp neben dem Teilstueck.
        val a = p(50.000, 10.0)
        val m1 = p(50.005, 10.0)
        val m2 = p(50.001, 10.0005)
        val c = p(50.002, 10.0)
        // M1 liegt auf der Geraden (Abstand 0), aber weit hinter dem Ende: zum Teilstueck ist er der entfernteste Punkt.
        assertEquals(listOf(a, m1, c), simplifyTrack(listOf(a, m1, m2, c), 3))
    }
}
