package de.adv990de.obd2.ui.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Die Drehung der Karte, die Ansicht „Fahrtrichtung oben“ und der Platz des Abstandstextes. Nur erfundene Koordinaten (um 50 N, 10 E). */
class TrackHeadingLayoutTest {

    private val w = 800.0
    private val h = 600.0
    private val frame = HeadingFrame(w, h, marginPx = 40.0)

    /** Ein Punkt der Ebene [metresEast] Meter oestlich und [metresNorth] Meter noerdlich von der Position (50 N, 10 E), in Einheiten der Ebene. */
    private val unitsPerMetre = 1.0 / (6_371_000.0 * Math.cos(Math.toRadians(50.0)))
    private val px = projectLon(10.0, 10.0)
    private val py = projectLat(50.0)

    private fun ahead(view: TrackView, metresEast: Double, metresNorth: Double): ScreenPoint =
        toScreen(view, w, h, px + metresEast * unitsPerMetre, py + metresNorth * unitsPerMetre)

    // ── Drehung ───────────────────────────────────────────────────────────────

    @Test
    fun withoutRotationNorthIsUp_exactlyAsBefore() {
        val v = TrackView(cx = 1.0, cy = 2.0, pxPerUnit = 10.0)
        val p = toScreen(v, 400.0, 200.0, 1.0 + 5.0, 2.0 + 3.0)
        assertEquals(400.0 / 2 + 50.0, p.x, 1e-9)
        assertEquals(200.0 / 2 - 30.0, p.y, 1e-9)
    }

    @Test
    fun theCourseThatIsUpIsReallyDrawnUp() {
        val v = frame.viewFor(HeadingViewState(), px, py, rotationDeg = 90.0)
        val east = ahead(v, 100.0, 0.0)
        val here = ahead(v, 0.0, 0.0)
        assertEquals("Osten oben: gleiche Breite", here.x, east.x, 1e-6)
        assertTrue("und hoeher im Bild", east.y < here.y - 10)
    }

    @Test
    fun withTheCourseNorth_northIsUp_andWithTheCourseSouth_itIsDown() {
        val north = frame.viewFor(HeadingViewState(), px, py, 0.0)
        assertTrue(ahead(north, 0.0, 100.0).y < ahead(north, 0.0, 0.0).y)
        val south = frame.viewFor(HeadingViewState(), px, py, 180.0)
        assertTrue("nach Sueden gedreht liegt Norden unten", ahead(south, 0.0, 100.0).y > ahead(south, 0.0, 0.0).y)
        assertEquals(ahead(south, 0.0, 100.0).x, ahead(south, 0.0, 0.0).x, 1e-6)
    }

    @Test
    fun theRotationKeepsDistances() {
        val v = frame.viewFor(HeadingViewState(), px, py, 37.0)
        val a = ahead(v, 0.0, 0.0)
        val b = ahead(v, 120.0, 90.0)
        val v0 = frame.viewFor(HeadingViewState(), px, py, 0.0)
        val a0 = ahead(v0, 0.0, 0.0)
        val b0 = ahead(v0, 120.0, 90.0)
        assertEquals(hypot(b0.x - a0.x, b0.y - a0.y), hypot(b.x - a.x, b.y - a.y), 1e-6)
    }

    @Test
    fun thePositionAnchor_standsInTheLowerPartOfTheImage_butNotAtTheEdge() {
        assertTrue("unter der Mitte, damit mehr Strecke voraus zu sehen ist", HEADING_ANCHOR_Y > 0.55)
        assertTrue("aber nicht am unteren Rand", HEADING_ANCHOR_Y <= 0.8)
    }


    // ── Position im Bild ──────────────────────────────────────────────────────

    @Test
    fun atRest_thePositionStandsLowInTheMiddle() {
        val v = frame.viewFor(HeadingViewState(), px, py, 123.0)
        val p = ahead(v, 0.0, 0.0)
        assertEquals(w / 2, p.x, 1e-6)
        assertEquals(h * HEADING_ANCHOR_Y, p.y, 1e-6)
    }

    @Test
    fun theShiftedPositionStandsWhereTheStateSaysItDoes_forEveryRotation() {
        val state = HeadingViewState(zoom = 2.0, offsetX = 70.0, offsetY = -40.0)
        for (rotation in listOf(0.0, 30.0, 90.0, 215.0, 350.0)) {
            val p = ahead(frame.viewFor(state, px, py, rotation), 0.0, 0.0)
            val m = frame.markerOf(state)
            assertEquals("x bei $rotation", m.x, p.x, 1e-6)
            assertEquals("y bei $rotation", m.y, p.y, 1e-6)
        }
    }

    @Test
    fun theDefaultScaleShowsAboutFourHundredMetresAcrossTheShortSide() {
        val v = frame.viewFor(HeadingViewState(), px, py, 0.0)
        val shortSidePx = minOf(w, h)
        val metresPerPx = metresPerPx(v, 50.0)
        assertEquals(HEADING_DEFAULT_SPAN_M, metresPerPx * shortSidePx, 1.0)
        val zoomed = frame.viewFor(HeadingViewState(zoom = 4.0), px, py, 0.0)
        assertEquals(HEADING_DEFAULT_SPAN_M / 4, metresPerPx(zoomed, 50.0) * shortSidePx, 0.5)
    }

    // ── Verschieben ───────────────────────────────────────────────────────────

    @Test
    fun aFingerMovesTheContent_thePositionFollows() {
        val moved = frame.pannedBy(HeadingViewState(), 30.0, -20.0)
        assertEquals(30.0, moved.offsetX, 1e-9)
        assertEquals(-20.0, moved.offsetY, 1e-9)
    }

    @Test
    fun thePositionCanNeverBePushedOutOfTheImage() {
        var s = HeadingViewState()
        repeat(40) { s = frame.pannedBy(s, 500.0, 500.0) }
        val m = frame.markerOf(s)
        assertEquals("rechts bleibt der Rand", w - 40.0, m.x, 1e-9)
        assertEquals("unten bleibt der Rand", h - 40.0, m.y, 1e-9)
        repeat(40) { s = frame.pannedBy(s, -2000.0, -2000.0) }
        val n = frame.markerOf(s)
        assertEquals(40.0, n.x, 1e-9)
        assertEquals(40.0, n.y, 1e-9)
    }

    @Test
    fun panningKeepsTheZoom() {
        assertEquals(3.0, frame.pannedBy(HeadingViewState(zoom = 3.0), 10.0, 10.0).zoom, 0.0)
    }

    // ── Zoomen ────────────────────────────────────────────────────────────────

    @Test
    fun zoomingAroundAPoint_keepsThatPointOfTheMapWhereItIs() {
        val before = HeadingViewState(zoom = 1.0, offsetX = 20.0, offsetY = 10.0)
        val rotation = 60.0
        val viewBefore = frame.viewFor(before, px, py, rotation)
        // Ein Punkt der Karte 30 m oestlich und 20 m noerdlich (nahe genug, dass die Position beim Zoomen im Bild bleibt), und wo er jetzt im Bild steht: um genau diesen Punkt wird gezoomt.
        val focus = ahead(viewBefore, 30.0, 20.0)
        val after = frame.zoomedAround(before, 2.0, focus.x, focus.y)
        assertEquals(2.0, after.zoom, 0.0)
        val focusAfter = ahead(frame.viewFor(after, px, py, rotation), 30.0, 20.0)
        assertEquals(focus.x, focusAfter.x, 1e-6)
        assertEquals(focus.y, focusAfter.y, 1e-6)
    }

    @Test
    fun theZoomStaysBetweenItsLimits() {
        var s = HeadingViewState()
        repeat(20) { s = frame.zoomedAround(s, 2.0, w / 2, h / 2) }
        assertEquals(HEADING_MAX_ZOOM, s.zoom, 0.0)
        repeat(40) { s = frame.zoomedAround(s, 0.5, w / 2, h / 2) }
        assertEquals(HEADING_MIN_ZOOM, s.zoom, 0.0)
    }

    @Test
    fun aGestureZoomsFirstAndThenMoves() {
        val s = frame.transformedBy(HeadingViewState(), w / 2, h * HEADING_ANCHOR_Y, 25.0, -15.0, 2.0)
        // Der Mittelpunkt der Finger liegt auf der Position: sie bleibt dort, dann kommt die Verschiebung.
        assertEquals(2.0, s.zoom, 0.0)
        assertEquals(25.0, s.offsetX, 1e-9)
        assertEquals(-15.0, s.offsetY, 1e-9)
    }

    // ── Kuerzester Drehweg ────────────────────────────────────────────────────

    @Test
    fun theMapTurnsTheShortWay() {
        assertEquals(20.0, shortestTurnDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, shortestTurnDeg(10.0, 350.0), 1e-9)
        assertEquals(90.0, shortestTurnDeg(0.0, 90.0), 1e-9)
        assertEquals(180.0, shortestTurnDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, shortestTurnDeg(720.0, 0.0), 1e-9)
        assertEquals(-90.0, shortestTurnDeg(0.0, -90.0), 1e-9)
        assertEquals("ueber mehrere Umdrehungen", 10.0, shortestTurnDeg(1075.0, 1085.0), 1e-9)
    }

    // ── Abstandstext neben der Linie ──────────────────────────────────────────

    private fun boxContains(box: ScreenPoint, textW: Double, textH: Double, x: Double, y: Double) =
        x >= box.x && x <= box.x + textW && y >= box.y && y <= box.y + textH

    private fun lineTouchesTheBox(fx: Double, fy: Double, qx: Double, qy: Double, box: ScreenPoint, textW: Double, textH: Double): Boolean =
        (0..200).any { k -> boxContains(box, textW, textH, fx + (qx - fx) * k / 200.0, fy + (qy - fy) * k / 200.0) }

    @Test
    fun theLabelSitsBesideTheLine_notOnIt_forEveryDirection() {
        val tw = 60.0
        val th = 24.0
        for (angle in 0 until 360 step 15) {
            val rad = Math.toRadians(angle.toDouble())
            val fx = 400.0
            val fy = 300.0
            val qx = fx + 90 * Math.sin(rad)
            val qy = fy - 90 * Math.cos(rad)
            val box = distanceLabelTopLeft(fx, fy, qx, qy, tw, th, gapPx = 6.0, widthPx = 800.0, heightPx = 600.0, padPx = 4.0)
            assertFalse("Winkel $angle: der Text liegt auf der Linie", lineTouchesTheBox(fx, fy, qx, qy, box, tw, th))
        }
    }

    @Test
    fun theLabelPrefersTheSideAboveTheLine() {
        val box = distanceLabelTopLeft(400.0, 300.0, 500.0, 300.0, 60.0, 24.0, 6.0, 800.0, 600.0, 4.0)
        assertTrue("ueber der waagerechten Linie", box.y + 24.0 <= 300.0)
        assertEquals("mittig ueber der Linie", 450.0, box.x + 30.0, 1e-9)
    }

    @Test
    fun aLabelThatWouldLeaveTheImageGoesToTheOtherSide() {
        // Die Linie liegt ganz oben: ueber ihr ist kein Platz, der Text steht darunter.
        val box = distanceLabelTopLeft(400.0, 12.0, 500.0, 12.0, 60.0, 24.0, 6.0, 800.0, 600.0, 4.0)
        assertTrue("unter der Linie", box.y > 12.0)
        assertTrue("im Bild", box.y >= 4.0 && box.y + 24.0 <= 600.0 - 4.0)
    }

    @Test
    fun aLabelNeverLeavesTheImage() {
        val box = distanceLabelTopLeft(10.0, 10.0, 30.0, 10.0, 60.0, 24.0, 6.0, 100.0, 80.0, 4.0)
        assertTrue(box.x >= 4.0 && box.x + 60.0 <= 100.0 - 4.0)
        assertTrue(box.y >= 4.0 && box.y + 24.0 <= 80.0 - 4.0)
    }

    @Test
    fun aVerticalLineGetsTheLabelBesideIt() {
        val box = distanceLabelTopLeft(400.0, 200.0, 400.0, 320.0, 60.0, 24.0, 6.0, 800.0, 600.0, 4.0)
        assertFalse(lineTouchesTheBox(400.0, 200.0, 400.0, 320.0, box, 60.0, 24.0))
        assertTrue("auf Hoehe der Mitte der Linie", abs((box.y + 12.0) - 260.0) < 1e-9)
    }
}
