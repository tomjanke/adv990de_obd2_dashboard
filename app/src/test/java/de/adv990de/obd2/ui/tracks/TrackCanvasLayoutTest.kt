package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.TrackBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Nur erfundene Koordinaten. */
class TrackCanvasLayoutTest {

    private val bounds = TrackBounds(minLat = 50.0, maxLat = 50.01, minLon = 10.0, maxLon = 10.02)
    private val w = 800.0
    private val h = 400.0

    private fun view() = fitView(bounds, w, h, paddingPx = 20.0)

    private fun frame() = trackFrame(bounds, w, h, paddingPx = 20.0)

    /** Der sichtbare Ausschnitt der Kartenebene: links, rechts, unten, oben. */
    private fun window(v: TrackView, f: TrackFrame): DoubleArray {
        val hw = f.widthPx / 2 / v.pxPerUnit
        val hh = f.heightPx / 2 / v.pxPerUnit
        return doubleArrayOf(v.cx - hw, v.cx + hw, v.cy - hh, v.cy + hh)
    }

    private fun assertInsideContent(v: TrackView, f: TrackFrame = frame()) {
        val win = window(v, f)
        val pad = f.paddingPx / v.pxPerUnit
        val eps = 1e-12
        // In einer Richtung, in der der Ausschnitt groesser ist als der Track, steht die Mitte auf der Mitte des Tracks.
        if (win[1] - win[0] <= f.maxX - f.minX + 2 * pad + eps) {
            assertTrue("links ${win[0]}", win[0] >= f.minX - pad - eps)
            assertTrue("rechts ${win[1]}", win[1] <= f.maxX + pad + eps)
        } else assertEquals((f.minX + f.maxX) / 2, v.cx, 1e-12)
        if (win[3] - win[2] <= f.maxY - f.minY + 2 * pad + eps) {
            assertTrue("unten ${win[2]}", win[2] >= f.minY - pad - eps)
            assertTrue("oben ${win[3]}", win[3] <= f.maxY + pad + eps)
        } else assertEquals((f.minY + f.maxY) / 2, v.cy, 1e-12)
    }

    private fun screen(lat: Double, lon: Double, v: TrackView = view()) = toScreen(v, w, h, projectLon(lon, bounds.minLon), projectLat(lat))

    @Test
    fun `the fitted view puts the whole track inside the padding`() {
        val a = screen(bounds.minLat, bounds.minLon)
        val b = screen(bounds.maxLat, bounds.maxLon)
        listOf(a, b).forEach {
            assertTrue("x ${it.x}", it.x >= 20.0 - 1e-6 && it.x <= w - 20.0 + 1e-6)
            assertTrue("y ${it.y}", it.y >= 20.0 - 1e-6 && it.y <= h - 20.0 + 1e-6)
        }
    }

    @Test
    fun `north is up and east is right`() {
        val sw = screen(bounds.minLat, bounds.minLon)
        val ne = screen(bounds.maxLat, bounds.maxLon)
        assertTrue(ne.x > sw.x)
        assertTrue(ne.y < sw.y)
    }

    @Test
    fun `the aspect ratio is kept - a square of metres is a square of pixels`() {
        // gleiche Strecke in Nord und in Ost: Pixelabstand wird gleich (cos(lat) beruecksichtigt)
        val origin = screen(50.005, 10.01)
        val metresPerDegLat = 111_195.0
        val dLat = 100.0 / metresPerDegLat
        val dLon = 100.0 / (metresPerDegLat * kotlin.math.cos(Math.toRadians(50.005)))
        val north = screen(50.005 + dLat, 10.01)
        val east = screen(50.005, 10.01 + dLon)
        val vertical = abs(north.y - origin.y)
        val horizontal = abs(east.x - origin.x)
        assertEquals(vertical, horizontal, vertical * 0.01)
    }

    @Test
    fun `the track is centred on the surface`() {
        val a = screen(bounds.minLat, bounds.minLon)
        val b = screen(bounds.maxLat, bounds.maxLon)
        assertEquals((a.x + b.x) / 2, w / 2, 1.0)
        assertEquals((a.y + b.y) / 2, h / 2, 1.0)
    }

    @Test
    fun `a single point still gets a usable view`() {
        val one = TrackBounds(50.0, 50.0, 10.0, 10.0)
        val v = fitView(one, w, h, paddingPx = 20.0)
        assertTrue(v.pxPerUnit.isFinite() && v.pxPerUnit > 0)
        val p = toScreen(v, w, h, projectLon(10.0, 10.0), projectLat(50.0))
        assertEquals(w / 2, p.x, 1e-6)
        assertEquals(h / 2, p.y, 1e-6)
    }

    @Test
    fun `a track exactly north-south or east-west still gets a finite view`() {
        val vertical = fitView(TrackBounds(50.0, 50.01, 10.0, 10.0), w, h, paddingPx = 20.0)
        val horizontal = fitView(TrackBounds(50.0, 50.0, 10.0, 10.02), w, h, paddingPx = 20.0)
        assertTrue(vertical.pxPerUnit.isFinite() && vertical.pxPerUnit > 0)
        assertTrue(horizontal.pxPerUnit.isFinite() && horizontal.pxPerUnit > 0)
    }

    @Test
    fun `zooming in keeps the centre and doubles the scale`() {
        val fit = view()
        val z = zoomBy(fit, 2.0, fit)
        assertEquals(fit.pxPerUnit * 2, z.pxPerUnit, 1e-9)
        assertEquals(fit.cx, z.cx, 0.0)
        assertEquals(fit.cy, z.cy, 0.0)
    }

    @Test
    fun `zoom is limited on both sides`() {
        val fit = view()
        assertEquals(fit.pxPerUnit * MAX_ZOOM_IN, zoomBy(fit, 1e9, fit).pxPerUnit, 1e-6)
        assertEquals(fit.pxPerUnit * MIN_ZOOM_OUT, zoomBy(fit, 1e-9, fit).pxPerUnit, 1e-6)
    }

    @Test
    fun `the track is never smaller than the fitted view`() {
        assertEquals(1.0, MIN_ZOOM_OUT, 0.0)
    }

    @Test
    fun `panning moves the content with the finger direction`() {
        val fit = view()
        val before = screen(50.005, 10.01, fit)
        val moved = panBy(fit, dxPx = 40.0, dyPx = -30.0)
        val after = screen(50.005, 10.01, moved)
        assertEquals(before.x + 40.0, after.x, 1e-6)
        assertEquals(before.y - 30.0, after.y, 1e-6)
    }

    @Test
    fun `the scale bar has a round length that fits the maximum width`() {
        val fit = view()
        val bar = scaleBar(fit, centerLatDeg = 50.005, maxBarPx = 160.0)
        assertTrue(bar.metres in listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000))
        assertTrue(bar.px <= 160.0 + 1e-6)
        // und es ist die groesste runde Laenge, die noch passt: die naechste runde waere zu lang
        val metresPerPx = bar.metres / bar.px
        assertTrue(bar.px > 160.0 / 2.6 || bar.metres == 100000)
        assertTrue(metresPerPx > 0)
    }

    @Test
    fun `the scale bar grows when zooming in`() {
        val fit = view()
        val near = scaleBar(zoomBy(fit, 16.0, fit), 50.005, 160.0)
        val far = scaleBar(fit, 50.005, 160.0)
        assertTrue(near.metres < far.metres)
    }

    @Test
    fun `metres per pixel follows the latitude`() {
        val v = TrackView(cx = 0.0, cy = 0.0, pxPerUnit = 1_000_000.0)
        assertTrue(metresPerPx(v, 0.0) > metresPerPx(v, 60.0))
        assertEquals(metresPerPx(v, 0.0) * 0.5, metresPerPx(v, 60.0), metresPerPx(v, 0.0) * 0.001)
    }

    @Test
    fun `the label of a length is metres below a kilometre and kilometres above`() {
        assertEquals(50 to false, scaleBarUnit(50))
        assertEquals(500 to false, scaleBarUnit(500))
        assertEquals(1 to true, scaleBarUnit(1000))
        assertEquals(20 to true, scaleBarUnit(20000))
    }

    // ── Ansichtszustand relativ zur angepassten Ansicht (ueberlebt eine Drehung) ──

    @Test
    fun `the untouched state resolves to the fitted view`() {
        val fit = view()
        assertEquals(fit, TrackViewState().resolve(fit))
    }

    @Test
    fun `zooming and panning the state equal doing it on the view while there is room`() {
        val f = frame()
        val viaState = TrackViewState().zoomedBy(2.0, f).pannedBy(40.0, -30.0, f).resolve(f.fit)
        val direct = panBy(zoomBy(f.fit, 2.0, f.fit), 40.0, -30.0)
        assertEquals(direct.pxPerUnit, viaState.pxPerUnit, 1e-9)
        assertEquals(direct.cx, viaState.cx, 1e-12)
        assertEquals(direct.cy, viaState.cy, 1e-12)
    }

    @Test
    fun `the zoom of the state is limited on both sides`() {
        val f = frame()
        assertEquals(MAX_ZOOM_IN, TrackViewState().zoomedBy(1e9, f).zoom, 1e-9)
        assertEquals(MIN_ZOOM_OUT, TrackViewState().zoomedBy(1e-9, f).zoom, 1e-9)
    }

    @Test
    fun `after a rotation the same zoom and centre still apply to the new surface`() {
        val f = frame()
        val state = TrackViewState().zoomedBy(4.0, f).pannedBy(100.0, 0.0, f)
        val rotatedFit = fitView(bounds, h, w, paddingPx = 20.0)
        val resolved = state.resolve(rotatedFit)
        assertEquals(rotatedFit.pxPerUnit * 4.0, resolved.pxPerUnit, 1e-9)
        // dieselbe geografische Verschiebung, nicht dieselben Bildpunkte
        assertEquals(f.fit.cx + (state.resolve(f.fit).cx - f.fit.cx), resolved.cx - rotatedFit.cx + f.fit.cx, 1e-12)
    }

    @Test
    fun `the fit action brings the state back to the start`() {
        val f = frame()
        val moved = TrackViewState().zoomedBy(8.0, f).pannedBy(50.0, 50.0, f)
        assertEquals(TrackViewState(), moved.fitted())
    }

    // ── Der Track verlaesst den Rahmen nie ────────────────────────────────────

    @Test
    fun `the frame fit is the fitted view`() {
        assertEquals(fitView(bounds, w, h, 20.0), frame().fit)
    }

    @Test
    fun `fitted, the view cannot be moved at all`() {
        val f = frame()
        for ((dx, dy) in listOf(500.0 to 0.0, -500.0 to 0.0, 0.0 to 500.0, 0.0 to -500.0, 300.0 to 300.0)) {
            val moved = TrackViewState().pannedBy(dx, dy, f)
            assertEquals("zoom", 1.0, moved.zoom, 1e-9)
            assertEquals("dx $dx $dy", 0.0, moved.dx, 1e-12)
            assertEquals("dy $dx $dy", 0.0, moved.dy, 1e-12)
        }
    }

    @Test
    fun `zooming out cannot go below the fitted view and ends exactly on it`() {
        val f = frame()
        val zoomedIn = TrackViewState().zoomedBy(8.0, f).pannedBy(120.0, 60.0, f)
        var state = zoomedIn
        repeat(6) { state = state.zoomedBy(0.5, f) }
        assertEquals(1.0, state.zoom, 1e-9)
        assertEquals(0.0, state.dx, 1e-12)
        assertEquals(0.0, state.dy, 1e-12)
    }

    @Test
    fun `zoomed in, the visible window stays inside the track rectangle whatever the finger does`() {
        val f = frame()
        var state = TrackViewState().zoomedBy(4.0, f)
        for ((dx, dy) in listOf(5_000.0 to 0.0, -9_000.0 to 0.0, 0.0 to 5_000.0, 0.0 to -9_000.0, 7_000.0 to -7_000.0, -300.0 to 200.0)) {
            state = state.pannedBy(dx, dy, f)
            assertInsideContent(state.resolved(f), f)
        }
    }

    @Test
    fun `zooming in at the edge cannot push the window out either`() {
        val f = frame()
        var state = TrackViewState().zoomedBy(4.0, f)
        state = state.pannedBy(-100_000.0, 100_000.0, f)
        for (factor in listOf(2.0, 3.0, 1.5)) {
            state = state.zoomedAround(factor, w - 1.0, 1.0, f)
            assertInsideContent(state.resolved(f), f)
        }
    }

    @Test
    fun `a saved state that lies outside the new surface after a rotation is brought back`() {
        val rotated = trackFrame(bounds, h, w, 20.0)
        val farOff = TrackViewState(zoom = 4.0, dx = 1.0, dy = -1.0)
        assertInsideContent(farOff.resolved(rotated), rotated)
    }

    // ── Zwei-Finger-Zoom ──────────────────────────────────────────────────────

    @Test
    fun `a pinch keeps the point under the fingers where it was`() {
        val f = frame()
        val zoomedIn = TrackViewState().zoomedBy(4.0, f)
        val before = zoomedIn.resolved(f)
        val focusX = 500.0
        val focusY = 150.0
        val mapX = before.cx + (focusX - w / 2) / before.pxPerUnit
        val mapY = before.cy - (focusY - h / 2) / before.pxPerUnit
        val after = zoomedIn.zoomedAround(2.0, focusX, focusY, f).resolved(f)
        assertEquals(before.pxPerUnit * 2, after.pxPerUnit, 1e-6)
        val p = toScreen(after, w, h, mapX, mapY)
        assertEquals(focusX, p.x, 1e-6)
        assertEquals(focusY, p.y, 1e-6)
    }

    @Test
    fun `a pinch around the centre is the same as the zoom button`() {
        val f = frame()
        assertEquals(TrackViewState().zoomedBy(2.0, f), TrackViewState().zoomedAround(2.0, w / 2, h / 2, f))
    }

    @Test
    fun `a gesture without zoom is a pure pan and one without pan a pure zoom`() {
        val f = frame()
        val start = TrackViewState().zoomedBy(4.0, f)
        assertEquals(start.pannedBy(30.0, -20.0, f), start.transformedBy(400.0, 200.0, 30.0, -20.0, 1.0, f))
        assertEquals(start.zoomedAround(1.5, 300.0, 120.0, f), start.transformedBy(300.0, 120.0, 0.0, 0.0, 1.5, f))
    }

    @Test
    fun `a gesture zooms first and then moves`() {
        val f = frame()
        val start = TrackViewState().zoomedBy(2.0, f)
        assertEquals(
            start.zoomedAround(2.0, 350.0, 100.0, f).pannedBy(25.0, 10.0, f),
            start.transformedBy(350.0, 100.0, 25.0, 10.0, 2.0, f),
        )
    }

    // ── Die eigene Position am Rand ───────────────────────────────────────────

    @Test
    fun `a position inside the surface stays where it is`() {
        val p = clampToFrame(300.0, 120.0, 800.0, 400.0, 20.0)
        assertEquals(300.0, p.x, 0.0)
        assertEquals(120.0, p.y, 0.0)
    }

    @Test
    fun `a position beyond an edge sits on that edge inside the margin`() {
        val right = clampToFrame(2_000.0, 200.0, 800.0, 400.0, 20.0)
        assertEquals(780.0, right.x, 1e-9)
        assertEquals(200.0, right.y, 1e-9)
        val top = clampToFrame(400.0, -900.0, 800.0, 400.0, 20.0)
        assertEquals(400.0, top.x, 1e-9)
        assertEquals(20.0, top.y, 1e-9)
    }

    @Test
    fun `a position far in a corner direction keeps its direction from the centre`() {
        val p = clampToFrame(2_000.0, 1_000.0, 800.0, 400.0, 20.0)
        assertTrue("x ${p.x}", p.x in 20.0..780.0)
        assertTrue("y ${p.y}", p.y in 20.0..380.0)
        // auf dem Strahl von der Mitte (400, 200) zum Punkt: Steigung 800 / 1600
        assertEquals((p.y - 200.0) / (p.x - 400.0), 800.0 / 1_600.0, 1e-9)
        assertTrue("an einem Rand", p.x == 780.0 || p.y == 380.0)
    }

    @Test
    fun `a position exactly in the centre is not moved`() {
        val p = clampToFrame(400.0, 200.0, 800.0, 400.0, 20.0)
        assertEquals(400.0, p.x, 0.0)
        assertEquals(200.0, p.y, 0.0)
    }

    @Test
    fun `the inverse of the latitude projection gives the latitude back`() {
        for (lat in listOf(-60.0, -0.5, 0.0, 12.34, 50.005, 84.0)) assertEquals(lat, unprojectLat(projectLat(lat)), 1e-9)
    }

    @Test
    fun `the window may reach beyond the track by exactly the padding and no further`() {
        val f = frame()
        val far = TrackViewState().zoomedBy(4.0, f).pannedBy(-1e6, 0.0, f).pannedBy(0.0, 1e6, f).resolved(f)
        val pad = f.paddingPx / far.pxPerUnit
        assertEquals("rechts: der Track plus der Rand", f.maxX + pad, far.cx + f.widthPx / 2 / far.pxPerUnit, 1e-9)
        assertEquals("oben: der Track plus der Rand", f.maxY + pad, far.cy + f.heightPx / 2 / far.pxPerUnit, 1e-9)
        val near = TrackViewState().zoomedBy(4.0, f).pannedBy(1e6, 0.0, f).pannedBy(0.0, -1e6, f).resolved(f)
        assertEquals("links: der Track plus der Rand", f.minX - pad, near.cx - f.widthPx / 2 / near.pxPerUnit, 1e-9)
        assertEquals("unten: der Track plus der Rand", f.minY - pad, near.cy - f.heightPx / 2 / near.pxPerUnit, 1e-9)
    }

    @Test
    fun `zooming out around a point at the edge cannot pull the window out of the frame`() {
        val f = frame()
        val atTheLeft = TrackViewState().zoomedBy(4.0, f).pannedBy(1e6, 0.0, f).resolved(f)
        // herauszoomen um den rechten Rand: ungeklemmt reichte der Ausschnitt links ueber den Rahmen hinaus
        val result = f.zoomAround(atTheLeft, 0.5, f.widthPx, f.heightPx / 2)
        assertInsideContent(result, f)
        assertTrue("es ist wirklich herausgezoomt", result.pxPerUnit < atTheLeft.pxPerUnit)
    }
}
