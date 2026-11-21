package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der Plan eines GPX-Imports: Namen, Doppelte, Kennzahlen. Alle Koordinaten sind erfunden. */
class TrackImportPlanTest {

    private fun p(lat: Double, lon: Double, timeMs: Long? = null, ele: Float? = null) =
        TrackPoint(timeMs = timeMs, lat = lat, lon = lon, ele = ele, accuracyM = null, speedKmh = null)

    private fun line(n: Int, startLat: Double = 10.0, withTime: Boolean = false) =
        (0 until n).map { p(startLat + it * 0.001, 20.0, if (withTime) 1_000L * it else null) }

    private fun file(vararg tracks: GpxTrack, rejected: Int = 0, waypoints: Int = 0) =
        GpxFile(tracks.toList(), List(waypoints) { Waypoint(null, 1.0, 2.0, null) }, rejected)

    private val fallback: (Int) -> String = { "Fallback $it" }

    @Test
    fun `a track keeps its name trimmed and cut`() {
        val long = " " + "x".repeat(TRACK_NAME_MAX + 20) + " "
        val plan = planTrackImport(file(GpxTrack("  Alpine loop ", line(3)), GpxTrack(long, line(3, 11.0))), emptyList(), fallback)
        assertEquals("Alpine loop", plan.items[0].name)
        assertEquals("x".repeat(TRACK_NAME_MAX), plan.items[1].name)
    }

    @Test
    fun `a track without a name gets the fallback numbered among the tracks with points`() {
        val plan = planTrackImport(file(GpxTrack(null, emptyList()), GpxTrack(null, line(3)), GpxTrack("  ", line(3, 11.0))), emptyList(), fallback)
        assertEquals(listOf("Fallback 1", "Fallback 2"), plan.items.map { it.name })
    }

    @Test
    fun `an empty fallback name falls back to Track n`() {
        val plan = planTrackImport(file(GpxTrack(null, line(3))), emptyList()) { "" }
        assertEquals("Track 1", plan.items.single().name)
    }

    @Test
    fun `the figures come from the points`() {
        val points = listOf(p(10.0, 20.0, 5_000, 100f), p(10.001, 20.0, 9_000, 110f), p(10.002, 20.0, 12_000, 120f))
        val item = planTrackImport(file(GpxTrack("t", points)), emptyList(), fallback).items.single()
        assertEquals(3, item.pointCount)
        assertEquals(trackStats(points).distanceM.toLong(), item.distanceM)
        assertEquals(5_000L, item.startMs)
        assertEquals(12_000L, item.endMs)
    }

    @Test
    fun `a track without any time has no start and is flagged`() {
        val plan = planTrackImport(file(GpxTrack("planned", line(4)), GpxTrack("ridden", line(4, 11.0, withTime = true))), emptyList(), fallback)
        assertNull(plan.items[0].startMs)
        assertNull(plan.items[0].endMs)
        assertEquals(1, plan.withoutTime)
    }

    @Test
    fun `a track equal to an existing one is a duplicate and is not inserted`() {
        val points = line(5)
        val existing = trackFingerprint("Loop", points)!!
        val plan = planTrackImport(file(GpxTrack("Loop", points), GpxTrack("Other", line(5, 12.0))), listOf(existing), fallback)
        assertEquals(listOf(true, false), plan.items.map { it.duplicate })
        assertEquals(1, plan.duplicateTracks)
        assertEquals(listOf("Other"), plan.toInsert.map { it.name })
    }

    @Test
    fun `a different name point count first or last point is no duplicate`() {
        val points = line(5)
        val existing = trackFingerprint("Loop", points)!!
        fun dup(name: String, pts: List<TrackPoint>) = planTrackImport(file(GpxTrack(name, pts)), listOf(existing), fallback).items.single().duplicate
        assertTrue(dup("Loop", points))
        assertFalse(dup("loop", points))
        assertFalse(dup("Loop", points.dropLast(1)))
        assertFalse(dup("Loop", points.drop(1) + p(10.0, 20.0)))
        assertFalse(dup("Loop", listOf(p(50.0, 20.0)) + points.drop(1)))
        assertFalse(dup("Loop", points.dropLast(1) + p(10.004, 21.0)))
    }

    @Test
    fun `the same ends but another point count is no duplicate`() {
        val points = line(5)
        val existing = trackFingerprint("Loop", points)!!
        val withExtraMiddlePoint = points.take(2) + p(10.0015, 20.0001) + points.drop(2)

        val plan = planTrackImport(file(GpxTrack("Loop", withExtraMiddlePoint)), listOf(existing), fallback)

        assertFalse(plan.items.single().duplicate)
    }

    @Test
    fun `a duplicate without times is not counted as a new track without times`() {
        val points = line(4)
        val existing = trackFingerprint("Old", points)!!

        val plan = planTrackImport(file(GpxTrack("Old", points), GpxTrack("New", line(4, 11.0, withTime = true))), listOf(existing), fallback)

        assertEquals(0, plan.withoutTime)
    }

    @Test
    fun `the rounding of a GPX file does not hide a duplicate`() {
        val stored = (0 until 4).map { p(48.1234567891 + it * 0.001, 11.9876543219) }
        val roundedByTheFile = stored.map { it.copy(lat = Math.round(it.lat * 1e7) / 1e7, lon = Math.round(it.lon * 1e7) / 1e7) }
        val plan = planTrackImport(file(GpxTrack("Loop", roundedByTheFile)), listOf(trackFingerprint("Loop", stored)!!), fallback)
        assertTrue(plan.items.single().duplicate)
    }

    @Test
    fun `a second equal track of the same file is a duplicate of the first`() {
        val points = line(5)
        val plan = planTrackImport(file(GpxTrack("Loop", points), GpxTrack("Loop", points)), emptyList(), fallback)
        assertEquals(listOf(false, true), plan.items.map { it.duplicate })
    }

    @Test
    fun `a nameless track is compared by its fallback name`() {
        val points = line(5)
        val existing = trackFingerprint("Fallback 1", points)!!
        val plan = planTrackImport(file(GpxTrack(null, points)), listOf(existing), fallback)
        assertTrue(plan.items.single().duplicate)
    }

    @Test
    fun `the counters of the file are carried over`() {
        val plan = planTrackImport(file(GpxTrack("a", line(3)), rejected = 7, waypoints = 4), emptyList(), fallback)
        assertEquals(7, plan.rejectedPoints)
        assertEquals(4, plan.ignoredWaypoints)
    }

    @Test
    fun `a file without any track with points has nothing to insert`() {
        val plan = planTrackImport(file(GpxTrack("empty", emptyList())), emptyList(), fallback)
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun `candidate keys are the name and the point count of every track with points`() {
        val keys = candidateKeys(file(GpxTrack("a", line(3)), GpxTrack(null, line(5, 11.0)), GpxTrack("e", emptyList())), fallback)
        assertEquals(listOf("a" to 3, "Fallback 2" to 5), keys)
    }

    @Test
    fun `only the new tracks can be chosen and the duplicates are skipped in the indices`() {
        val points = line(4)
        val existing = trackFingerprint("Old", points)!!
        val plan = planTrackImport(file(GpxTrack("A", line(3)), GpxTrack("Old", points), GpxTrack("B", line(3, 12.0))), listOf(existing), fallback)

        assertEquals(listOf(0, 2), plan.newIndices)
    }

    @Test
    fun `without removes the chosen new tracks and keeps the duplicates and the counters`() {
        val points = line(4)
        val existing = trackFingerprint("Old", points)!!
        val plan = planTrackImport(
            file(GpxTrack("A", line(3)), GpxTrack("Old", points), GpxTrack("B", line(3, 12.0)), GpxTrack("C", line(3, 13.0)), rejected = 5, waypoints = 2),
            listOf(existing),
            fallback,
        )

        val reduced = plan.without(setOf(0, 3))

        assertEquals(listOf("B"), reduced.toInsert.map { it.name })
        assertEquals("the duplicate stays counted", 1, reduced.duplicateTracks)
        assertEquals(5, reduced.rejectedPoints)
        assertEquals(2, reduced.ignoredWaypoints)
    }

    @Test
    fun `without an empty set is the same plan`() {
        val plan = planTrackImport(file(GpxTrack("A", line(3))), emptyList(), fallback)

        assertTrue(plan.without(emptySet()) === plan)
    }

    @Test
    fun `without never removes a duplicate and ignores positions that do not exist`() {
        val points = line(4)
        val existing = trackFingerprint("Old", points)!!
        val plan = planTrackImport(file(GpxTrack("Old", points), GpxTrack("A", line(3))), listOf(existing), fallback)

        val reduced = plan.without(setOf(0, 17))

        assertEquals(2, reduced.items.size)
        assertEquals(1, reduced.duplicateTracks)
        assertEquals(listOf("A"), reduced.toInsert.map { it.name })
    }

    @Test
    fun `an empty track has no fingerprint`() {
        assertNull(trackFingerprint("x", emptyList()))
    }
}
