package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Der Aufnahmeknopf des Dashboards in seinen Zustaenden, als Bildpunkte gelesen: Ring, roter Punkt, **gruener Kreis mit Play-Dreieck** beim Nachfahren. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingButtonDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val recording = RecordingState.Recording(1, 10, 100.0, 0, signal = true, writeFailed = false)

    private var starts = 0
    private var stops = 0
    private var menus = 0

    private fun show(state: RecordingState = RecordingState.Idle, following: Boolean = false) {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                // Ein weisser Grund: der Knopf selbst hat keinen Hintergrund, und die Aufnahme liest „nichts gezeichnet“ sonst als undurchsichtigen Punkt.
                Box(Modifier.background(Color.White)) {
                    RecordingButton(state, onStart = { starts++ }, onStopRequest = { stops++ }, following = following, onFollowMenu = { menus++ })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun button() = composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG)

    private fun near(a: Color, b: Color, tolerance: Float = 0.03f) = abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance && abs(a.blue - b.blue) < tolerance

    /** Die Pixel des Knopfes: der Mittelpunkt und ein Punkt im Kreis ausserhalb des Zeichens. */
    private fun pixels() = button().captureToImage().toPixelMap()

    @Test
    fun followingIsAGreenCircleWithADarkPlayTriangle() {
        show(following = true)
        val p = pixels()
        val cx = p.width / 2
        val cy = p.height / 2
        val unit = p.width / 56f // ein dp in Bildpunkten (der Knopf ist 56 dp breit)
        // Ein Punkt links oben im Kreis (ausserhalb des Dreiecks): das Gruen der Ampel.
        val circle = p[(cx - 8 * unit).toInt(), (cy - 4 * unit).toInt()]
        assertTrue("green circle: $circle", near(circle, StatusColors.TrafficLightGreen))
        // Das Dreieck sitzt in der Mitte und ist dunkel.
        val glyph = p[cx, cy]
        assertTrue("dark triangle in the middle: $glyph", near(glyph, StatusColors.OnTrafficLightGreen))
        // Das Dreieck ist klein: ein Punkt 7 dp rechts der Mitte ist wieder Gruen.
        val beyond = p[(cx + 7 * unit).toInt(), cy]
        assertTrue("the triangle is small: $beyond", near(beyond, StatusColors.TrafficLightGreen))
    }

    @Test
    fun theTriangleStaysInsideTheCircleAndNothingIsGreenOutsideIt() {
        show(following = true)
        val p = pixels()
        val unit = p.width / 56f
        val corner = p[(2 * unit).toInt(), (2 * unit).toInt()]
        assertTrue("outside the circle nothing is drawn: $corner", near(corner, Color.White))
    }

    @Test
    fun recordingIsARedCircle_idleIsARing() {
        show(recording)
        val red = pixels().let { it[it.width / 2, it.height / 2] }
        assertTrue("red dot: $red", near(red, StatusColors.Critical))
    }

    @Test
    fun idleIsAnOutlineWithADot_notFilled() {
        show(RecordingState.Idle)
        val p = pixels()
        val unit = p.width / 56f
        val ringColor = lightColorScheme().onSurfaceVariant
        val dot = p[p.width / 2, p.height / 2]
        assertTrue("a dot in the middle: $dot", near(dot, ringColor))
        val inside = p[(p.width / 2 + 7 * unit).toInt(), p.height / 2]
        assertTrue("between dot and ring it is empty: $inside", near(inside, Color.White))
    }

    @Test
    fun recordingWinsWhenBothAreTrue() {
        show(recording, following = true)
        val red = pixels().let { it[it.width / 2, it.height / 2] }
        assertTrue("a recording is shown as such: $red", near(red, StatusColors.Critical))
        button().performClick()
        assertEquals(1, stops)
        assertEquals(0, menus)
    }

    // ── Antippen und Beschreibung ─────────────────────────────────────────────

    @Test
    fun aTapWhileFollowingOpensTheMenuAndStartsNoRecording() {
        show(following = true)
        button().performClick()
        assertEquals(1, menus)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test
    fun aTapWhileIdleStartsAndWhileRecordingAsksToStop() {
        show(RecordingState.Idle)
        button().performClick()
        assertEquals(1, starts)
        assertEquals(0, menus)
    }

    @Test
    fun aTapWhileRecordingAsksToStop() {
        show(recording)
        button().performClick()
        assertEquals(1, stops)
        assertEquals(0, starts)
    }

    @Test
    fun theDescriptionNamesTheState() {
        show(following = true)
        button().assertContentDescriptionEquals(composeRule.activity.getString(R.string.dashboard_track_follow_cd))
    }

    @Test
    fun theButtonKeepsItsTouchTargetAndIsLockedWhileStarting() {
        show(following = true)
        button().assertTouchTargetAtLeast56(Density(composeRule.activity))
        button().assertIsEnabled()
    }

    @Test
    fun whileStartingTheButtonIsLockedEvenWhenFollowing() {
        show(RecordingState.Starting, following = true)
        button().assertIsNotEnabled()
    }
}
