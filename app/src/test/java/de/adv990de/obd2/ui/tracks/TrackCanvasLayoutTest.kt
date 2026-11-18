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
    fun `zooming and panning the state equal doing it on the view`() {
        val fit = view()
        val viaState = TrackViewState().zoomedBy(2.0, fit).pannedBy(40.0, -30.0, fit).resolve(fit)
        val direct = panBy(zoomBy(fit, 2.0, fit), 40.0, -30.0)
        assertEquals(direct.pxPerUnit, viaState.pxPerUnit, 1e-9)
        assertEquals(direct.cx, viaState.cx, 1e-12)
        assertEquals(direct.cy, viaState.cy, 1e-12)
    }

    @Test
    fun `the zoom of the state is limited like the zoom of the view`() {
        val fit = view()
        assertEquals(MAX_ZOOM_IN, TrackViewState().zoomedBy(1e9, fit).zoom, 1e-9)
        assertEquals(MIN_ZOOM_OUT, TrackViewState().zoomedBy(1e-9, fit).zoom, 1e-9)
    }

    @Test
    fun `after a rotation the same zoom and centre still apply to the new surface`() {
        val fit = view()
        val state = TrackViewState().zoomedBy(4.0, fit).pannedBy(100.0, 0.0, fit)
        val rotatedFit = fitView(bounds, h, w, paddingPx = 20.0)
        val resolved = state.resolve(rotatedFit)
        assertEquals(rotatedFit.pxPerUnit * 4.0, resolved.pxPerUnit, 1e-9)
        // dieselbe geografische Verschiebung, nicht dieselben Bildpunkte
        assertEquals(fit.cx + (state.resolve(fit).cx - fit.cx), resolved.cx - rotatedFit.cx + fit.cx, 1e-12)
    }

    @Test
    fun `the fit action brings the state back to the start`() {
        val fit = view()
        val moved = TrackViewState().zoomedBy(8.0, fit).pannedBy(50.0, 50.0, fit)
        assertEquals(TrackViewState(), moved.fitted())
    }

    @Test
    fun `the inverse of the latitude projection gives the latitude back`() {
        for (lat in listOf(-60.0, -0.5, 0.0, 12.34, 50.005, 84.0)) assertEquals(lat, unprojectLat(projectLat(lat)), 1e-9)
    }
}
