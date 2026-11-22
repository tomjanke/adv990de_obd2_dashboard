package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs

/** Das Roadbook-HUD auf dem Geraet: Zahlen, Balken, Pfeil als Bildpunkte, die zwei Groessen, das Ein- und Ausblenden. Alle Koordinaten sind erfunden. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RoadbookHudDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun at(lat: Double, lon: Double) = TrackPoint(null, lat, lon, null, null, null)

    private val path = listOf(at(50.0000, 10.0000), at(50.0010, 10.0000), at(50.0020, 10.0000), at(50.0023, 10.0005), at(50.0023, 10.0020))

    private fun hud(
        maneuver: Maneuver = Maneuver(220.0, 240.0, 260.0, 95.0, ManeuverKind.NORMAL),
        distance: Double = 240.0,
        trigger: Double = 400.0,
        next: Maneuver? = null,
    ) = RoadbookHud(maneuver, distance, trigger, next, next?.let { 90.0 }, path, path[3], path[0], 0.0)

    private fun show(hud: RoadbookHud, compact: Boolean) {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) { RoadbookHudView(hud, compact) }
        }
        composeRule.waitForIdle()
    }

    private fun tag(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun kindText(maneuver: Maneuver) = composeRule.activity.getString(
        when (maneuver.kind) {
            ManeuverKind.SLIGHT -> if (maneuver.isRight) R.string.roadbook_kind_slight_right else R.string.roadbook_kind_slight_left
            ManeuverKind.NORMAL -> if (maneuver.isRight) R.string.roadbook_kind_normal_right else R.string.roadbook_kind_normal_left
            ManeuverKind.SHARP -> if (maneuver.isRight) R.string.roadbook_kind_sharp_right else R.string.roadbook_kind_sharp_left
            ManeuverKind.HAIRPIN -> if (maneuver.isRight) R.string.roadbook_kind_hairpin_right else R.string.roadbook_kind_hairpin_left
            ManeuverKind.DESTINATION -> R.string.roadbook_kind_destination
        },
    )

    // ── Inhalt ────────────────────────────────────────────────────────────────

    @Test
    fun theDistanceIsShownInMetresAndThePictureHasADescription() {
        val h = hud(distance = 247.0)
        show(h, compact = false)

        tag(ROADBOOK_DISTANCE_TEST_TAG).assertTextEquals(formatRoadbookDistance(247.0, locale))
        val spoken = composeRule.activity.getString(R.string.roadbook_desc, formatRoadbookDistance(247.0, locale), kindText(h.maneuver))
        // Die volle Fassung hat die Karte als Kind; ihre Beschreibung wird mit der des HUD zusammengefuehrt.
        composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).assertContentDescriptionEquals(spoken, composeRule.activity.getString(R.string.roadbook_map_cd))
    }

    @Test
    fun theFullVersionHasTheMapAndTheNextTurn_theCompactOneHasNeither() {
        val next = Maneuver(330.0, 340.0, 350.0, -70.0, ManeuverKind.NORMAL)
        show(hud(next = next), compact = false)
        assertEquals(1, count(ROADBOOK_MAP_TEST_TAG))
        assertEquals(1, count(ROADBOOK_NEXT_TEST_TAG))
        tag(ROADBOOK_NEXT_TEST_TAG).assertTextEquals(composeRule.activity.getString(R.string.roadbook_then, kindText(next), formatRoadbookDistance(90.0, locale)))
        val size = composeRule.onNodeWithTag(ROADBOOK_MAP_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(112f, size.width.value, 1f)
        assertEquals(72f, size.height.value, 1f)
    }

    @Test
    fun theCompactVersionHasNoMapAndNoNextTurn() {
        show(hud(next = Maneuver(330.0, 340.0, 350.0, -70.0, ManeuverKind.NORMAL)), compact = true)
        tag(ROADBOOK_DISTANCE_TEST_TAG).assertIsDisplayed()
        assertEquals(0, count(ROADBOOK_MAP_TEST_TAG))
        assertEquals(0, count(ROADBOOK_NEXT_TEST_TAG))
    }

    @Test
    fun noNextTurnMeansNoNextLine() {
        show(hud(next = null), compact = false)
        assertEquals(0, count(ROADBOOK_NEXT_TEST_TAG))
    }

    @Test
    fun theBarFillsAsFarAsTheProgress() {
        // 240 von 400 m: Fortschritt 0,4.
        show(hud(distance = 240.0, trigger = 400.0), compact = false)
        val bar = composeRule.onNodeWithTag(ROADBOOK_BAR_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        val fill = composeRule.onNodeWithTag(ROADBOOK_BAR_FILL_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(0.4f, fill.width.value / bar.width.value, 0.03f)
    }

    @Test
    fun anEmptyAndAFullBar() {
        show(hud(distance = 400.0, trigger = 400.0), compact = true)
        val fill = composeRule.onNodeWithTag(ROADBOOK_BAR_FILL_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals("nothing filled at the trigger distance", 0f, fill.width.value, 1f)
    }

    // ── Pfeil als Bildpunkte ──────────────────────────────────────────────────

    /** Wie viel Tinte (dunkle Pixel) in den drei senkrechten Dritteln des Pfeils liegt: links, Mitte, rechts. */
    private fun inkThirds(node: SemanticsNodeInteraction): Triple<Int, Int, Int> {
        val image = node.captureToImage().toPixelMap()
        var l = 0
        var m = 0
        var r = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = image[x, y]
            if (c.alpha > 0.5f && c.red + c.green + c.blue < 1.2f) {
                when {
                    x < image.width / 3 -> l++
                    x < 2 * image.width / 3 -> m++
                    else -> r++
                }
            }
        }
        return Triple(l, m, r)
    }

    @Test
    fun aRightTurnPutsInkOnTheRightAndALeftTurnOnTheLeft() {
        show(hud(maneuver = Maneuver(220.0, 240.0, 260.0, 95.0, ManeuverKind.NORMAL)), compact = false)
        val (l, _, r) = inkThirds(tag(ROADBOOK_ARROW_TEST_TAG))
        assertTrue("right turn: more ink right ($r) than left ($l)", r > l * 2)
    }

    @Test
    fun aLeftTurnPutsInkOnTheLeft() {
        show(hud(maneuver = Maneuver(220.0, 240.0, 260.0, -95.0, ManeuverKind.NORMAL)), compact = false)
        val (l, _, r) = inkThirds(tag(ROADBOOK_ARROW_TEST_TAG))
        assertTrue("left turn: more ink left ($l) than right ($r)", l > r * 2)
    }

    @Test
    fun aHairpinIsAUWithInkOnBothSides() {
        show(hud(maneuver = Maneuver(220.0, 240.0, 260.0, 170.0, ManeuverKind.HAIRPIN)), compact = false)
        val (l, m, r) = inkThirds(tag(ROADBOOK_ARROW_TEST_TAG))
        assertTrue("both legs of the U have ink ($l, $r)", l > 50 && r > 50)
        assertTrue("the arc over the top fills the middle ($m)", m > 50)
    }

    @Test
    fun theDestinationIsAFlagNotAnArrow() {
        show(hud(maneuver = Maneuver(240.0, 240.0, 240.0, 0.0, ManeuverKind.DESTINATION)), compact = true)
        val (l, m, r) = inkThirds(tag(ROADBOOK_ARROW_TEST_TAG))
        assertTrue("a flag has ink", l + m + r > 100)
        composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).assertContentDescriptionEquals(
            composeRule.activity.getString(R.string.roadbook_desc, formatRoadbookDistance(240.0, locale), composeRule.activity.getString(R.string.roadbook_kind_destination)),
        )
    }

    // ── Dezent: die Deckkraft waechst ─────────────────────────────────────────

    @Test
    fun aFarTurnIsPalerThanANearOne() {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                // Die Deckkraft liegt auf der Ebene des HUD selbst; erst die Aufnahme des Elternknotens (weisser Grund) rechnet sie ein.
                Column(Modifier.background(Color.White)) {
                    Box(Modifier.testTag("far")) { RoadbookHudView(hud(distance = 400.0, trigger = 400.0), compact = true) }
                    Box(Modifier.testTag("near")) { RoadbookHudView(hud(distance = 100.0, trigger = 400.0), compact = true) }
                }
            }
        }
        composeRule.waitForIdle()
        fun brightness(i: Int): Float {
            val image = composeRule.onNodeWithTag(if (i == 0) "far" else "near").captureToImage().toPixelMap()
            // Die Pfeilfarbe: der dunkelste Punkt der Flaeche (onSurface auf der Flaeche).
            var min = 3f
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val c: Color = image[x, y]
                min = minOf(min, c.red + c.green + c.blue)
            }
            return min
        }
        assertTrue("the far one is lighter (${brightness(0)}) than the near one (${brightness(1)})", brightness(0) > brightness(1) + 0.15f)
    }

    // ── Platz ─────────────────────────────────────────────────────────────────

    @Test
    fun theCompactVersionFitsANarrowWindow() {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                Column(Modifier.width(300.dp)) { RoadbookHudView(hud(), compact = true) }
            }
        }
        composeRule.waitForIdle()
        val b = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).getBoundsInRoot()
        assertTrue("within the width (${b.width})", b.width.value <= 300f && b.left.value >= 0f)
        assertTrue("not taller than needed (${b.height})", b.height.value in 40f..90f)
    }

    @Test
    fun theFullVersionAtADashboardWidthHasItsDocumentedSize() {
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
                Column(Modifier.width(380.dp)) { RoadbookHudView(hud(next = Maneuver(330.0, 340.0, 350.0, -70.0, ManeuverKind.NORMAL)), compact = false, modifier = Modifier.fillMaxWidth()) }
            }
        }
        composeRule.waitForIdle()
        val b = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG).getBoundsInRoot()
        assertEquals(380f, b.width.value, 1f)
        assertTrue("about 88 dp high, below the 97 dp where the tile values start (${b.height})", abs(b.height.value - 88f) < 6f)
    }

    // ── Ein- und Ausblenden ───────────────────────────────────────────────────

    @Test
    fun theHudFadesInWhenThereIsOneAndOutWhenItIsGone() {
        var current by mutableStateOf<RoadbookHud?>(null)
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) { FadingRoadbookHud(current, compact = true) }
        }
        composeRule.waitForIdle()
        assertEquals(0, count(ROADBOOK_HUD_TEST_TAG))

        composeRule.runOnUiThread { current = hud() }
        composeRule.waitUntil(timeoutMillis = 5_000) { count(ROADBOOK_HUD_TEST_TAG) == 1 }
        tag(ROADBOOK_HUD_TEST_TAG).assertIsDisplayed()

        composeRule.runOnUiThread { current = null }
        composeRule.waitUntil(timeoutMillis = 5_000) { count(ROADBOOK_HUD_TEST_TAG) == 0 }
    }

    @Test
    fun theHudKeepsTheLastStateWhileFadingOut() {
        var current by mutableStateOf<RoadbookHud?>(hud(distance = 123.0))
        composeRule.setContent {
            AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) { FadingRoadbookHud(current, compact = true) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { count(ROADBOOK_HUD_TEST_TAG) == 1 }
        val before = formatRoadbookDistance(123.0, locale)
        tag(ROADBOOK_DISTANCE_TEST_TAG).assertTextEquals(before)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { current = null }
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals("still there during the fade", 1, count(ROADBOOK_HUD_TEST_TAG))
        tag(ROADBOOK_DISTANCE_TEST_TAG).assertTextEquals(before)
        composeRule.mainClock.autoAdvance = true
    }
}
