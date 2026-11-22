package de.adv990de.obd2.ui.tracks

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.centerRight
import androidx.compose.ui.test.centerLeft
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.FollowTrackState
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Die Seite „Nachfahren“ auf dem Geraet: Zahlen, Richtung, Hinweise, die Zustaende der Strecke und der Marker als Bildpunkte. Alle Koordinaten sind erfunden. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrackFollowScreenDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    @After
    fun unlockOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private val points = List(30) { i -> TrackPoint(null, 50.0 + 0.002 * Math.sin(i / 5.0), 10.0 + 0.0005 * i, null, null, null) }
    private val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    private val ready = FollowTrackState.Ready("Sonntagsrunde", points, points, bounds)

    private fun following(off: Double = 12.0, along: Double = 2_300.0, left: Double = 3_850.0) = FollowReading.Following(
        points[10],
        FollowResult(distanceToTrackM = off, alongM = along, remainingM = left, totalM = along + left, segmentIndex = 10, bearingDeg = 80.0),
    )

    private fun show(
        track: FollowTrackState = ready,
        reading: FollowReading = FollowReading.Searching,
        direction: FollowDirection = FollowDirection.FORWARD,
        permissionMissing: Boolean = false,
        onDirection: (FollowDirection) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                TrackFollowContent(track, direction, reading, permissionMissing, onDirection, onBack = {}, onOpenSettings = onOpenSettings, onStop = onStop)
            }
        }
        composeRule.waitForIdle()
    }

    // ── Zahlen ────────────────────────────────────────────────────────────────

    @Test
    fun aFollowingReading_showsDistanceCoveredAndRemaining() {
        show(reading = following(off = 12.4, along = 2_300.0, left = 3_850.0))

        composeRule.onNodeWithTag(FOLLOW_STAT_OFF_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(12, locale))
        composeRule.onNodeWithTag(FOLLOW_STAT_DONE_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(2_300, locale))
        composeRule.onNodeWithTag(FOLLOW_STAT_LEFT_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(3_850, locale))
        composeRule.onNodeWithTag(FOLLOW_PROGRESS_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_SEARCHING_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theNumbers_areRoundedToWholeMetres() {
        show(reading = following(off = 12.6, along = 99.5, left = 0.4))
        composeRule.onNodeWithTag(FOLLOW_STAT_OFF_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(13, locale))
        composeRule.onNodeWithTag(FOLLOW_STAT_DONE_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(100, locale))
        composeRule.onNodeWithTag(FOLLOW_STAT_LEFT_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatTrackDistance(0, locale))
    }

    @Test
    fun withoutAPosition_thePageSaysItIsWaiting_andShowsNoNumbers() {
        show(reading = FollowReading.Searching)

        composeRule.onNodeWithTag(FOLLOW_SEARCHING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_searching)).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_STAT_DONE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withoutPermission_theHintLeadsToTheSettings_andNoNumbersAreShown() {
        var opened = 0
        show(reading = FollowReading.NoPermission, permissionMissing = true, onOpenSettings = { opened++ })

        composeRule.onNodeWithTag(FOLLOW_PERMISSION_HINT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_permission)).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_SEARCHING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FOLLOW_STAT_DONE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.settings_permission_open_settings)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theNoteThatThePositionIsNotStored_isAlwaysThere() {
        show(reading = following())
        composeRule.onNodeWithTag(FOLLOW_NOTE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_note)).assertIsDisplayed()
    }

    @Test
    fun theNumbersAreSpokenAsOneSentence() {
        show(reading = following(off = 12.0, along = 2_300.0, left = 3_850.0))
        val expected = string(R.string.track_follow_summary, formatTrackDistance(12, locale), formatTrackDistance(2_300, locale), formatTrackDistance(3_850, locale))
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription(expected)).assertExists()
    }

    // ── Richtung ──────────────────────────────────────────────────────────────

    @Test
    fun theDirectionSegments_are56dp_showTheChoice_andReportTheTap() {
        val chosen = mutableListOf<FollowDirection>()
        show(direction = FollowDirection.FORWARD, onDirection = { chosen += it })

        composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).assertIsSelected().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithTag(FOLLOW_DIRECTION_REVERSE_TEST_TAG).assertIsNotSelected().assertTouchTargetAtLeast56(composeRule.density)

        composeRule.onNodeWithTag(FOLLOW_DIRECTION_REVERSE_TEST_TAG).performClick()
        assertEquals(listOf(FollowDirection.REVERSE), chosen)
    }

    @Test
    fun theReverseDirection_isSelectedWhenGiven() {
        show(direction = FollowDirection.REVERSE)
        composeRule.onNodeWithTag(FOLLOW_DIRECTION_REVERSE_TEST_TAG).assertIsSelected()
        composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).assertIsNotSelected()
    }

    // ── Zustaende der Strecke ─────────────────────────────────────────────────

    @Test
    fun theTrackStates_sayWhatIsWrong() {
        show(track = FollowTrackState.NotFound)
        composeRule.onNodeWithText(string(R.string.track_detail_not_found)).assertIsDisplayed()
    }

    @Test
    fun aTrackThatCouldNotBeRead_saysSo() {
        show(track = FollowTrackState.Failed)
        composeRule.onNodeWithText(string(R.string.track_detail_failed)).assertIsDisplayed()
    }

    @Test
    fun aTooShortTrack_saysItCannotBeFollowed_withItsName() {
        show(track = FollowTrackState.TooShort("Kurz"))
        composeRule.onNodeWithTag(FOLLOW_TOO_SHORT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Kurz").assertIsDisplayed()
    }

    @Test
    fun aReadyTrack_showsItsNameAndTheMap() {
        show()
        composeRule.onNodeWithText("Sonntagsrunde").assertIsDisplayed()
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).assertIsDisplayed()
    }

    // ── Ausrichtung ───────────────────────────────────────────────────────────

    /** setContent darf je Test nur einmal laufen (steht das Telefon schon in der Ausrichtung, baut sie die Activity nicht neu): je Ausrichtung ein Test. */
    private fun numbersMapAndDirectionAreAllVisibleIn(orientation: Int) {
        requestOrientation(orientation)
        show(reading = following())
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_STAT_LEFT_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun inPortrait_numbersMapAndDirectionAreAllVisible() = numbersMapAndDirectionAreAllVisibleIn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    @Test
    fun inLandscape_numbersMapAndDirectionAreAllVisible() = numbersMapAndDirectionAreAllVisibleIn(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    // ── Marker als Bildpunkte ─────────────────────────────────────────────────

    private val colors = lightColorScheme()

    private fun showMap(marker: TrackMarker?, orientable: Boolean = false, autoSpanM: Double? = null) {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), marker, orientable, autoSpanM)
            }
        }
        composeRule.waitForIdle()
    }

    private fun Color.near(other: Color, tolerance: Float = 0.06f) =
        abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance

    /** Wo [p] im Bild der Zeichnung liegt (Ansicht angepasst, wie sie beim Oeffnen ist). */
    private fun screenOf(p: TrackPoint): Pair<Double, Double> {
        val image = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage()
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val fit = fitView(bounds, w, h, paddingPx = 32 * composeRule.density.density.toDouble())
        val s = toScreen(TrackViewState().resolve(fit), w, h, projectLon(p.lon, bounds.minLon), projectLat(p.lat))
        return s.x to s.y
    }

    /** Ist an `centre + (dir * ahead + side * across)` (in dp) die Farbe des Markers? [bearing] 0 = Nord, im Uhrzeigersinn. */
    private fun markerAt(centre: Pair<Double, Double>, bearing: Double, aheadDp: Double, acrossDp: Double): Boolean {
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        val d = composeRule.density.density
        val rad = Math.toRadians(bearing)
        val dx = sin(rad)
        val dy = -cos(rad)
        val x = (centre.first + (dx * aheadDp + (-dy) * acrossDp) * d).roundToInt()
        val y = (centre.second + (dy * aheadDp + dx * acrossDp) * d).roundToInt()
        if (x !in 0 until map.width || y !in 0 until map.height) return false
        return map[x, y].near(StatusColors.OwnPosition)
    }

    @Test
    fun theMarker_isDrawnAtThePositionAndPointsAlongTheTrack() {
        val position = points[10]
        showMap(TrackMarker(position, bearingDeg = 90.0))
        val centre = screenOf(position)

        assertTrue("in der Pfeilspitze, 8 dp voraus", markerAt(centre, 90.0, aheadDp = 8.0, acrossDp = 0.0))
        assertFalse("nicht quer daneben (8 dp seitlich der Kerbe)", markerAt(centre, 90.0, aheadDp = 0.0, acrossDp = 8.0))
        assertFalse("nicht hinter dem Pfeil", markerAt(centre, 90.0, aheadDp = -14.0, acrossDp = 0.0))
    }

    @Test
    fun theMarker_turnsWithTheBearing() {
        val position = points[10]
        showMap(TrackMarker(position, bearingDeg = 0.0))
        val centre = screenOf(position)
        assertTrue("Nord: die Spitze zeigt nach oben", markerAt(centre, 0.0, aheadDp = 8.0, acrossDp = 0.0))
        assertFalse("Nord: rechts der Kerbe ist nichts", markerAt(centre, 0.0, aheadDp = 0.0, acrossDp = 8.0))
        // dieselbe Stelle im Bild, als Ost gelesen: dort liegt bei Nord nichts
        assertFalse("kein Marker 8 dp rechts der Mitte", markerAt(centre, 90.0, aheadDp = 8.0, acrossDp = 0.0))
    }

    @Test
    fun withoutAMarker_nothingIsDrawnAtThatPlace() {
        val position = points[10]
        showMap(null)
        val centre = screenOf(position)
        assertFalse(markerAt(centre, 90.0, aheadDp = 8.0, acrossDp = 0.0))
    }

    @Test
    fun theMarker_isAlsoThereOnAFollowPreviewTheme() {
        // PreviewTheme (Hell und Dunkel) komponiert die Seite mit dem Marker ohne Fehler: die Zeichnung haengt nur an Farben des Themes.
        composeRule.setContent { PreviewTheme(dark = true) { TrackFollowContent(ready, FollowDirection.FORWARD, following(), false, {}, {}) } }
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).assertIsDisplayed()
    }

    // ── Rueckwaerts: die Zeichnung folgt der Richtung ─────────────────────────

    /**
     * Eine Schleife, deren Anfang links oben und deren Ende links in der Mitte liegt: beide Enden liegen frei (rechts oben der Nordpfeil, unten rechts die Knoepfe, unten links die Leiste decken
     * sonst die Marke ab).
     */
    private val loop = listOf(
        TrackPoint(null, 50.002, 10.000, null, null, null),
        TrackPoint(null, 50.002, 10.010, null, null, null),
        TrackPoint(null, 49.998, 10.010, null, null, null),
        TrackPoint(null, 49.998, 10.000, null, null, null),
        TrackPoint(null, 50.000, 10.000, null, null, null),
    )
    private val loopBounds = TrackBounds(loop.minOf { it.lat }, loop.maxOf { it.lat }, loop.minOf { it.lon }, loop.maxOf { it.lon })
    private val loopReady = FollowTrackState.Ready("Schleife", loop, loop, loopBounds)

    private fun showLoop(direction: FollowDirection) {
        composeRule.setContent { AppTheme(colorScheme = colors, typography = AppTypography) { TrackFollowContent(loopReady, direction, FollowReading.Searching, false, {}, {}) } }
        composeRule.waitForIdle()
    }

    private fun startMarkAt(p: TrackPoint): Boolean {
        val image = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage()
        val fit = fitView(loopBounds, image.width.toDouble(), image.height.toDouble(), paddingPx = 32 * composeRule.density.density.toDouble())
        val s = toScreen(TrackViewState().resolve(fit), image.width.toDouble(), image.height.toDouble(), projectLon(p.lon, loopBounds.minLon), projectLat(p.lat))
        val map = image.toPixelMap()
        val px = s.x.roundToInt()
        val py = s.y.roundToInt()
        if (px !in 0 until map.width || py !in 0 until map.height) return false
        return map[px, py].near(colors.tertiary)
    }

    @Test
    fun forwards_theStartMarkSitsAtTheRecordedStart() {
        showLoop(FollowDirection.FORWARD)
        assertTrue("Start am aufgezeichneten Anfang", startMarkAt(loop.first()))
        assertFalse("nicht am aufgezeichneten Ende", startMarkAt(loop.last()))
    }

    @Test
    fun backwards_theStartAndGoalMarksChangePlaces() {
        showLoop(FollowDirection.REVERSE)
        assertTrue("rueckwaerts faehrt man am aufgezeichneten Ende los", startMarkAt(loop.last()))
        assertFalse("und der aufgezeichnete Anfang ist das Ziel", startMarkAt(loop.first()))
    }

    @Test
    fun switchingTheDirection_redrawsTheMarks() {
        var direction by androidx.compose.runtime.mutableStateOf(FollowDirection.FORWARD)
        composeRule.setContent { AppTheme(colorScheme = colors, typography = AppTypography) { TrackFollowContent(loopReady, direction, FollowReading.Searching, false, { direction = it }, {}) } }
        composeRule.waitForIdle()
        assertTrue(startMarkAt(loop.first()))

        composeRule.onNodeWithTag(FOLLOW_DIRECTION_REVERSE_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue("nach dem Tipp auf „Rueckwaerts“ sitzt der Start am anderen Ende", startMarkAt(loop.last()))

        composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue("und zurueck", startMarkAt(loop.first()))
    }

    // ── Position und Abstand in der Karte ─────────────────────────────────────

    private fun pixelAt(x: Double, y: Double): Color? {
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        val px = x.roundToInt()
        val py = y.roundToInt()
        return if (px in 0 until map.width && py in 0 until map.height) map[px, py] else null
    }

    /** Ein Prozentsatz der Stuetzstellen zwischen [a] und [b] (ohne die Enden), an denen die Farbe des Markers liegt: die gestrichelte Linie. */
    private fun dashedShare(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val n = 60
        val hits = (8..n - 8).count { k ->
            val t = k / n.toDouble()
            pixelAt(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)?.near(StatusColors.OwnPosition, 0.08f) == true
        }
        return hits / (n - 15).toDouble()
    }

    private val foot = points[10]
    private val awayFromTheTrack = TrackPoint(null, foot.lat + 0.0012, foot.lon, null, null, null)

    @Test
    fun theDistanceLine_leadsFromThePositionToTheFootOnTheTrack() {
        showMap(TrackMarker(awayFromTheTrack, bearingDeg = 90.0, foot = foot, distanceLabel = "133 m"))
        val a = screenOf(awayFromTheTrack)
        val b = screenOf(foot)
        assertTrue("die Linie ist gestrichelt gezeichnet, also teils in der Farbe des Markers", dashedShare(a, b) > 0.25)
        val ring = pixelAt(b.first, b.second)
        assertTrue("am Fusspunkt sitzt ein Punkt", ring != null && ring.near(StatusColors.OwnPosition, 0.08f))
    }

    @Test
    fun withoutAFootPoint_thereIsNoDistanceLine() {
        showMap(TrackMarker(awayFromTheTrack, bearingDeg = 90.0))
        assertTrue("keine Linie", dashedShare(screenOf(awayFromTheTrack), screenOf(foot)) < 0.05)
    }

    @Test
    fun aPositionOnTheTrack_needsNoDistanceLine() {
        showMap(TrackMarker(foot, bearingDeg = 90.0, foot = foot, distanceLabel = "0 m"))
        val (x, y) = screenOf(foot)
        // der Pfeil deckt den Fusspunkt: die Linie zu sich selbst wird nicht gezeichnet (kein Strich quer durch den Pfeil, kein Punkt daneben)
        assertTrue(markerAt(screenOf(foot), 90.0, aheadDp = 8.0, acrossDp = 0.0))
        assertFalse("kein Fusspunkt-Kreis neben dem Pfeil", pixelAt(x, y - 9 * composeRule.density.density)?.near(StatusColors.OwnPosition, 0.08f) == true && pixelAt(x, y + 9 * composeRule.density.density)?.near(StatusColors.OwnPosition, 0.08f) == true)
    }

    @Test
    fun aPositionFarOutsideTheFrame_isShownAtTheEdgeAndStillPointsAlongTheTrack() {
        val far = TrackPoint(null, foot.lat + 0.05, foot.lon + 0.05, null, null, null)
        showMap(TrackMarker(far, bearingDeg = 0.0, foot = foot, distanceLabel = "7 km"))
        val image = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage()
        val (x, y) = screenOf(far)
        assertTrue("die echte Stelle liegt ausserhalb des Bildes", x > image.width || x < 0 || y > image.height || y < 0)
        val shown = clampToFrame(x, y, image.width.toDouble(), image.height.toDouble(), 22 * composeRule.density.density.toDouble())
        assertTrue("der Pfeil sitzt am Rand, im Bild", shown.x in 0.0..image.width.toDouble() && shown.y in 0.0..image.height.toDouble())
        assertTrue("und ist dort gezeichnet (Spitze nach Norden)", markerAt(shown.x to shown.y, 0.0, aheadDp = 8.0, acrossDp = 0.0))
    }

    /** Wie viele Bildpunkte der Zeichnung in der Textfarbe stehen (der Text des Abstands; die Zielmarke ist in beiden Faellen gleich). */
    private fun markerColouredPixels(): Int {
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        var n = 0
        for (y in 0 until map.height) for (x in 0 until map.width) if (map[x, y].near(colors.onSurface, 0.08f)) n++
        return n
    }

    @Test
    fun theDistanceLabel_isDrawnNextToTheLine_andGoneWithoutIt() {
        var label by androidx.compose.runtime.mutableStateOf<String?>("1234 m")
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), TrackMarker(awayFromTheTrack, 90.0, foot, label))
            }
        }
        composeRule.waitForIdle()
        val withLabel = markerColouredPixels()

        label = null
        composeRule.waitForIdle()
        val without = markerColouredPixels()

        assertTrue("der Text des Abstands zeichnet Bildpunkte (mit $withLabel, ohne $without)", withLabel > without + 40)
    }

    @Test
    fun aPositionOffTheTrack_onTheFullPage_showsTheLineToTheTrack() {
        // Die ganze Seite, nicht nur die Karte: der Fusspunkt des Ergebnisses muss bis zur Zeichnung durchgereicht werden. Hochformat, damit die Karte hoch genug fuer die Strecke ueber dem Track ist.
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val reading = FollowReading.Following(
            awayFromTheTrack,
            FollowResult(distanceToTrackM = 133.0, alongM = 2_300.0, remainingM = 3_850.0, totalM = 6_150.0, segmentIndex = 10, bearingDeg = 80.0, foot = foot),
        )
        composeRule.setContent { AppTheme(colorScheme = colors, typography = AppTypography) { TrackFollowContent(ready, FollowDirection.FORWARD, reading, false, {}, {}) } }
        composeRule.waitForIdle()

        // Die Schilder des Abstands decken einen Teil der Linie ab: der Anteil liegt unter dem der blossen Karte, aber weit ueber 0 (ohne Fusspunkt 0).
        val share = dashedShare(screenOf(awayFromTheTrack), screenOf(foot))
        assertTrue("die gestrichelte Linie fuehrt von der Position zum Track (Anteil $share)", share > 0.12)
        val (fx, fy) = screenOf(foot)
        assertTrue("am Fusspunkt sitzt ein Punkt in der Farbe des Markers", pixelAt(fx, fy)?.near(StatusColors.OwnPosition, 0.08f) == true)
    }

    @Test
    fun aPositionCloseToTheTrack_needsNoLine_andNoDotAtTheFoot() {
        // 20 dp neben dem Track: unter der Mindestlaenge der Linie (28 dp). Der Fusspunkt liegt auf der Linie des Tracks; ein Kreis in der Farbe des Markers waere dort zu sehen.
        val f = fitView(bounds, 400.0 * composeRule.density.density, 300.0 * composeRule.density.density, paddingPx = 32 * composeRule.density.density.toDouble())
        val unitsFor20Dp = 20 * composeRule.density.density / f.pxPerUnit
        val near = TrackPoint(null, unprojectLat(projectLat(foot.lat) + unitsFor20Dp), foot.lon, null, null, null)
        showMap(TrackMarker(near, bearingDeg = 0.0, foot = foot, distanceLabel = "5 m"))

        val (x, y) = screenOf(foot)
        assertFalse("am Fusspunkt steht kein Punkt in der Farbe des Markers", pixelAt(x, y)?.near(StatusColors.OwnPosition, 0.08f) == true)
        assertTrue("dort liegt die Linie des Tracks", pixelAt(x, y)?.near(colors.primary) == true)
    }

    // ── Der Abstandstext liegt neben der Linie ────────────────────────────────

    @Test
    fun theDistanceLabel_doesNotCoverTheLine() {
        var label by androidx.compose.runtime.mutableStateOf<String?>("133 m")
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), TrackMarker(awayFromTheTrack, 90.0, foot, label))
            }
        }
        composeRule.waitForIdle()
        val a = screenOf(awayFromTheTrack)
        val b = screenOf(foot)
        val withLabel = dashedShare(a, b)
        label = null
        composeRule.waitForIdle()
        val without = dashedShare(a, b)
        assertTrue("die Linie ist mit Text so sichtbar wie ohne (mit $withLabel, ohne $without)", withLabel >= without - 0.05)
    }

    // ── Position in Blau ──────────────────────────────────────────────────────

    @Test
    fun thePosition_hasItsOwnColour_notThatOfTheGoalMark() {
        assertFalse("die Farbe der Position ist nicht die der Zielmarke", StatusColors.OwnPosition.near(colors.onSurface, 0.2f))
        assertFalse("und nicht die der Linie", StatusColors.OwnPosition.near(colors.primary, 0.2f))
        assertFalse("und nicht die der Startmarke", StatusColors.OwnPosition.near(colors.tertiary, 0.2f))
    }

    // ── Ausrichtung der Karte ─────────────────────────────────────────────────

    private val here = points[10]
    private val orientationMarker = TrackMarker(here, bearingDeg = 90.0)

    private fun canvasSize(): Pair<Double, Double> {
        val image = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage()
        return image.width.toDouble() to image.height.toDouble()
    }

    private fun headingFrame(): HeadingFrame {
        val (w, h) = canvasSize()
        return HeadingFrame(w, h, marginPx = 22 * composeRule.density.density.toDouble())
    }

    /** Der Schwerpunkt der Bildpunkte in der Farbe der Position: bei einem Marker ohne Linie der Pfeil. */
    private fun markerCentroid(): Pair<Double, Double> {
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until map.height) for (x in 0 until map.width) if (map[x, y].near(StatusColors.OwnPosition, 0.08f)) { n++; sx += x; sy += y }
        assertTrue("der Marker ist gezeichnet", n > 20)
        return sx / n to sy / n
    }

    private fun toggleOrientation() {
        composeRule.onNodeWithTag(TRACK_ORIENTATION_TEST_TAG).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun theOrientationButton_isThereWhenTheMapCanBeOriented_with56dp() {
        showMap(orientationMarker, orientable = true)
        composeRule.onNodeWithTag(TRACK_ORIENTATION_TEST_TAG).assertIsDisplayed().assertTouchTargetAtLeast56(composeRule.density)
    }

    @Test
    fun theOrientationButton_isNotThereWithoutThat() {
        showMap(orientationMarker, orientable = false)
        composeRule.onNodeWithTag(TRACK_ORIENTATION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun inTheDirectionOfTravelMode_thePositionStandsLowInTheMiddle_andItsArrowPointsUp() {
        showMap(orientationMarker, orientable = true)
        // Norden oben: der Pfeil zeigt nach Osten (Kurs 90), nicht nach oben.
        assertTrue(markerAt(screenOf(here), 90.0, aheadDp = 8.0, acrossDp = 0.0))
        toggleOrientation()

        val (w, h) = canvasSize()
        val (cx, cy) = markerCentroid()
        assertEquals("waagerecht in der Mitte", w / 2, cx, 4.0 * composeRule.density.density)
        assertTrue("und tief im Bild (Pfeil um 65 % der Hoehe)", abs(cy - h * HEADING_ANCHOR_Y) < 12 * composeRule.density.density)
        assertTrue("der Pfeil zeigt jetzt nach oben", markerAt(w / 2 to h * HEADING_ANCHOR_Y, 0.0, aheadDp = 8.0, acrossDp = 0.0))
    }

    @Test
    fun inTheDirectionOfTravelMode_theTrackIsDrawnSoTheBearingIsUp() {
        showMap(orientationMarker, orientable = true)
        toggleOrientation()
        // Der Track laeuft nach Osten (Kurs 90): ein Punkt gut 35 m weiter vorn muss jetzt ueber der Position liegen, auf der Linie (nicht unter dem Knopffeld rechts unten).
        val ahead = points[11]
        val frame = headingFrame()
        val view = frame.viewFor(HeadingViewState(), projectLon(here.lon, bounds.minLon), projectLat(here.lat), rotationDeg = 90.0)
        val p = toScreen(view, frame.widthPx, frame.heightPx, projectLon(ahead.lon, bounds.minLon), projectLat(ahead.lat))
        assertTrue("vorn liegt oben im Bild", p.y < frame.heightPx * HEADING_ANCHOR_Y - 20)
        assertTrue("dort laeuft die Linie des Tracks (bei ${p.x}, ${p.y})", pixelAt(p.x, p.y)?.near(colors.primary) == true)
    }

    @Test
    fun inTheDirectionOfTravelMode_aFingerMovesTheMap_andThePositionFollowsIt() {
        showMap(orientationMarker, orientable = true)
        toggleOrientation()
        val (x0, y0) = markerCentroid()
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { swipe(start = center, end = center.copy(x = center.x - 160f, y = center.y - 50f), durationMillis = 300) }
        composeRule.waitForIdle()
        val (x1, y1) = markerCentroid()
        assertTrue("die Position ist nach links gewandert (vorher $x0, nachher $x1)", x1 < x0 - 60)
        assertTrue("und nach oben (vorher $y0, nachher $y1)", y1 < y0 - 10)
    }

    @Test
    fun inTheDirectionOfTravelMode_thePositionCanNeverBeSweptOutOfTheImage() {
        showMap(orientationMarker, orientable = true)
        toggleOrientation()
        repeat(3) {
            composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { swipe(start = centerRight.copy(x = centerRight.x - 2f), end = centerLeft, durationMillis = 200) }
        }
        composeRule.waitForIdle()
        val (w, h) = canvasSize()
        val (x, y) = markerCentroid()
        assertTrue("die Position steht im Bild ($x, $y)", x in 0.0..w && y in 0.0..h)
    }

    @Test
    fun theMiddleButton_bringsThePositionBackToItsPlace_inTheDirectionOfTravelMode() {
        showMap(orientationMarker, orientable = true)
        toggleOrientation()
        val (x0, y0) = markerCentroid()
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { swipe(start = center, end = center.copy(x = center.x - 160f), durationMillis = 300) }
        composeRule.waitForIdle()
        assertTrue("verschoben", markerCentroid().first < x0 - 60)

        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        val (x1, y1) = markerCentroid()
        assertEquals(x0, x1, 3.0)
        assertEquals(y0, y1, 3.0)
    }

    @Test
    fun aDoubleTap_bringsThePositionBackToItsPlace_inTheDirectionOfTravelMode() {
        showMap(orientationMarker, orientable = true)
        toggleOrientation()
        val (x0, y0) = markerCentroid()
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { swipe(start = center, end = center.copy(x = center.x - 160f), durationMillis = 300) }
        composeRule.waitForIdle()
        assertTrue("verschoben", markerCentroid().first < x0 - 60)

        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()
        val (x1, y1) = markerCentroid()
        assertEquals(x0, x1, 3.0)
        assertEquals(y0, y1, 3.0)
    }

    @Test
    fun theMiddleButton_isAlwaysDescribedForWhatItDoes() {
        showMap(orientationMarker, orientable = true)
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))
        toggleOrientation()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_recentre_cd))
        composeRule.onNodeWithTag(TRACK_ORIENTATION_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_orientation_to_north))
        toggleOrientation()
        composeRule.onNodeWithTag(TRACK_ORIENTATION_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_orientation_to_heading))
    }

    @Test
    fun backToNorth_theFittedViewIsThereAgain() {
        showMap(orientationMarker, orientable = true)
        val fittedStart = screenOf(points[0])
        toggleOrientation()
        toggleOrientation()
        assertTrue("Norden oben: die Startmarke steht wieder am Anfang", run {
            val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
            val x = fittedStart.first.roundToInt()
            val y = fittedStart.second.roundToInt()
            x in 0 until map.width && y in 0 until map.height && map[x, y].near(colors.tertiary)
        })
        assertTrue("und der Pfeil zeigt wieder nach Osten", markerAt(screenOf(here), 90.0, aheadDp = 8.0, acrossDp = 0.0))
    }

    @Test
    fun withoutAPosition_theDirectionOfTravelModeStaysNorthUp() {
        showMap(null, orientable = true)
        val fittedStart = screenOf(points[0])
        toggleOrientation()
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        val x = fittedStart.first.roundToInt()
        val y = fittedStart.second.roundToInt()
        assertTrue("ohne Position kein Drehen: die Startmarke bleibt", x in 0 until map.width && y in 0 until map.height && map[x, y].near(colors.tertiary))
    }

    @Test
    fun theNorthArrow_pointsNorth_alsoWhenTheMapIsTurned() {
        showMap(orientationMarker, orientable = true)
        val d = composeRule.density.density.toDouble()
        val (w, _) = canvasSize()
        val pivotX = w - 44 * d
        val pivotY = 50 * d
        val ink = colors.onSurfaceVariant
        // Norden oben: die Pfeilspitze liegt ueber dem Drehpunkt.
        assertTrue("Nordpfeil zeigt nach oben", pixelAt(pivotX, pivotY - 6 * d)?.near(ink, 0.1f) == true)
        assertFalse(pixelAt(pivotX - 6 * d, pivotY)?.near(ink, 0.1f) == true)
        toggleOrientation()
        // Der Kurs ist Osten (90): Osten liegt oben, Norden damit links.
        assertTrue("Nordpfeil zeigt nach links", pixelAt(pivotX - 6 * d, pivotY)?.near(ink, 0.1f) == true)
        assertFalse(pixelAt(pivotX, pivotY - 6 * d)?.near(ink, 0.1f) == true)
    }

    // ── Richtung: die Woerter sind ganz zu lesen ──────────────────────────────

    private fun assertWholeTextShown(text: String) {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val r = results.first()
        assertEquals("„$text“ steht auf einer Zeile", 1, r.lineCount)
        assertEquals("„$text“ ist ganz da, nicht abgeschnitten", text.length, r.getLineEnd(0))
        assertFalse("„$text“ ragt nicht ueber seinen Platz", r.didOverflowWidth)
    }

    private fun directionWordsAreWhole(orientation: Int, fontScale: Float) {
        requestOrientation(orientation)
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale)) {
                AppTheme(colorScheme = colors, typography = AppTypography) { TrackFollowContent(ready, FollowDirection.FORWARD, following(), false, {}, {}) }
            }
        }
        composeRule.waitForIdle()
        assertWholeTextShown(string(R.string.track_follow_dir_forward))
        assertWholeTextShown(string(R.string.track_follow_dir_reverse))
    }

    @Test fun theDirectionWords_areWhole_inPortrait() = directionWordsAreWhole(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, 1f)

    @Test fun theDirectionWords_areWhole_inLandscape() = directionWordsAreWhole(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, 1f)

    @Test fun theDirectionWords_areWhole_inLandscapeWithALargeSystemFont() = directionWordsAreWhole(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, 1.3f)

    @Test
    fun theSelectedDirectionSegment_hasNoCheckmark_soTheWordStaysCentred() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        composeRule.setContent {
            AppTheme(colorScheme = colors, typography = AppTypography) { TrackFollowContent(ready, FollowDirection.FORWARD, following(), false, {}, {}) }
        }
        composeRule.waitForIdle()
        val segment = composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val word = composeRule.onNodeWithText(string(R.string.track_follow_dir_forward), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val d = composeRule.density.density
        assertEquals("das Wort steht in der Mitte des Segments (kein Haken daneben)", segment.center.x, word.center.x, 2f * d)
    }

    // ── Ohne Position bleibt die Ansicht „Fahrtrichtung oben“ aus ─────────────

    @Test
    fun withoutAPosition_theMiddleButtonStaysTheFitButton_afterTheToggle() {
        showMap(null, orientable = true)
        toggleOrientation()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))
    }

    // ── Der Abstandstext steht in der Naehe der Mitte der Linie ───────────────

    private fun inkPixels(): Set<Long> {
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).captureToImage().toPixelMap()
        val out = HashSet<Long>()
        for (y in 0 until map.height) for (x in 0 until map.width) if (map[x, y].near(colors.onSurface, 0.08f)) out += x.toLong() * 100_000 + y
        return out
    }

    @Test
    fun theDistanceLabel_sitsCloseToTheMiddleOfTheLine() {
        var label by androidx.compose.runtime.mutableStateOf<String?>("133 m")
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), TrackMarker(awayFromTheTrack, 90.0, foot, label))
            }
        }
        composeRule.waitForIdle()
        val with = inkPixels()
        label = null
        composeRule.waitForIdle()
        val text = with - inkPixels()
        assertTrue("der Text zeichnet Bildpunkte", text.size > 40)
        val cx = text.sumOf { it / 100_000 }.toDouble() / text.size
        val cy = text.sumOf { it % 100_000 }.toDouble() / text.size
        val a = screenOf(awayFromTheTrack)
        val b = screenOf(foot)
        val d = composeRule.density.density
        val dist = hypot(cx - (a.first + b.first) / 2, cy - (a.second + b.second) / 2)
        assertTrue("der Text liegt neben der Mitte der Linie (Abstand ${dist / d} dp)", dist < 60 * d)
    }

    // ── Roadbook-HUD und „Nachfahren beenden“ auf der Seite ────────────────────

    private val sampleHud = RoadbookHud(
        maneuver = Maneuver(220.0, 240.0, 260.0, 95.0, ManeuverKind.NORMAL), distanceM = 240.0, triggerM = 400.0, next = null, nextDistanceM = null,
        path = points.take(5), apex = points[3], position = points[0], bearingDeg = 0.0,
    )

    private fun followingWithHud() = following().copy(hud = sampleHud)

    @Test
    fun theStopButton_is56dp_andReportsTheTap() {
        var stops = 0
        show(reading = following(), onStop = { stops++ })
        composeRule.onNodeWithTag(FOLLOW_STOP_TEST_TAG).assertIsDisplayed().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithText(string(R.string.track_follow_stop)).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_STOP_TEST_TAG).performClick()
        assertEquals(1, stops)
    }

    @Test
    fun theStopButton_isThereWithoutAPositionToo() {
        show(reading = FollowReading.Searching)
        composeRule.onNodeWithTag(FOLLOW_STOP_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun aHudInTheReading_showsTheCompactHudOnTheMap_withoutItsOwnMap() {
        show(reading = followingWithHud())
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag(ROADBOOK_HUD_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ROADBOOK_MAP_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        val hud = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).getBoundsInRoot()
        val map = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).getBoundsInRoot()
        assertTrue("the HUD sits on the map (top ${hud.top}, map top ${map.top})", hud.top >= map.top && hud.bottom <= map.bottom && hud.right <= map.right)
    }

    @Test
    fun noHudInTheReading_noHudOnThePage() {
        show(reading = following())
        composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theHudDoesNotCoverTheControlsOrTheNorthArrowInBothOrientations() {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            requestOrientation(orientation)
            show(reading = followingWithHud())
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag(ROADBOOK_HUD_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }
            val hud = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).getBoundsInRoot()
            for (tag in listOf(TRACK_ZOOM_IN_TEST_TAG, TRACK_FIT_TEST_TAG, TRACK_ORIENTATION_TEST_TAG, TRACK_PAN_DOWN_TEST_TAG)) {
                val b = composeRule.onNodeWithTag(tag).getBoundsInRoot()
                val overlaps = hud.left < b.right && b.left < hud.right && hud.top < b.bottom && b.top < hud.bottom
                assertFalse("the HUD must not cover $tag in orientation $orientation", overlaps)
            }
        }
    }

    // ── Auto-Zoom: die Karte folgt der Position, auch in Norden oben ───────────

    private val inFollowing = points[5]

    /** Der Schwerpunkt des Markers in Dichte-unabhaengigen dp. */
    private fun markerCentroidDp(): Pair<Double, Double> {
        val (x, y) = markerCentroid()
        val d = composeRule.density.density
        return x / d to y / d
    }

    @Test
    fun withAnAutoSpan_northUpFollowsThePosition() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = 300.0)
        val (x, _) = markerCentroidDp()
        assertEquals("the position is in the middle of the map", 200.0, x, 4.0)
    }

    @Test
    fun withoutAnAutoSpan_northUpStaysTheOverview() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = null)
        val (x, _) = markerCentroidDp()
        assertTrue("the whole track is fitted, the position is left of the middle ($x)", x < 140.0)
    }

    @Test
    fun theCentreButton_switchesBetweenFollowingAndTheOverview() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = 300.0)
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))

        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_recentre_cd))
        assertTrue("the overview shows the whole track (${markerCentroidDp().first})", markerCentroidDp().first < 140.0)

        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))
        assertEquals(200.0, markerCentroidDp().first, 4.0)
    }

    @Test
    fun aManualZoomLeavesFollowing_andADoubleTapBringsItBack() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = 300.0)
        composeRule.onNodeWithTag(TRACK_ZOOM_IN_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_recentre_cd))

        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))
        assertEquals(200.0, markerCentroidDp().first, 4.0)
    }

    @Test
    fun aManualPanLeavesFollowing() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = 300.0)
        composeRule.onNodeWithTag(TRACK_PAN_LEFT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_recentre_cd))
    }

    @Test
    fun withoutAnAutoSpan_theCentreButtonStaysTheFitButton() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = null)
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).assertContentDescriptionEquals(string(R.string.track_fit_cd))
    }

    @Test
    fun withAnAutoSpan_headingUpKeepsThePositionAtItsAnchor() {
        showMap(TrackMarker(inFollowing, bearingDeg = 80.0), orientable = true, autoSpanM = 600.0)
        toggleOrientation()
        val (_, y) = markerCentroid()
        val (_, h) = canvasSize()
        assertEquals(HEADING_ANCHOR_Y * h, y, 0.03 * h)
    }
}
