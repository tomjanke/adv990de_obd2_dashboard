package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.center
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Zeichnung des Tracks, als Bildpunkte gelesen (nicht nur gerechnet): die Startmarke liegt dort, wo die Projektion sie hinrechnet, die Knoepfe verschieben und zoomen
 * die Ansicht in die richtige Richtung, „Anpassen“ und der Doppeltipp bringen sie zurueck, **zwei Finger zoomen um ihren Mittelpunkt**, und der Track verlaesst den Rahmen nie
 * (angepasst laesst sich nichts verschieben, herausgezoomt wird nur bis „angepasst“). Nur erfundene Koordinaten.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrackMapDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val colors = lightColorScheme()

    /**
     * Von links oben nach rechts unten: der Start liegt im Bild links oben, das Ziel rechts unten, in der Mitte ein Knick nach oben. **Das Rechteck hat das Seitenverhaeltnis der Flaeche
     * (400 x 300 dp abzueglich je 32 dp Rand):** angepasst fuellt die Strecke beide Achsen. Waere eine Achse kuerzer als das Fenster, hielte der Rahmen sie beim Hineinzoomen in der Mitte
     * fest, und der Punkt unter den Fingern bliebe dort nicht stehen (das ist gewollt, der Track verlaesst den Rahmen nie, aber kein Beleg fuer das Zoomen um die Finger).
     */
    private val points = listOf(
        TrackPoint(null, 50.0075, 10.000, null, null, null),
        TrackPoint(null, 50.00903, 10.010, null, null, null),
        TrackPoint(null, 50.000, 10.020, null, null, null),
    )
    private val bounds = TrackBounds(minLat = 50.000, maxLat = 50.00903, minLon = 10.000, maxLon = 10.020)

    private fun show() {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp))
            }
        }
        composeRule.waitForIdle()
    }

    private fun canvas() = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG)

    private fun density() = composeRule.density.density

    /** Der Rahmen der Zeichnung, wie die Ansicht ihn rechnet. */
    private fun frame(): TrackFrame {
        val image = canvas().captureToImage()
        return trackFrame(bounds, image.width.toDouble(), image.height.toDouble(), paddingPx = 32 * density().toDouble())
    }

    /** Wo die Startmarke bei der Ansicht [state] sitzen muss, in Bildpunkten der Zeichnung. */
    private fun expectedStart(state: TrackViewState): Pair<Int, Int> {
        val f = frame()
        val p = toScreen(state.resolved(f), f.widthPx, f.heightPx, projectLon(points[0].lon, bounds.minLon), projectLat(points[0].lat))
        return p.x.roundToInt() to p.y.roundToInt()
    }

    private fun step() = minOf(frame().widthPx, frame().heightPx) * 0.25

    private fun Color.near(other: Color, tolerance: Float = 0.06f) =
        abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance

    private fun startMarkerAt(x: Int, y: Int): Boolean {
        val map = canvas().captureToImage().toPixelMap()
        if (x !in 0 until map.width || y !in 0 until map.height) return false
        return map[x, y].near(colors.tertiary)
    }

    private fun linePixels(): List<Pair<Int, Int>> {
        val map = canvas().captureToImage().toPixelMap()
        return (0 until map.height).flatMap { y -> (0 until map.width).filter { x -> map[x, y].near(colors.primary) }.map { x -> x to y } }
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    /** Hineingezoomt und so verschoben, dass die Startmarke im Bild steht (auf angepasster Ansicht liegt sie links oben, hineingezoomt zuerst ausserhalb). */
    private fun zoomedInWithTheStartInView(): TrackViewState {
        click(TRACK_ZOOM_IN_TEST_TAG)
        click(TRACK_PAN_LEFT_TEST_TAG)
        click(TRACK_PAN_LEFT_TEST_TAG)
        click(TRACK_PAN_UP_TEST_TAG)
        val f = frame()
        val s = step()
        return TrackViewState().zoomedBy(2.0, f).pannedBy(s, 0.0, f).pannedBy(s, 0.0, f).pannedBy(0.0, s, f)
    }

    @Test
    fun theStartMarker_sitsWhereTheProjectionPutsIt() {
        show()
        val (x, y) = expectedStart(TrackViewState())
        assertTrue("Startmarke bei ($x, $y)", startMarkerAt(x, y))
    }

    @Test
    fun theLine_isDrawnBetweenTheMarkers() {
        show()
        assertTrue("Linie gezeichnet", linePixels().size > 200)
    }

    @Test
    fun zoomedIn_theButtonsMoveTheContent_andFitBringsItBack() {
        show()
        val (xFit, yFit) = expectedStart(TrackViewState())
        assertFalse("hineingezoomt steht die Startmarke zuerst ausserhalb", expectedStart(TrackViewState().zoomedBy(2.0, frame())).let { (x, y) -> x in 0 until frame().widthPx.toInt() && y in 0 until frame().heightPx.toInt() })

        val state = zoomedInWithTheStartInView()
        val (x, y) = expectedStart(state)
        assertTrue("die Marke steht im Bild bei ($x, $y)", startMarkerAt(x, y))
        assertFalse("und nicht mehr am Platz der angepassten Ansicht", startMarkerAt(xFit, yFit))

        click(TRACK_FIT_TEST_TAG)
        assertTrue("angepasst steht sie wieder am Anfang", startMarkerAt(xFit, yFit))
    }

    @Test
    fun theButtons_cannotPushTheTrackOutOfTheFrame() {
        show()
        // hineingezoomt und so weit nach links geschoben, wie es geht: ein weiterer Tipp aendert nichts mehr (die Klemme haelt den Ausschnitt im Rechteck des Tracks)
        zoomedInWithTheStartInView()
        repeat(4) { click(TRACK_PAN_LEFT_TEST_TAG); click(TRACK_PAN_UP_TEST_TAG) }
        val f = frame()
        val s = step()
        var model = TrackViewState().zoomedBy(2.0, f)
        repeat(6) { model = model.pannedBy(s, 0.0, f).pannedBy(0.0, s, f) }
        val (x, y) = expectedStart(model)
        assertTrue("Startmarke an der geklemmten Stelle ($x, $y)", startMarkerAt(x, y))
        // die geklemmte Stelle liegt im Bild: der Track ist nicht herausgeschoben
        assertTrue(x in 0 until f.widthPx.toInt() && y in 0 until f.heightPx.toInt())
        assertTrue("die Linie ist noch zu sehen", linePixels().size > 200)
    }

    @Test
    fun fitted_nothingCanBeMoved() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())
        for (tag in listOf(TRACK_PAN_LEFT_TEST_TAG, TRACK_PAN_RIGHT_TEST_TAG, TRACK_PAN_UP_TEST_TAG, TRACK_PAN_DOWN_TEST_TAG)) click(tag)
        assertTrue("angepasst bleibt die Startmarke, wo sie ist", startMarkerAt(x0, y0))
        canvas().performTouchInput { swipe(start = center, end = center.copy(x = center.x + 300f), durationMillis = 300) }
        composeRule.waitForIdle()
        assertTrue("auch ein Ziehen bewegt nichts", startMarkerAt(x0, y0))
    }

    @Test
    fun zoomOut_stopsAtTheFittedView() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())
        repeat(3) { click(TRACK_ZOOM_OUT_TEST_TAG) }
        assertTrue("angepasst gibt es kein Herauszoomen: die Marke bleibt", startMarkerAt(x0, y0))

        click(TRACK_ZOOM_IN_TEST_TAG)
        click(TRACK_ZOOM_IN_TEST_TAG)
        repeat(4) { click(TRACK_ZOOM_OUT_TEST_TAG) }
        assertTrue("und nach Hinein- und Herauszoomen ist es wieder genau die angepasste Ansicht", startMarkerAt(x0, y0))
    }

    @Test
    fun aDoubleTap_fitsTheView_again() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())
        zoomedInWithTheStartInView()
        assertFalse(startMarkerAt(x0, y0))

        canvas().performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()

        assertTrue(startMarkerAt(x0, y0))
    }

    @Test
    fun dragging_movesTheView_asAnExtraWayBesidesTheButtons() {
        show()
        click(TRACK_ZOOM_IN_TEST_TAG)
        val before = linePixels()
        val meanBefore = before.map { it.first }.average()

        canvas().performTouchInput { swipe(start = center, end = center.copy(x = center.x + 120f), durationMillis = 300) }
        composeRule.waitForIdle()

        val meanAfter = linePixels().map { it.first }.average()
        assertTrue("die Linie ist nach rechts gewandert (vorher $meanBefore, nachher $meanAfter)", meanAfter > meanBefore + 40)
    }

    // ── Zwei Finger ───────────────────────────────────────────────────────────

    /** Ein Punkt der Linie nahe der Mitte der Zeichnung. */
    private fun lineNearTheCentre(): Pair<Int, Int> {
        val f = frame()
        return linePixels().minBy { (x, y) -> (x - f.widthPx / 2) * (x - f.widthPx / 2) + (y - f.heightPx / 2) * (y - f.heightPx / 2) }
    }

    /**
     * Ein Punkt der Linie nahe der Mitte, an dem der Rahmen beim Hineinzoomen nichts verschiebt: nur dort bleibt der Punkt unter den Fingern gleich (an den Raendern der Strecke klemmt der Rahmen
     * die Ansicht, das ist gewollt). Geprueft wird mit der Rechnung des Rahmens selbst fuer mehrere Zoomfaktoren.
     */
    private fun lineNearTheCentreWithoutClamping(farthest: Boolean = false, fingerReach: Double = 0.0): Pair<Int, Int> {
        val f = frame()
        val fit = f.fit
        val line = linePixels()
        val onLine = line.toHashSet()
        // Der Strich ist 4 dp breit: ein Punkt am Strichrand laege nach dem Zoomen (der Strich wird nicht dicker) neben dem Strich. Gewaehlt wird darum ein Punkt in der Strichmitte.
        val ring = listOf(-3 to 0, 3 to 0, 0 to -3, 0 to 3, -2 to -2, 2 to 2, -2 to 2, 2 to -2)
        return line
            .filter { (x, y) -> ring.all { (dx, dy) -> (x + dx) to (y + dy) in onLine } }
            .filter { (x, y) ->
                (if (farthest) (4..24).map { it * 0.25 } else listOf(2.0, 3.0, 4.0, 6.0)).all { z ->
                    val ppu = fit.pxPerUnit * z
                    val wantedCx = fit.cx + (x - f.widthPx / 2) / fit.pxPerUnit - (x - f.widthPx / 2) / ppu
                    val wantedCy = fit.cy - (y - f.heightPx / 2) / fit.pxPerUnit + (y - f.heightPx / 2) / ppu
                    val got = f.zoomAround(fit, z, x.toDouble(), y.toDouble())
                    Math.abs(got.cx - wantedCx) * ppu < 0.5 && Math.abs(got.cy - wantedCy) * ppu < 0.5
                }
            }
            .filter { (x, _) -> x - fingerReach >= 0 && x + fingerReach <= f.widthPx }
            .let { candidates ->
                val distance = { p: Pair<Int, Int> -> (p.first - f.widthPx / 2) * (p.first - f.widthPx / 2) + (p.second - f.heightPx / 2) * (p.second - f.heightPx / 2) }
                if (farthest) candidates.maxByOrNull(distance) else candidates.minByOrNull(distance)
            }
            ?: error("kein Punkt der Linie, an dem der Rahmen beim Zoomen nichts verschiebt")
    }

    private fun lineAt(x: Int, y: Int): Boolean {
        val map = canvas().captureToImage().toPixelMap()
        if (x !in 0 until map.width || y !in 0 until map.height) return false
        return map[x, y].near(colors.primary)
    }

    /** Reicht die Linie bis an den Rand der Zeichnung? Angepasst bleibt der Rand des Rahmens frei, hineingezoomt laeuft die Linie aus dem Bild. */
    private fun lineReachesTheEdge(): Boolean {
        val map = canvas().captureToImage().toPixelMap()
        return linePixels().any { (x, y) -> x < 8 || y < 8 || x > map.width - 9 || y > map.height - 9 }
    }

    @Test
    fun twoFingersApart_zoomIn_aroundTheirCentre() {
        show()
        val (fx, fy) = lineNearTheCentreWithoutClamping()
        val (xFit, yFit) = expectedStart(TrackViewState())
        assertFalse("angepasst bleibt der Rand frei", lineReachesTheEdge())
        assertTrue(startMarkerAt(xFit, yFit))

        // Die Fingerbewegung muss die Touch-Schwelle ueberschreiten, bevor ein Zoom zaehlt: ein weiter Weg (Faktor 4) laesst davon genug uebrig.
        val d = 60f
        canvas().performTouchInput {
            pinch(Offset(fx - d, fy.toFloat()), Offset(fx - 4 * d, fy.toFloat()), Offset(fx + d, fy.toFloat()), Offset(fx + 4 * d, fy.toFloat()), durationMillis = 500)
        }
        composeRule.waitForIdle()

        assertTrue("hineingezoomt laeuft die Linie aus dem Bild", lineReachesTheEdge())
        assertFalse("die Startmarke ist aus dem Bild gezoomt", startMarkerAt(xFit, yFit))
        val nearest = linePixels().minOf { (x, y) -> Math.hypot((x - fx).toDouble(), (y - fy).toDouble()) }
        assertTrue("der Punkt unter den Fingern ($fx, $fy) bleibt, wo er war: naechster Punkt der Linie $nearest px entfernt", nearest <= 8.0)
    }

    @Test
    fun twoFingersTogether_zoomOut_butNeverBelowFitted() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())
        val (fx, fy) = lineNearTheCentre()
        canvas().performTouchInput {
            pinch(Offset(fx - 140f, fy.toFloat()), Offset(fx - 40f, fy.toFloat()), Offset(fx + 140f, fy.toFloat()), Offset(fx + 40f, fy.toFloat()), durationMillis = 500)
        }
        composeRule.waitForIdle()
        assertTrue("angepasst bringt Zusammenziehen nichts: die Ansicht bleibt angepasst", startMarkerAt(x0, y0))
    }

    /**
     * Wie der Test oben, aber um den **entferntesten** Punkt der Linie, an dem der Rahmen nichts klemmt, und mit enger Toleranz. Um die Mitte der Flaeche ist es gleich, ob die Mitte der Finger
     * oder die Ecke (0, 0) als Zoom-Mittelpunkt dient (die Zickzack-Linie liegt dort zufaellig wieder unter den Fingern): erst weit weg von der Mitte verraet sich ein falscher Mittelpunkt.
     */
    @Test
    fun twoFingersApart_zoomIn_aroundAPointFarFromTheMiddle_keepsThatPointUnderThem() {
        show()
        val d = 60f
        val (fx, fy) = lineNearTheCentreWithoutClamping(farthest = true, fingerReach = 4.0 * d)
        val f = frame()
        val farFromTheMiddle = Math.hypot(fx - f.widthPx / 2, fy - f.heightPx / 2)
        assertTrue("der Punkt liegt wirklich abseits der Mitte ($farFromTheMiddle px)", farFromTheMiddle > 60 * density())

        canvas().performTouchInput {
            pinch(Offset(fx - d, fy.toFloat()), Offset(fx - 4 * d, fy.toFloat()), Offset(fx + d, fy.toFloat()), Offset(fx + 4 * d, fy.toFloat()), durationMillis = 500)
        }
        composeRule.waitForIdle()

        val nearest = linePixels().minOf { (x, y) -> Math.hypot((x - fx).toDouble(), (y - fy).toDouble()) }
        assertTrue("der Punkt unter den Fingern ($fx, $fy) bleibt, wo er war: naechster Punkt der Linie $nearest px entfernt", nearest <= 4.0)
    }

    // ── Zuschneiden ───────────────────────────────────────────────────────────

    /** Die Karte liegt mit Abstand in einer groesseren, weissen Flaeche; ausserhalb ihres Rechtecks darf keine Linienfarbe stehen. */
    @Test
    fun theLine_neverIsDrawnOutsideTheMap() {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                Box(Modifier.size(width = 440.dp, height = 340.dp).background(Color.White).testTag("outer")) {
                    TrackMapView(points, bounds, Modifier.offset(20.dp, 20.dp).size(width = 400.dp, height = 300.dp))
                }
            }
        }
        composeRule.waitForIdle()
        click(TRACK_ZOOM_IN_TEST_TAG)
        click(TRACK_ZOOM_IN_TEST_TAG)

        val outer = composeRule.onNodeWithTag("outer").captureToImage().toPixelMap()
        val d = density()
        val left = (20 * d).roundToInt() - 2
        val top = (20 * d).roundToInt() - 2
        val right = ((20 + 400) * d).roundToInt() + 2
        val bottom = ((20 + 300) * d).roundToInt() + 2
        var inside = 0
        var outside = 0
        for (y in 0 until outer.height) for (x in 0 until outer.width) {
            if (!outer[x, y].near(colors.primary)) continue
            if (x in left until right && y in top until bottom) inside++ else outside++
        }
        assertTrue("die Linie ist in der Karte zu sehen ($inside px)", inside > 100)
        assertEquals("ausserhalb der Karte steht keine Linie", 0, outside)
    }
}
