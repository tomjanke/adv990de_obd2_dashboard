package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.layout.size
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
 * die Ansicht in die richtige Richtung, „Anpassen“ und der Doppeltipp bringen sie zurueck. Nur erfundene Koordinaten.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrackMapDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val colors = lightColorScheme()

    /** Von links oben nach rechts unten: der Start liegt im Bild links oben, das Ziel rechts unten, in der Mitte ein Knick nach oben. */
    private val points = listOf(
        TrackPoint(null, 50.010, 10.000, null, null, null),
        TrackPoint(null, 50.012, 10.010, null, null, null),
        TrackPoint(null, 50.000, 10.020, null, null, null),
    )
    private val bounds = TrackBounds(minLat = 50.000, maxLat = 50.012, minLon = 10.000, maxLon = 10.020)

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

    /** Wo die Startmarke bei der Ansicht [state] sitzen muss, in Bildpunkten der Zeichnung. */
    private fun expectedStart(state: TrackViewState): Pair<Int, Int> {
        val image = canvas().captureToImage()
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val fit = fitView(bounds, w, h, paddingPx = 32 * density().toDouble())
        val p = toScreen(state.resolve(fit), w, h, projectLon(points[0].lon, bounds.minLon), projectLat(points[0].lat))
        return p.x.roundToInt() to p.y.roundToInt()
    }

    private fun Color.near(other: Color, tolerance: Float = 0.06f) =
        abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance

    private fun startMarkerAt(x: Int, y: Int): Boolean {
        val map = canvas().captureToImage().toPixelMap()
        if (x !in 0 until map.width || y !in 0 until map.height) return false
        return map[x, y].near(colors.tertiary)
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
        val map = canvas().captureToImage().toPixelMap()
        val line = (0 until map.height).flatMap { y -> (0 until map.width).filter { x -> map[x, y].near(colors.primary) }.map { x -> x to y } }
        assertTrue("Linie gezeichnet", line.size > 200)
    }

    @Test
    fun panLeft_movesTheContentRight_andFitBringsItBack() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())

        composeRule.onNodeWithTag(TRACK_PAN_LEFT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        val image = canvas().captureToImage()
        val step = minOf(image.width, image.height) * 0.25
        assertFalse("die Marke hat den alten Platz verlassen", startMarkerAt(x0, y0))
        assertTrue("und steht eine Viertelseite weiter rechts", startMarkerAt((x0 + step).roundToInt(), y0))

        composeRule.onNodeWithTag(TRACK_FIT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue("angepasst steht sie wieder am Anfang", startMarkerAt(x0, y0))
    }

    @Test
    fun panUp_movesTheContentDown() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())

        composeRule.onNodeWithTag(TRACK_PAN_UP_TEST_TAG).performClick()
        composeRule.waitForIdle()

        val image = canvas().captureToImage()
        val step = minOf(image.width, image.height) * 0.25
        assertTrue(startMarkerAt(x0, (y0 + step).roundToInt()))
    }

    @Test
    fun zoomOut_pullsTheMarkerTowardsTheCentre() {
        show()
        val zoomedOut = TrackViewState().let { s ->
            val image = canvas().captureToImage()
            val fit = fitView(bounds, image.width.toDouble(), image.height.toDouble(), 32 * density().toDouble())
            s.zoomedBy(0.5, fit)
        }
        val (x, y) = expectedStart(zoomedOut)

        composeRule.onNodeWithTag(TRACK_ZOOM_OUT_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue("Marke bei ($x, $y) nach dem Herauszoomen", startMarkerAt(x, y))
    }

    @Test
    fun aDoubleTap_fitsTheView_again() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())
        composeRule.onNodeWithTag(TRACK_PAN_LEFT_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertFalse(startMarkerAt(x0, y0))

        canvas().performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()

        assertTrue(startMarkerAt(x0, y0))
    }

    @Test
    fun dragging_movesTheView_asAnExtraWayBesidesTheButtons() {
        show()
        val (x0, y0) = expectedStart(TrackViewState())

        canvas().performTouchInput { swipe(start = center, end = center.copy(x = center.x + 120f), durationMillis = 300) }
        composeRule.waitForIdle()

        assertFalse("die Marke steht nicht mehr am alten Platz", startMarkerAt(x0, y0))
    }
}
