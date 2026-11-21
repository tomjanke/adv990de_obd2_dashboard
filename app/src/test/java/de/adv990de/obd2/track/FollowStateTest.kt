package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nachfahren: Abstand, Strecke, Rest und Richtung auf einem Track. Alle Koordinaten sind erfunden (um 50 N, 10 E). */
class FollowStateTest {

    /** Ein Grad Breite sind rund 111 195 m. */
    private val metresPerDegLat = 111_195.0

    private fun p(lat: Double, lon: Double) = TrackPoint(null, lat, lon, null, null, null)

    /** Ein Punkt [northM] Meter nördlich von 50 N und [eastM] Meter östlich von 10 E. */
    private fun at(northM: Double, eastM: Double = 0.0) =
        p(50.0 + northM / metresPerDegLat, 10.0 + eastM / (metresPerDegLat * Math.cos(Math.toRadians(50.0))))

    /** Ein gerader Track nach Norden: ein Punkt alle [stepM] Meter, [count] Punkte. */
    private fun north(count: Int, stepM: Double = 100.0) = List(count) { at(it * stepM) }

    private fun tracker(points: List<TrackPoint>, direction: FollowDirection = FollowDirection.FORWARD) =
        FollowTracker(FollowLine.of(points, direction))

    // ── Linie ─────────────────────────────────────────────────────────────────

    @Test
    fun `the length of the line is the sum of its parts`() {
        val line = FollowLine.of(north(11), FollowDirection.FORWARD)
        assertEquals(1_000.0, line.totalM, 1.0)
        assertEquals(11, line.cumulativeM.size)
        assertEquals(0.0, line.cumulativeM[0], 0.0)
        assertEquals(500.0, line.cumulativeM[5], 1.0)
    }

    @Test
    fun `the reverse line runs from the end to the start with the same length`() {
        val points = north(11)
        val reverse = FollowLine.of(points, FollowDirection.REVERSE)
        assertEquals(points.last(), reverse.points.first())
        assertEquals(points.first(), reverse.points.last())
        assertEquals(FollowLine.of(points, FollowDirection.FORWARD).totalM, reverse.totalM, 0.001)
    }

    @Test
    fun `a line without two different points cannot be followed`() {
        assertFalse(FollowLine.of(emptyList(), FollowDirection.FORWARD).canFollow)
        assertFalse(FollowLine.of(listOf(at(0.0)), FollowDirection.FORWARD).canFollow)
        assertFalse(FollowLine.of(listOf(at(0.0), at(0.0)), FollowDirection.FORWARD).canFollow)
        assertTrue(FollowLine.of(north(2), FollowDirection.FORWARD).canFollow)
    }

    @Test
    fun `a tracker on a line that cannot be followed answers nothing`() {
        assertNull(tracker(listOf(at(0.0))).update(at(0.0)))
        assertNull(tracker(emptyList()).update(at(0.0)))
    }

    // ── Abstand, Strecke, Rest ────────────────────────────────────────────────

    @Test
    fun `a position on the track has no distance and the right progress`() {
        val r = tracker(north(11)).update(at(250.0))!!
        assertEquals(0.0, r.distanceToTrackM, 1.0)
        assertEquals(250.0, r.alongM, 2.0)
        assertEquals(750.0, r.remainingM, 2.0)
        assertEquals(1_000.0, r.totalM, 1.0)
        assertEquals(0.25, r.progress, 0.005)
    }

    @Test
    fun `a position beside the track is its distance away`() {
        val r = tracker(north(11)).update(at(400.0, eastM = 50.0))!!
        assertEquals(50.0, r.distanceToTrackM, 1.0)
        assertEquals(400.0, r.alongM, 2.0)
    }

    @Test
    fun `before the start and after the end the progress stops at the ends`() {
        val line = north(11)
        val before = tracker(line).update(at(-300.0))!!
        assertEquals(0.0, before.alongM, 0.5)
        assertEquals(0.0, before.progress, 0.0)
        assertEquals(300.0, before.distanceToTrackM, 2.0)

        val after = tracker(line).update(at(1_200.0))!!
        assertEquals(1_000.0, after.alongM, 1.0)
        assertEquals(1.0, after.progress, 0.0)
        assertEquals(0.0, after.remainingM, 1.0)
    }

    @Test
    fun `the progress of the reverse direction counts from the other end`() {
        val r = tracker(north(11), FollowDirection.REVERSE).update(at(250.0))!!
        assertEquals(750.0, r.alongM, 2.0)
        assertEquals(250.0, r.remainingM, 2.0)
    }

    // ── Richtung ──────────────────────────────────────────────────────────────

    @Test
    fun `the bearing is the direction of the part`() {
        assertEquals(0.0, tracker(north(11)).update(at(250.0))!!.bearingDeg, 1.0)
        assertEquals(180.0, tracker(north(11), FollowDirection.REVERSE).update(at(250.0))!!.bearingDeg, 1.0)
        val east = List(11) { at(0.0, it * 100.0) }
        assertEquals(90.0, tracker(east).update(at(0.0, 250.0))!!.bearingDeg, 1.0)
    }

    @Test
    fun `the bearing skips parts of length zero`() {
        val line = listOf(at(0.0), at(100.0), at(100.0), at(100.0), at(200.0))
        val r = tracker(line).update(at(100.0, eastM = 1.0))!!
        assertEquals(0.0, r.bearingDeg, 1.0)
    }

    // ── Fenster: Rückweg, Schleife, Rauschen ──────────────────────────────────

    /** Hin 1000 m nach Norden und über dieselben Punkte zurück. */
    private fun outAndBack(): List<TrackPoint> = north(11) + north(11).reversed().drop(1)

    @Test
    fun `on the way back along the same road the progress does not jump to the way out`() {
        val t = tracker(outAndBack())
        for (m in listOf(0.0, 200.0, 400.0, 600.0, 800.0, 1_000.0)) t.update(at(m))
        val back = t.update(at(800.0))!!
        assertEquals("1000 m out plus 200 m back", 1_200.0, back.alongM, 5.0)
        val further = t.update(at(300.0))!!
        assertEquals(1_700.0, further.alongM, 5.0)
        assertEquals(300.0, further.remainingM, 5.0)
    }

    @Test
    fun `at the end of a loop the progress is the whole way and not zero`() {
        // eine Raute: Start = Ziel
        val loop = listOf(at(0.0), at(500.0), at(500.0, 500.0), at(0.0, 500.0), at(0.0))
        val t = tracker(loop)
        listOf(at(0.0), at(300.0), at(500.0), at(500.0, 300.0), at(500.0, 500.0), at(300.0, 500.0), at(0.0, 500.0), at(0.0, 300.0)).forEach { t.update(it) }
        val end = t.update(at(0.0, 20.0))!!
        assertTrue("progress ${end.progress}", end.progress > 0.95)
    }

    @Test
    fun `a small step back from noise does not lower the progress`() {
        val t = tracker(north(21))
        t.update(at(500.0))
        val jitter = t.update(at(470.0))!!
        assertEquals(500.0, jitter.alongM, 2.0)
        assertEquals(0.0, jitter.distanceToTrackM, 1.0)
    }

    @Test
    fun `a real step back lowers the progress`() {
        val t = tracker(north(21))
        t.update(at(500.0))
        val back = t.update(at(400.0))!!
        assertEquals(400.0, back.alongM, 2.0)
    }

    @Test
    fun `a jump far ahead is found again by the search over the whole line`() {
        val t = tracker(north(101))
        t.update(at(500.0))
        val far = t.update(at(6_000.0))!!
        assertEquals(6_000.0, far.alongM, 5.0)
    }

    @Test
    fun `a position far from the track keeps the progress where it was`() {
        val t = tracker(north(21))
        t.update(at(500.0))
        val away = t.update(at(520.0, eastM = 3_000.0))!!
        assertEquals(500.0, away.alongM, 30.0)
        assertTrue(away.distanceToTrackM > 2_900.0)
    }

    @Test
    fun `the first position finds its place on the whole line`() {
        val r = tracker(north(101)).update(at(7_300.0))!!
        assertEquals(7_300.0, r.alongM, 5.0)
    }

    @Test
    fun `a longer line does not change the answer for the same position`() {
        val short = tracker(north(11)).update(at(250.0, eastM = 20.0))!!
        val long = tracker(north(11) + north(11).map { at(1_000.0 + 100.0) }).update(at(250.0, eastM = 20.0))
        assertNotNull(long)
        assertEquals(short.distanceToTrackM, long!!.distanceToTrackM, 0.5)
        assertEquals(short.alongM, long.alongM, 0.5)
    }

    @Test
    fun `the first position takes the nearest place on the whole line even when a nearer window exists`() {
        // Hin nach Norden, nach Osten, zurueck nach Sueden und auf Hoehe 100 m nach Westen: die Strecke kommt bei (100 m N, 60 m O) zweimal vorbei, einmal 60 m weg, spaeter genau.
        val line = listOf(at(0.0), at(1_000.0), at(1_000.0, 1_500.0), at(100.0, 1_500.0), at(100.0, 0.0))
        val r = tracker(line).update(at(100.0, eastM = 60.0))!!
        assertEquals(0.0, r.distanceToTrackM, 1.0)
        assertEquals("the late pass, not the first 60 m away", 4_840.0, r.alongM, 10.0)
    }
}
