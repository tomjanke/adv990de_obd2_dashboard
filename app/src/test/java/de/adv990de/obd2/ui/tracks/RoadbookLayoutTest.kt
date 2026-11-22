package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.cos

class RoadbookLayoutTest {

    private val metresPerDegLat = 111_195.0

    private fun at(northM: Double, eastM: Double) =
        TrackPoint(null, 50.0 + northM / metresPerDegLat, 10.0 + eastM / (metresPerDegLat * cos(Math.toRadians(50.0))), null, null, null)

    // ── Entfernung ────────────────────────────────────────────────────────────

    @Test
    fun `a distance rounds down to ten metres from 100 m and to five below`() {
        assertEquals("240 m", formatRoadbookDistance(240.0, Locale.GERMANY))
        assertEquals("240 m", formatRoadbookDistance(249.9, Locale.GERMANY))
        assertEquals("100 m", formatRoadbookDistance(100.0, Locale.GERMANY))
        assertEquals("95 m", formatRoadbookDistance(99.9, Locale.GERMANY))
        assertEquals("45 m", formatRoadbookDistance(47.0, Locale.GERMANY))
        assertEquals("0 m", formatRoadbookDistance(4.9, Locale.GERMANY))
        assertEquals("0 m", formatRoadbookDistance(0.0, Locale.GERMANY))
        assertEquals("0 m", formatRoadbookDistance(-30.0, Locale.GERMANY))
    }

    @Test
    fun `from a kilometre it has one decimal in the language of the device`() {
        assertEquals("1,2 km", formatRoadbookDistance(1_234.0, Locale.GERMANY))
        assertEquals("1.2 km", formatRoadbookDistance(1_234.0, Locale.US))
        assertEquals("1,0 km", formatRoadbookDistance(1_000.0, Locale.GERMANY))
        assertEquals("0 m".length, formatRoadbookDistance(0.0, Locale.US).length)
    }

    // ── Pfeil ─────────────────────────────────────────────────────────────────

    @Test
    fun `the arrow leg turns by the angle`() {
        val straight = arrowShape(0.0)
        assertEquals(0f, straight.legDx, 0.001f)
        assertEquals(-1f, straight.legDy, 0.001f)
        val right = arrowShape(90.0)
        assertEquals(1f, right.legDx, 0.001f)
        assertEquals(0f, right.legDy, 0.001f)
        val left = arrowShape(-90.0)
        assertEquals(-1f, left.legDx, 0.001f)
        val sharp = arrowShape(120.0)
        assertTrue("a sharp right points down and right", sharp.legDx > 0.8f && sharp.legDy > 0.4f)
        assertFalse(sharp.uTurn)
    }

    @Test
    fun `from 135 degrees the arrow is a u`() {
        assertFalse(arrowShape(134.9).uTurn)
        assertTrue(arrowShape(135.0).uTurn)
        assertTrue(arrowShape(-170.0).uTurn)
    }

    // ── Karte ─────────────────────────────────────────────────────────────────

    @Test
    fun `a path ahead runs straight up from the position`() {
        val path = listOf(at(0.0, 0.0), at(100.0, 0.0), at(200.0, 0.0))
        val map = roadbookMap(path, path[0], bearingDeg = 0.0, apex = path[2], widthPx = 120f, heightPx = 100f)
        assertEquals(60f, map.position.x, 0.01f)
        assertEquals(100f * ROADBOOK_MAP_ANCHOR_Y, map.position.y, 0.01f)
        for (p in map.line) assertEquals("straight ahead stays in the middle", 60f, p.x, 0.5f)
        assertTrue(map.line[1].y < map.line[0].y && map.line[2].y < map.line[1].y)
        assertNotNull(map.apex)
        assertEquals(map.line[2].y, map.apex!!.y, 0.01f)
    }

    @Test
    fun `the position stands at 85 percent of the height`() {
        val path = listOf(at(0.0, 0.0), at(100.0, 0.0))
        val map = roadbookMap(path, path[0], bearingDeg = 0.0, apex = null, widthPx = 120f, heightPx = 200f)
        assertEquals(170f, map.position.y, 0.01f)
        assertEquals(60f, map.position.x, 0.01f)
    }

    @Test
    fun `the course is up whatever its compass direction`() {
        // Ein Weg nach Osten mit Kurs 90 Grad zeigt in der Karte ebenfalls nach oben.
        val east = listOf(at(0.0, 0.0), at(0.0, 100.0), at(0.0, 200.0))
        val map = roadbookMap(east, east[0], bearingDeg = 90.0, apex = null, widthPx = 120f, heightPx = 100f)
        for (p in map.line) assertEquals(60f, p.x, 0.5f)
        assertTrue(map.line[2].y < map.line[0].y)
        assertNull(map.apex)
    }

    @Test
    fun `a turn to the right bends to the right of the screen`() {
        val path = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0))
        val map = roadbookMap(path, path[0], bearingDeg = 0.0, apex = path[1], widthPx = 120f, heightPx = 100f)
        assertTrue(map.line[2].x > map.line[1].x)
    }

    @Test
    fun `what lies behind the position is below it`() {
        val path = listOf(at(-50.0, 0.0), at(0.0, 0.0), at(150.0, 0.0))
        val map = roadbookMap(path, path[1], bearingDeg = 0.0, apex = null, widthPx = 120f, heightPx = 100f)
        assertTrue(map.line[0].y > map.position.y)
    }

    @Test
    fun `the scale fits the farthest point and never shows less than the minimum`() {
        val near = listOf(at(0.0, 0.0), at(10.0, 0.0))
        val a = roadbookMap(near, near[0], 0.0, null, 120f, 100f)
        assertEquals("a near turn still shows 120 m of way ahead", (100f * ROADBOOK_MAP_ANCHOR_Y / ROADBOOK_MAP_MIN_AHEAD_M).toDouble(), 1.0 / a.metresPerPx, 0.001)
        val far = listOf(at(0.0, 0.0), at(600.0, 0.0))
        val b = roadbookMap(far, far[0], 0.0, null, 120f, 100f)
        assertTrue(b.metresPerPx > a.metresPerPx)
        assertTrue("the farthest point is inside", b.line[1].y >= 0f)
    }

    @Test
    fun `a wide path is limited by the width`() {
        val wide = listOf(at(0.0, 0.0), at(50.0, 400.0))
        val map = roadbookMap(wide, wide[0], 0.0, null, 120f, 100f)
        assertTrue(map.line[1].x <= 120f + 0.5f)
    }
}
