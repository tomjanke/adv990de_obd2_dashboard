package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackStatsTest {

    private fun p(lat: Double, lon: Double, ele: Float? = null, timeMs: Long? = null) =
        TrackPoint(timeMs = timeMs, lat = lat, lon = lon, ele = ele, accuracyM = null, speedKmh = null)

    @Test
    fun `an empty track has no distance duration or bounds`() {
        val s = trackStats(emptyList())
        assertEquals(0.0, s.distanceM, 0.0)
        assertNull(s.durationMs)
        assertNull(s.bounds)
        assertNull(s.ascentM)
        assertNull(s.descentM)
    }

    @Test
    fun `one point has bounds but no distance`() {
        val s = trackStats(listOf(p(48.0, 11.0)))
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(TrackBounds(48.0, 48.0, 11.0, 11.0), s.bounds)
    }

    @Test
    fun `distance is the sum of the segments`() {
        val s = trackStats(listOf(p(0.0, 0.0), p(0.0, 0.001), p(0.001, 0.001)))
        assertEquals(2 * 111.195, s.distanceM, 0.3)
    }

    @Test
    fun `bounds cover all points`() {
        val s = trackStats(listOf(p(48.0, 11.5), p(47.5, 12.0), p(48.2, 11.0)))
        assertEquals(TrackBounds(minLat = 47.5, maxLat = 48.2, minLon = 11.0, maxLon = 12.0), s.bounds)
    }

    @Test
    fun `duration is the span between the first and last time`() {
        val s = trackStats(listOf(p(0.0, 0.0, timeMs = 1_000L), p(0.0, 0.1), p(0.0, 0.2, timeMs = 61_000L)))
        assertEquals(60_000L, s.durationMs)
    }

    @Test
    fun `without two times there is no duration`() {
        assertNull(trackStats(listOf(p(0.0, 0.0), p(0.0, 0.1))).durationMs)
        assertNull(trackStats(listOf(p(0.0, 0.0, timeMs = 5L), p(0.0, 0.1))).durationMs)
    }

    @Test
    fun `a clock that jumps backwards does not give a negative duration`() {
        val s = trackStats(listOf(p(0.0, 0.0, timeMs = 100_000L), p(0.0, 0.1, timeMs = 40_000L)))
        assertEquals(60_000L, s.durationMs)
    }

    @Test
    fun `without elevations ascent and descent are unknown not zero`() {
        val s = trackStats(listOf(p(0.0, 0.0), p(0.0, 0.1)))
        assertNull(s.ascentM)
        assertNull(s.descentM)
    }

    @Test
    fun `a single elevation gives no ascent`() {
        assertNull(trackStats(listOf(p(0.0, 0.0, ele = 100f), p(0.0, 0.1))).ascentM)
    }

    @Test
    fun `noise around a level does not count`() {
        val noise = listOf(100f, 101f, 99f, 100.5f, 99.5f, 100f, 101f, 99f, 100f, 100.5f, 99.5f, 100f)
        val s = trackStats(noise.mapIndexed { i, e -> p(0.0, i * 0.0001, ele = e) })
        assertEquals(0.0, s.ascentM!!, 0.0)
        assertEquals(0.0, s.descentM!!, 0.0)
    }

    @Test
    fun `a climb counts as ascent and not as descent`() {
        val s = trackStats((0..20).map { p(0.0, it * 0.0001, ele = 100f + it * 5f) })
        assertEquals(100.0, s.ascentM!!, 8.0)
        assertEquals(0.0, s.descentM!!, 0.0)
    }

    @Test
    fun `a descent counts as descent and not as ascent`() {
        val s = trackStats((0..20).map { p(0.0, it * 0.0001, ele = 300f - it * 5f) })
        assertEquals(100.0, s.descentM!!, 8.0)
        assertEquals(0.0, s.ascentM!!, 0.0)
    }

    @Test
    fun `up and down are counted separately`() {
        val up = (0..10).map { 100f + it * 5f }
        val down = (1..10).map { 150f - it * 5f }
        val s = trackStats((up + down).mapIndexed { i, e -> p(0.0, i * 0.0001, ele = e) })
        assertEquals(50.0, s.ascentM!!, 8.0)
        assertEquals(50.0, s.descentM!!, 8.0)
    }

    @Test
    fun `points without an elevation are skipped for the elevation`() {
        val pts = (0..20).map { p(0.0, it * 0.0001, ele = if (it % 2 == 0) 100f + it * 5f else null) }
        val s = trackStats(pts)
        assertNotNull(s.ascentM)
        assertEquals(100.0, s.ascentM!!, 12.0)
    }

    @Test
    fun `a single spike is smoothed away`() {
        val e = MutableList(15) { 100f }
        e[7] = 180f
        val s = trackStats(e.mapIndexed { i, v -> p(0.0, i * 0.0001, ele = v) })
        // ein Ausreisser von 80 m ueber 5 Punkte geglaettet hoechstens +16 m und zurueck
        assertEquals(true, s.ascentM!! <= 20.0)
    }

    @Test
    fun `a slow drift below 3 m in total is not counted`() {
        val s = trackStats((0..20).map { p(0.0, it * 0.0001, ele = 100f + it * 0.1f) })
        assertEquals(0.0, s.ascentM!!, 0.0)
    }

    @Test
    fun `a step of exactly 3 m is counted`() {
        val s = trackStats(listOf(100f, 100f, 100f, 103f, 103f, 103f).mapIndexed { i, e -> p(0.0, i * 0.0001, ele = e) })
        assertEquals(3.0, s.ascentM!!, 0.0)
    }
}
