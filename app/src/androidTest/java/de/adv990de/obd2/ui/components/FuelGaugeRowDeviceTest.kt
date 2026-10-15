package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die virtuelle Tankanzeige (Nutzerwunsch 2026-10-15) auf dem Geraet: Fuellung, Farbe, Blinken, Long-Press und Trefferflaeche.
 * Die Farben werden aus dem gerenderten Bild gelesen (nicht aus dem Quelltext geschlossen), das Blinken mit angehaltener Uhr Schritt fuer Schritt.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FuelGaugeRowDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Pflichtbaustein jeder @HiltAndroidTest-Klasse hier, s. [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private var resets = 0

    private fun show(state: FuelGaugeState) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                Box(Modifier.fillMaxWidth()) {
                    FuelGaugeRow(state = state, onResetRequested = { resets++ })
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Spur und Fuellung sind Kinder der Zeile, und die Zeile fasst ihre Kinder zu EINEM Knoten zusammen (`combinedClickable`: ein Knoten fuer
     * TalkBack mit einer Beschreibung) — im zusammengefuehrten Baum gibt es sie nicht, nur im ungemergten.
     */
    private fun inner(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    /** Die Farbe in der Mitte der Fuellung, wie sie auf dem Bildschirm steht. */
    private fun fillColorArgb(): Int {
        val image = inner(FUEL_GAUGE_FILL_TEST_TAG).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    private fun width(tag: String) = inner(tag).fetchSemanticsNode().size.width

    @Test
    fun theRowDescribesTheRemainingRangeForScreenReaders() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f)) // 220 km = 68,75 % -> 69 %

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.fuel_gauge_desc, 220, 69))
    }

    @Test
    fun theLongPressIsAnnouncedAsResetForScreenReaders() {
        show(FuelGaugeState(320, 0f))

        val action = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().config[SemanticsActions.OnLongClick]

        assertEquals(composeRule.activity.getString(R.string.fuel_gauge_reset_action), action.label)
    }

    @Test
    fun theBarFillsFromTheLeftWithTheShareOfTheRemainingRange() {
        show(FuelGaugeState(rangeFullKm = 200, kmSinceFill = 50f)) // 75 %

        val track = width(FUEL_GAUGE_TRACK_TEST_TAG)
        val fill = width(FUEL_GAUGE_FILL_TEST_TAG)

        assertTrue("Fuellung $fill px von $track px, erwartet 75 %", kotlin.math.abs(fill - track * 0.75f) <= 1f)
    }

    @Test
    fun aFullTankFillsTheWholeTrackAndAnEmptyOneNothing() {
        show(FuelGaugeState(200, 0f))
        assertEquals(width(FUEL_GAUGE_TRACK_TEST_TAG), width(FUEL_GAUGE_FILL_TEST_TAG))
    }

    @Test
    fun anEmptyTankFillsNothing() {
        show(FuelGaugeState(200, 400f))
        assertEquals(0, width(FUEL_GAUGE_FILL_TEST_TAG))
    }

    @Test
    fun theBarIsGreenAbove40Percent() {
        show(FuelGaugeState(200, 100f)) // 50 %
        assertEquals(StatusColors.TrafficLightGreen.toArgb(), fillColorArgb())
    }

    @Test
    fun theBarIsYellowFrom20To39Percent() {
        show(FuelGaugeState(200, 140f)) // 30 %
        assertEquals(StatusColors.Warning.toArgb(), fillColorArgb())
    }

    @Test
    fun theBarIsRedFrom5To19Percent() {
        show(FuelGaugeState(200, 170f)) // 15 %
        assertEquals(StatusColors.Critical.toArgb(), fillColorArgb())
    }

    @Test
    fun aRedBarAbove5PercentDoesNotBlink() {
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 170f)) // 15 %

        repeat(4) {
            composeRule.mainClock.advanceTimeBy(500)
            assertEquals("Takt ${it + 1}", StatusColors.Critical.toArgb(), fillColorArgb())
        }
    }

    @Test
    fun belowFivePercentTheBarBlinksRed() {
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 195f)) // 2,5 %
        val on = fillColorArgb()
        assertEquals("zuerst rot und voll sichtbar", StatusColors.Critical.toArgb(), on)

        composeRule.mainClock.advanceTimeBy(FUEL_BLINK_HALF_PERIOD_MS + 50)
        val off = fillColorArgb()
        assertNotEquals("nach einer halben Periode abgeblendet", on, off)

        composeRule.mainClock.advanceTimeBy(FUEL_BLINK_HALF_PERIOD_MS)
        assertEquals("nach einer Periode wieder rot", on, fillColorArgb())
    }

    @Test
    fun aLongPressOnTheRowAsksForAReset() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(1, resets)
    }

    @Test
    fun aLongPressOnTheBarItselfAsksForAReset() {
        show(FuelGaugeState(320, 100f))

        inner(FUEL_GAUGE_TRACK_TEST_TAG).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(1, resets)
    }

    @Test
    fun aSimpleTapDoesNothing() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(0, resets)
    }

    @Test
    fun aPressThatEndsBeforeTheSystemLongPressTimeoutDoesNothing() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis / 2)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, resets)
    }

    @Test
    fun theRowIsExactly40DpHigh_withEqualSpaceAboveAndBelowTheBar() {
        // Dokumentierte Ausnahme von der 56-dp-Regel (Nutzerwunsch 2026-10-15: engerer Abstand): 40 dp Zeile, 16 dp Balken, 12 dp Luft je Seite.
        show(FuelGaugeState(320, 100f))
        val density = composeRule.activity.resources.displayMetrics.density

        val row = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val track = inner(FUEL_GAUGE_TRACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val above = track.top - row.top
        val below = row.bottom - track.bottom

        assertEquals("Zeilenhoehe ${row.height} px", 40 * density, row.height, 1.5f)
        assertEquals("Balkenhoehe ${track.height} px", 16 * density, track.height, 1.5f)
        assertEquals("Luft oben $above px, unten $below px", above, below, 1.5f)
        assertEquals("12 dp Luft", 12 * density, above, 1.5f)
    }

    @Test
    fun theBarEndsAt16DpFromTheRightEdge_andTheLeftMarginIsOnly8Dp() {
        // Nutzerwunsch 2026-10-15: das Tankstellen-Symbol sitzt naeher am linken Rand (8 dp Zeilenrand + 4 dp Eigenrand des Symbols = 12 dp sichtbar, vorher 16 + 4);
        // rechts bleibt der Rand des Balkens bei 16 dp (die sichtbare Kante des Symbols misst DashboardLeftEdgeAlignmentDeviceTest als Bildpunkt).
        show(FuelGaugeState(320, 100f))
        val density = composeRule.activity.resources.displayMetrics.density

        val row = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val track = inner(FUEL_GAUGE_TRACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("rechter Rand ${row.right - track.right} px", 16 * density, row.right - track.right, 1.5f)
        // Symbol (24 dp) + 12 dp Abstand vor dem Balken: er beginnt bei 8 + 24 + 12 = 44 dp.
        assertEquals("Balken beginnt ${track.left - row.left} px vom Zeilenanfang", 44 * density, track.left - row.left, 1.5f)
    }
}
