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

    // ── Gleiten zwischen zwei Fixen ───────────────────────────────────────────

    @Test
    fun `the pose glides from the first fix to the second`() {
        val a = MapPose(at(0.0, 0.0), 10.0)
        val b = MapPose(at(20.0, 10.0), 30.0)
        assertEquals(a.position.lat, interpolatePose(a, b, 0f).position.lat, 1e-12)
        assertEquals(b.position.lat, interpolatePose(a, b, 1f).position.lat, 1e-12)
        val mid = interpolatePose(a, b, 0.5f)
        assertEquals((a.position.lat + b.position.lat) / 2, mid.position.lat, 1e-9)
        assertEquals((a.position.lon + b.position.lon) / 2, mid.position.lon, 1e-9)
        assertEquals(20.0, mid.bearingDeg, 1e-9)
        assertEquals("a value outside 0..1 is clamped", b.position.lat, interpolatePose(a, b, 3f).position.lat, 1e-12)
        assertEquals(a.position.lat, interpolatePose(a, b, -1f).position.lat, 1e-12)
    }

    @Test
    fun `the bearing turns the short way over north`() {
        val a = MapPose(at(0.0, 0.0), 350.0)
        val b = MapPose(at(0.0, 0.0), 10.0)
        assertEquals(0.0, interpolatePose(a, b, 0.5f).bearingDeg, 1e-9)
        assertEquals(355.0, interpolatePose(a, b, 0.25f).bearingDeg, 1e-9)
        val back = interpolatePose(MapPose(at(0.0, 0.0), 10.0), MapPose(at(0.0, 0.0), 350.0), 0.5f)
        assertEquals(0.0, back.bearingDeg, 1e-9)
    }

    @Test
    fun `a pose across the date line takes the short way`() {
        val west = MapPose(TrackPoint(null, 0.0, 179.9999, null, null, null), 0.0)
        val east = MapPose(TrackPoint(null, 0.0, -179.9999, null, null, null), 0.0)
        val mid = interpolatePose(west, east, 0.5f).position.lon
        assertTrue("near the date line, not at 0 (was $mid)", kotlin.math.abs(mid) > 179.0)
    }

    // ── Karte ─────────────────────────────────────────────────────────────────

    @Test
    fun `a path ahead runs straight up from the position`() {
        val path = listOf(at(0.0, 0.0), at(100.0, 0.0), at(200.0, 0.0))
        val map = roadbookMap(path, path[0], bearingDeg = 0.0, apex = path[2], widthPx = 120f, heightPx = 100f)
        assertEquals(60f, map.position.x, 0.01f)
        assertTrue("the position stays in the lower half so the way ahead gets the larger part", map.position.y > 50f && map.position.y < 100f)
        for (p in map.line) assertEquals("straight ahead stays in the middle", 60f, p.x, 0.5f)
        assertTrue(map.line[1].y < map.line[0].y && map.line[2].y < map.line[1].y)
        assertNotNull(map.apex)
        assertEquals(map.line[2].y, map.apex!!.y, 0.01f)
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
        // Nahe Kurve: mindestens 50 m voraus und 20 m dahinter, Rand 12 Prozent der Hoehe (12 px) oben und unten: (100 - 24) / 70 = 1,0857 Bildpunkte je Meter.
        assertEquals(76.0 / 70.0, 1.0 / a.metresPerPx, 0.001)
        val far = listOf(at(0.0, 0.0), at(600.0, 0.0))
        val b = roadbookMap(far, far[0], 0.0, null, 120f, 100f)
        assertTrue(b.metresPerPx > a.metresPerPx)
        assertTrue("the farthest point is inside", b.line[1].y >= 0f)
    }

    @Test
    fun `the position is always inside the map with a margin, whatever the path`() {
        // (Weg, Position): die Position ist der erste Punkt, ausser beim fast erreichten Ziel (der Weg davor liegt hinter ihr).
        val cases = listOf(
            listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 300.0)) to 0, // Rechtskurve
            listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, -300.0)) to 0, // Linkskurve
            listOf(at(0.0, 0.0), at(40.0, 0.0), at(40.0, 40.0), at(0.0, 40.0), at(0.0, 400.0)) to 0, // Kehre
            listOf(at(0.0, 0.0), at(900.0, 0.0)) to 0, // sehr weit voraus
            listOf(at(-60.0, 0.0), at(0.0, 0.0), at(10.0, 0.0)) to 1, // fast am Ziel
            listOf(at(0.0, 0.0)) to 0, // nur die Position
        )
        for ((n, case) in cases.withIndex()) for (bearing in listOf(0.0, 90.0, 215.0)) {
            val (path, at) = case
            val map = roadbookMap(path, path[at], bearing, null, 144f, 88f)
            val pad = 0.12f * 88f
            assertTrue("case $n bearing $bearing: x ${map.position.x}", map.position.x in (pad - 0.01f)..(144f + 0.0f - pad + 0.01f))
            assertTrue("case $n bearing $bearing: y ${map.position.y}", map.position.y in (pad - 0.01f)..(88f - pad + 0.01f))
        }
    }

    @Test
    fun `a right turn puts the position to the left and a left turn to the right, and the way ahead gets the larger part`() {
        val rightTurn = listOf(at(0.0, 0.0), at(60.0, 0.0), at(60.0, 120.0))
        val r = roadbookMap(rightTurn, rightTurn[0], 0.0, rightTurn[1], 144f, 88f)
        assertTrue("left of the middle (${r.position.x})", r.position.x < 72f)
        assertTrue("the end of the path lies right of the position", r.line[2].x > r.position.x)
        assertTrue("the end of the path stays inside", r.line[2].x <= 144f)
        val leftTurn = listOf(at(0.0, 0.0), at(60.0, 0.0), at(60.0, -120.0))
        val l = roadbookMap(leftTurn, leftTurn[0], 0.0, leftTurn[1], 144f, 88f)
        assertTrue("right of the middle (${l.position.x})", l.position.x > 72f)
        assertTrue("the end of the path lies left of the position", l.line[2].x < l.position.x)
        assertTrue("more of the map lies ahead of the position than behind it", r.position.y > 88f / 2f)
        assertTrue("the turn is drawn bigger than with the old framing (0.51 px per metre in the old 72 dp map): (88 - 21.12) / 80 = 0.84", 1.0 / r.metresPerPx > 0.8)
    }

    @Test
    fun `a wide path is limited by the width`() {
        val wide = listOf(at(0.0, 0.0), at(50.0, 400.0))
        val map = roadbookMap(wide, wide[0], 0.0, null, 120f, 100f)
        assertTrue(map.line[1].x <= 120f + 0.5f)
    }
}
