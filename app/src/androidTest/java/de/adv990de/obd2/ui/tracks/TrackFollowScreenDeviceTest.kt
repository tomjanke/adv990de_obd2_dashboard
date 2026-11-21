package de.adv990de.obd2.ui.tracks

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.PreviewTheme
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
    ) {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                TrackFollowContent(track, direction, reading, permissionMissing, onDirection, onBack = {}, onOpenSettings = onOpenSettings)
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

    @Test
    fun inBothOrientations_numbersMapAndDirectionAreAllVisible() {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            requestOrientation(orientation)
            show(reading = following())
            composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(FOLLOW_DIRECTION_FORWARD_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(FOLLOW_STAT_LEFT_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    // ── Marker als Bildpunkte ─────────────────────────────────────────────────

    private val colors = lightColorScheme()

    private fun showMap(marker: TrackMarker?) {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), marker)
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
        return map[x, y].near(colors.onSurface)
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
}
