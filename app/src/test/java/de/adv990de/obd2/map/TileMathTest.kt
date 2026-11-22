package de.adv990de.obd2.map

import de.adv990de.obd2.ui.tracks.ScreenPoint
import de.adv990de.obd2.ui.tracks.TrackView
import de.adv990de.obd2.ui.tracks.projectLat
import de.adv990de.obd2.ui.tracks.projectLon
import de.adv990de.obd2.ui.tracks.toScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Kachel-Mathematik gegen die Ebene der Track-Zeichnung. Alle Orte sind bekannt oder erfunden; die Gegenrechnungen nutzen andere Formeln als der Code. */
class TileMathTest {

    private val w = 800.0
    private val h = 600.0

    // ── Kachel eines Ortes ────────────────────────────────────────────────────

    @Test
    fun `the tile of a place agrees with the textbook formula`() {
        val places = listOf(52.52 to 13.405, -33.8688 to 151.2093, 0.0001 to -0.0001, 64.1466 to -21.9426, 40.7128 to -74.006)
        for ((lat, lon) in places) for (z in listOf(0, 1, 5, 10, 14, 17)) {
            val n = 2.0.pow(z)
            val x = floorInt((lon + 180) / 360 * n)
            val latRad = Math.toRadians(lat)
            val y = floorInt((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n)
            assertEquals("$lat/$lon z$z", TileKey(z, x, y), tileContaining(lat, lon, z))
        }
        assertEquals(TileKey(10, 550, 335), tileContaining(52.52, 13.405, 10)) // Berlin, Stufe 10
    }

    private fun floorInt(d: Double) = kotlin.math.floor(d).toInt()

    @Test
    fun `places beyond the grid are clamped to its edge`() {
        assertEquals(TileKey(3, 0, 0), tileContaining(89.0, -200.0, 3))
        assertEquals(TileKey(3, 7, 7), tileContaining(-89.0, 200.0, 3))
        assertEquals(TileKey(3, 0, 0), tileContaining(Double.NaN, Double.NaN, 3))
    }

    @Test
    fun `tile edges`() {
        assertEquals(-180.0, tileWestDeg(0, 3), 0.0)
        assertEquals(0.0, tileWestDeg(4, 3), 0.0)
        assertEquals(135.0, tileWestDeg(7, 3), 0.0)
        assertEquals(PI, tileNorthY(0, 4), 1e-12)
        assertEquals(0.0, tileNorthY(8, 4), 1e-12)
        assertEquals(2 * PI / 8, tileSizeUnits(3), 1e-12)
    }

    // ── Stufe aus dem Massstab ────────────────────────────────────────────────

    @Test
    fun `the zoom level makes a tile about the target size`() {
        for (z in 3..14) {
            // Massstab, bei dem eine Kachel der Stufe z genau 1,5 mal die Zielgroesse hat -> Stufe z.
            val ppu = TILE_TARGET_PX * 1.5 / tileSizeUnits(z)
            assertEquals(z, zoomFor(ppu, 0, 20))
            val tilePx = ppu * tileSizeUnits(zoomFor(ppu, 0, 20))
            assertTrue("$tilePx", tilePx >= TILE_TARGET_PX && tilePx < 2 * TILE_TARGET_PX)
        }
    }

    @Test
    fun `the zoom level is clamped to the range of the source and never fails`() {
        assertEquals(15, zoomFor(1e12, 4, 15))
        assertEquals(4, zoomFor(1.0, 4, 15))
        assertEquals(4, zoomFor(0.0, 4, 15))
        assertEquals(4, zoomFor(-5.0, 4, 15))
        assertEquals(4, zoomFor(Double.NaN, 4, 15))
        assertEquals(4, zoomFor(Double.POSITIVE_INFINITY, 4, 15))
        assertEquals(7, zoomFor(1e12, 7, 6)) // unsinnige Spanne
    }

    @Test
    fun `the zoom level never falls when the scale grows`() {
        var last = -1
        var ppu = 1.0
        while (ppu < 1e9) { val z = zoomFor(ppu, 0, 22); assertTrue(z >= last); last = z; ppu *= 1.3 }
    }

    // ── Abbild gegen toScreen ─────────────────────────────────────────────────

    private fun views() = listOf(
        TrackView(0.0, 0.0, 1.0e6), TrackView(0.001, 0.9, 5.0e6, rotationDeg = 37.0), TrackView(0.0005, 0.95, 2.0e7, rotationDeg = 90.0, anchorY = 0.65),
        TrackView(0.002, 0.91, 3.0e7, rotationDeg = 180.0, anchorY = 0.8), TrackView(0.0, 0.9, 1.0e5, rotationDeg = 315.5, anchorY = 0.3),
    )

    @Test
    fun `the affine map of a tile equals toScreen of the plane coordinates`() {
        val ref = 10.0
        val rnd = Random(7)
        for (view in views()) for (key in listOf(TileKey(14, 8800, 5400), TileKey(10, 550, 335), TileKey(3, 4, 2))) for (extent in listOf(256, 4096)) {
            val m = tileToScreen(view, w, h, ref, key, extent)
            repeat(20) {
                val u = rnd.nextDouble() * extent
                val v = rnd.nextDouble() * extent
                // Gegenrechnung: Lage der Kachelecke aus der Breite/Laenge ueber projectLat/projectLon, dann toScreen.
                val size = tileSizeUnits(key.z)
                val west = Math.toRadians(tileWestDeg(key.x, key.z) - ref)
                val north = projectLat(Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(tileNorthY(key.y, key.z)))))
                val px = west + u / extent * size
                val py = north - v / extent * size
                val expected = toScreen(view, w, h, px, py)
                val got = m.map(u, v)
                assertEquals("$view $key u=$u", expected.x, got.x, 1e-3)
                assertEquals("$view $key v=$v", expected.y, got.y, 1e-3)
            }
        }
    }

    @Test
    fun `the plane of a tile agrees with projectLon and projectLat`() {
        // Die westliche Kante der Kachel in der Ebene ist projectLon der Laenge der Kante; die noerdliche der y-Wert von projectLat der Breite der Kante.
        val key = TileKey(12, 2200, 1350)
        val ref = tileWestDeg(2200, 12)
        val northLat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(tileNorthY(1350, 12))))
        val view = TrackView(projectLon(tileWestDeg(2201, 12), ref), projectLat(northLat), 1.0)
        val m = tileToScreen(view, w, h, ref, key, 1)
        // Die nordwestliche Ecke (0, 0) liegt einen Kachelbreite links der Ansichtsmitte und auf deren Hoehe.
        val corner = m.map(0.0, 0.0)
        assertEquals(w / 2 - tileSizeUnits(12), corner.x, 1e-6)
        assertEquals(h / 2, corner.y, 1e-6)
    }

    @Test
    fun `the centre of the view is the centre of its tile`() {
        val key = TileKey(13, 4400, 2700)
        val ref = 8.0
        val lonC = tileWestDeg(4400, 13) + 360.0 / 2.0.pow(13) / 2
        val yC = tileNorthY(2700, 13) - tileSizeUnits(13) / 2
        for (rot in listOf(0.0, 37.0, 90.0, 200.0)) {
            val view = TrackView(Math.toRadians(lonC - ref), yC, 3.0e6, rotationDeg = rot, anchorY = 0.6)
            val p = tileToScreen(view, w, h, ref, key, 4096).map(2048.0, 2048.0)
            assertEquals("rot $rot", w / 2, p.x, 1e-6)
            assertEquals("rot $rot", h * 0.6, p.y, 1e-6)
        }
    }

    @Test
    fun `a tile is drawn at the size of the scale and turned by the rotation`() {
        val key = TileKey(10, 550, 335)
        val view = TrackView(0.0, 0.0, 1.0e6, rotationDeg = 90.0)
        val m = tileToScreen(view, w, h, 13.0, key, 100)
        val o = m.map(0.0, 0.0)
        val ex = m.map(100.0, 0.0) // Kante nach Osten
        val len = Math.hypot(ex.x - o.x, ex.y - o.y)
        assertEquals(1.0e6 * tileSizeUnits(10), len, 1e-3)
        // Bei 90 Grad steht Osten oben: die Ostkante zeigt nach oben (y wird kleiner), nicht seitwaerts.
        assertEquals(0.0, ex.x - o.x, 1e-3)
        assertTrue(ex.y < o.y)
    }

    @Test
    fun `toPlane inverts toScreen`() {
        val rnd = Random(3)
        for (view in views()) repeat(20) {
            val x = rnd.nextDouble() * 0.01
            val y = 0.9 + rnd.nextDouble() * 0.01
            val s = toScreen(view, w, h, x, y)
            val (px, py) = toPlane(view, w, h, s.x, s.y)
            assertEquals(x, px, 1e-9)
            assertEquals(y, py, 1e-9)
        }
    }

    // ── Sichtbare Kacheln ─────────────────────────────────────────────────────

    /** Gegenprobe: ein Polygon gegen ein Rechteck mit Sutherland-Hodgman (andere Methode als die Trennachsen im Code); `true`, wenn der Schnitt eine Flaeche hat. */
    private fun clipArea(poly: List<ScreenPoint>, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        var p = poly
        fun clip(inside: (ScreenPoint) -> Boolean, cut: (ScreenPoint, ScreenPoint) -> ScreenPoint) {
            val out = ArrayList<ScreenPoint>()
            for (i in p.indices) {
                val a = p[i]; val b = p[(i + 1) % p.size]
                if (inside(b)) { if (!inside(a)) out.add(cut(a, b)); out.add(b) } else if (inside(a)) out.add(cut(a, b))
            }
            p = out
        }
        fun cutX(x: Double) = { a: ScreenPoint, b: ScreenPoint -> val t = (x - a.x) / (b.x - a.x); ScreenPoint(x, a.y + t * (b.y - a.y)) }
        fun cutY(y: Double) = { a: ScreenPoint, b: ScreenPoint -> val t = (y - a.y) / (b.y - a.y); ScreenPoint(a.x + t * (b.x - a.x), y) }
        clip({ it.x >= x0 }, cutX(x0)); if (p.isEmpty()) return 0.0
        clip({ it.x <= x1 }, cutX(x1)); if (p.isEmpty()) return 0.0
        clip({ it.y >= y0 }, cutY(y0)); if (p.isEmpty()) return 0.0
        clip({ it.y <= y1 }, cutY(y1)); if (p.isEmpty()) return 0.0
        var area = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; area += a.x * b.y - b.x * a.y }
        return abs(area) / 2
    }

    private fun bruteForce(view: TrackView, ref: Double, z: Int, margin: Double): Set<TileKey> {
        val n = 1 shl z
        val result = HashSet<TileKey>()
        for (x in 0 until n) for (y in 0 until n) {
            val m = tileToScreen(view, w, h, ref, TileKey(z, x, y), 1)
            val poly = listOf(m.map(0.0, 0.0), m.map(1.0, 0.0), m.map(1.0, 1.0), m.map(0.0, 1.0))
            if (clipArea(poly, -margin, -margin, w + margin, h + margin) > 1e-6) result.add(TileKey(z, x, y))
        }
        return result
    }

    private fun viewAt(lat: Double, lon: Double, ref: Double, z: Int, tilePx: Double, rot: Double, anchor: Double = 0.5): TrackView =
        TrackView(Math.toRadians(lon - ref), projectLat(lat), tilePx / tileSizeUnits(z), rot, anchor)

    @Test
    fun `the visible tiles are exactly the tiles that touch the surface, in every rotation`() {
        val ref = 13.0
        for (rot in listOf(0.0, 90.0, 37.0, 45.0, 180.0, 291.0)) for (anchor in listOf(0.5, 0.65)) for (tilePx in listOf(300.0, 520.0, 700.0)) {
            val z = 9
            val view = viewAt(52.52, 13.405, ref, z, tilePx, rot, anchor)
            val got = visibleTiles(view, w, h, ref, z, maxTiles = 1000).toSet()
            assertEquals("rot=$rot anchor=$anchor px=$tilePx", bruteForce(view, ref, z, 0.0), got)
        }
    }

    @Test
    fun `the margin adds the tiles around`() {
        val ref = 13.0
        val view = viewAt(52.52, 13.405, ref, 9, 400.0, 37.0)
        val plain = visibleTiles(view, w, h, ref, 9, maxTiles = 1000).toSet()
        val withMargin = visibleTiles(view, w, h, ref, 9, marginPx = 200.0, maxTiles = 1000).toSet()
        assertTrue(withMargin.containsAll(plain))
        assertTrue(withMargin.size > plain.size)
        assertEquals(bruteForce(view, ref, 9, 200.0), withMargin)
    }

    @Test
    fun `every point of the surface lies in a returned tile`() {
        val ref = 13.0
        for (rot in listOf(0.0, 37.0, 90.0, 225.0)) {
            val z = 11
            val view = viewAt(52.52, 13.405, ref, z, 450.0, rot)
            val tiles = visibleTiles(view, w, h, ref, z, maxTiles = 1000).toSet()
            for (gx in 0..20) for (gy in 0..20) {
                val (px, py) = toPlane(view, w, h, gx / 20.0 * w, gy / 20.0 * h)
                val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(py)))
                val lon = ref + Math.toDegrees(px)
                assertTrue("rot=$rot ($gx,$gy)", tileContaining(lat, lon, z) in tiles)
            }
        }
    }

    private fun diamond(cx: Double, cy: Double, r: Double) = listOf(ScreenPoint(cx, cy - r), ScreenPoint(cx + r, cy), ScreenPoint(cx, cy + r), ScreenPoint(cx - r, cy))

    @Test
    fun `a turned square beside a corner of the rectangle does not touch it although its bounding box does`() {
        // Das Rechteck ist [0,100] x [0,100]; eine Raute mit Halbdiagonale 30 um (-25|-25) liegt mit ihrem Umriss-Rechteck ([-55,5]) im Eck, die Raute selbst nicht
        // (ihre naechste Kante verlaeuft durch (-25|5) und (5|-25), die Ecke des Rechtecks (0|0) liegt auf der anderen Seite).
        for ((cx, cy) in listOf(-25.0 to -25.0, 125.0 to -25.0, -25.0 to 125.0, 125.0 to 125.0)) {
            assertFalse("($cx|$cy)", intersectsRect(diamond(cx, cy, 30.0), 0.0, 0.0, 100.0, 100.0))
            // Etwas naeher heran schneidet sie.
            val nearer = if (cx < 0) cx + 10 else cx - 10
            val nearerY = if (cy < 0) cy + 10 else cy - 10
            assertTrue("($cx|$cy) naeher", intersectsRect(diamond(nearer, nearerY, 30.0), 0.0, 0.0, 100.0, 100.0))
        }
    }

    @Test
    fun `squares and rectangles that contain, are contained or cross each other touch`() {
        assertTrue(intersectsRect(diamond(50.0, 50.0, 10.0), 0.0, 0.0, 100.0, 100.0)) // Raute im Rechteck
        assertTrue(intersectsRect(diamond(50.0, 50.0, 500.0), 0.0, 0.0, 100.0, 100.0)) // Rechteck in der Raute
        assertTrue(intersectsRect(diamond(-5.0, 50.0, 20.0), 0.0, 0.0, 100.0, 100.0)) // ragt an der Kante hinein
        assertFalse(intersectsRect(diamond(-50.0, 50.0, 20.0), 0.0, 0.0, 100.0, 100.0)) // links daneben
        assertFalse(intersectsRect(diamond(50.0, 200.0, 20.0), 0.0, 0.0, 100.0, 100.0)) // darunter
    }

    @Test
    fun `a turned view needs fewer tiles than its bounding box`() {
        val ref = 13.0
        val view = viewAt(52.52, 13.405, ref, 10, 360.0, 45.0)
        val count = visibleTiles(view, w, h, ref, 10, maxTiles = 1000).size
        // Das umschliessende Rechteck der gedrehten Flaeche (800 x 600 bei 45 Grad: 990 x 990) fasst 3 x 3 bis 4 x 4 Kacheln; die Flaeche selbst schneidet weniger.
        assertTrue("$count", count in 6..13)
    }

    @Test
    fun `the nearest tiles come first and the number is capped`() {
        val ref = 13.0
        val view = viewAt(52.52, 13.405, ref, 12, 200.0, 0.0)
        val all = visibleTiles(view, w, h, ref, 12, maxTiles = 1000)
        assertTrue(all.size > 12)
        val capped = visibleTiles(view, w, h, ref, 12, maxTiles = 12)
        assertEquals(all.take(12), capped)
        // Die erste Kachel ist die, in der die Mitte der Flaeche liegt.
        val (px, py) = toPlane(view, w, h, w / 2, h / 2)
        val centre = tileContaining(Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(py))), ref + Math.toDegrees(px), 12)
        assertEquals(centre, all.first())
    }

    @Test
    fun `unusable input gives no tiles`() {
        val view = TrackView(0.0, 0.9, 1.0e6)
        assertTrue(visibleTiles(view, 0.0, h, 0.0, 5).isEmpty())
        assertTrue(visibleTiles(view, w, -1.0, 0.0, 5).isEmpty())
        assertTrue(visibleTiles(view, w, h, 0.0, -1).isEmpty())
        assertTrue(visibleTiles(view, w, h, 0.0, MAX_TILE_ZOOM + 1).isEmpty())
        assertTrue(visibleTiles(view, w, h, 0.0, 5, maxTiles = 0).isEmpty())
        assertTrue(visibleTiles(view.copy(cx = Double.NaN), w, h, 0.0, 5).isEmpty())
        assertTrue(visibleTiles(view.copy(pxPerUnit = 0.0), w, h, 0.0, 5).isEmpty())
        assertTrue(visibleTiles(view.copy(pxPerUnit = Double.POSITIVE_INFINITY), w, h, 0.0, 5).isEmpty())
        assertTrue(visibleTiles(view, w, h, 0.0, 5, marginPx = Double.NaN).isEmpty())
    }

    @Test
    fun `an absurd number of candidates gives no tiles instead of hanging`() {
        // Stufe 20 mit winzigem Massstab: Millionen Kacheln waeren im Bild.
        val view = TrackView(0.0, 0.5, 5.0)
        assertTrue(visibleTiles(view, w, h, 0.0, 20).isEmpty())
    }

    @Test
    fun `tiles never leave the grid`() {
        val view = TrackView(0.0, 3.0, 1.0e3) // y = 3 liegt jenseits der Mercator-Grenze (π)
        val tiles = visibleTiles(view, w, h, 0.0, 4, maxTiles = 1000)
        assertTrue(tiles.all { it.isValid })
    }

    // ── Korridor ──────────────────────────────────────────────────────────────

    private fun straight(lat0: Double, lon0: Double, lat1: Double, lon1: Double, n: Int = 20) =
        List(n) { LatLon(lat0 + (lat1 - lat0) * it / (n - 1), lon0 + (lon1 - lon0) * it / (n - 1)) }

    @Test
    fun `an empty track has no tiles`() {
        val p = corridorTiles(emptyList(), 5, 12, 1, 1000)
        assertTrue(p.tiles.isEmpty()); assertEquals(0, p.count); assertFalse(p.tooLarge)
        assertTrue(corridorTiles(listOf(LatLon(Double.NaN, 1.0)), 5, 12, 1, 1000).tiles.isEmpty())
    }

    @Test
    fun `a single point gives its tile and the buffer around it on every level`() {
        val p = corridorTiles(listOf(LatLon(52.52, 13.405)), 8, 10, 1, 1000)
        assertEquals(27, p.count) // 3 Stufen zu je 3 x 3
        assertEquals(mapOf(8 to 9, 9 to 9, 10 to 9), p.byZoom)
        for (z in 8..10) assertTrue(tileContaining(52.52, 13.405, z) in p.tiles)
        val noBuffer = corridorTiles(listOf(LatLon(52.52, 13.405)), 8, 10, 0, 1000)
        assertEquals(listOf(tileContaining(52.52, 13.405, 8), tileContaining(52.52, 13.405, 9), tileContaining(52.52, 13.405, 10)), noBuffer.tiles)
    }

    @Test
    fun `a straight track touches every tile on its way`() {
        val track = straight(50.0, 8.0, 50.0, 9.0) // etwa 71 km nach Osten
        val z = 12
        val p = corridorTiles(track, z, z, 0, 10_000)
        val xs = p.tiles.map { it.x }
        assertEquals(tileContaining(50.0, 8.0, z).x, xs.min())
        assertEquals(tileContaining(50.0, 9.0, z).x, xs.max())
        // Keine Luecke: jede Spalte dazwischen kommt vor.
        assertEquals((xs.min()..xs.max()).toList(), xs.distinct().sorted())
        // Auf der Breite 50 liegt der Weg in einer Zeile.
        assertEquals(1, p.tiles.map { it.y }.distinct().size)
    }

    @Test
    fun `a diagonal track leaves no gap`() {
        val z = 12
        val a = tileContaining(50.0, 8.0, z)
        val track = straight(50.0, 8.0, 50.3, 8.4, 5)
        val p = corridorTiles(track, z, z, 0, 10_000).tiles
        val set = p.toSet()
        // Jede Kachel des Weges hat eine Nachbarkachel (Kante) im Weg, ausser dem Anfang/Ende bei einer Spitze: der Weg ist zusammenhaengend.
        val remaining = HashSet(set)
        val queue = ArrayDeque(listOf(set.first()))
        remaining.remove(set.first())
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
                val n = TileKey(z, t.x + dx, t.y + dy)
                if (remaining.remove(n)) queue.add(n)
            }
        }
        assertTrue("nicht zusammenhaengend: $remaining", remaining.isEmpty())
        assertTrue(a in set)
    }

    @Test
    fun `a loop has no duplicate tiles`() {
        val loop = straight(50.0, 8.0, 50.1, 8.1, 10) + straight(50.1, 8.1, 50.0, 8.0, 10)
        val p = corridorTiles(loop, 10, 13, 1, 10_000)
        assertEquals(p.tiles.size, p.tiles.toSet().size)
    }

    @Test
    fun `the buffer widens the corridor on every side`() {
        val track = straight(50.0, 8.0, 50.0, 8.2, 10)
        val z = 12
        val thin = corridorTiles(track, z, z, 0, 10_000).tiles.toSet()
        val wide = corridorTiles(track, z, z, 2, 10_000).tiles.toSet()
        assertTrue(wide.containsAll(thin))
        assertEquals(5, wide.map { it.y }.distinct().size) // 1 Zeile + 2 oben + 2 unten
        assertEquals(thin.map { it.x }.min() - 2, wide.map { it.x }.min())
        assertEquals(thin.map { it.x }.max() + 2, wide.map { it.x }.max())
    }

    @Test
    fun `the order is by zoom level and stable`() {
        val track = straight(50.0, 8.0, 50.2, 8.4, 10)
        val a = corridorTiles(track, 8, 12, 1, 10_000)
        val b = corridorTiles(track, 8, 12, 1, 10_000)
        assertEquals(a.tiles, b.tiles)
        assertEquals(a.tiles.map { it.z }, a.tiles.map { it.z }.sorted())
    }

    @Test
    fun `too many tiles are reported and no tiles are returned`() {
        val track = straight(50.0, 8.0, 52.0, 12.0, 30)
        val p = corridorTiles(track, 8, 15, 1, 200)
        assertTrue(p.tooLarge)
        assertTrue(p.tiles.isEmpty())
        assertTrue(p.count > 200)
        // Mit grosszuegiger Grenze geht es.
        val ok = corridorTiles(track, 8, 15, 1, 1_000_000)
        assertFalse(ok.tooLarge)
        assertEquals(ok.count, ok.tiles.size)
    }

    @Test
    fun `the limit counts a track of one point and is not exceeded exactly at the limit`() {
        val one = listOf(LatLon(52.52, 13.405))
        assertTrue(corridorTiles(one, 8, 10, 1, 26).tooLarge) // 27 Kacheln
        val exact = corridorTiles(one, 8, 10, 1, 27)
        assertFalse(exact.tooLarge)
        assertEquals(27, exact.tiles.size)
    }

    @Test
    fun `a negative buffer is no buffer`() {
        val track = straight(50.0, 8.0, 50.0, 8.1, 5)
        assertEquals(corridorTiles(track, 12, 12, 0, 1000).tiles, corridorTiles(track, 12, 12, -3, 1000).tiles)
    }

    @Test
    fun `a huge jump gives tooLarge quickly`() {
        val p = corridorTiles(listOf(LatLon(-60.0, -170.0), LatLon(60.0, 170.0)), 26, 26, 0, 1_000)
        assertTrue(p.tooLarge)
    }

    @Test
    fun `points at the edge of the grid stay inside it`() {
        val p = corridorTiles(listOf(LatLon(89.0, 179.99), LatLon(-89.0, -179.99)), 3, 3, 1, 1_000)
        assertTrue(p.tiles.all { it.isValid })
    }

    @Test
    fun `the range of levels is respected`() {
        val p = corridorTiles(listOf(LatLon(50.0, 8.0)), 5, 3, 0, 100)
        assertTrue(p.tiles.isEmpty())
        val q = corridorTiles(listOf(LatLon(50.0, 8.0)), -3, 2, 0, 100)
        assertEquals(listOf(0, 1, 2), q.tiles.map { it.z })
    }

}
