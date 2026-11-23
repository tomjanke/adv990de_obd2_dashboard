package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.FollowStatus
import de.adv990de.obd2.track.formatFollowDistance
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Die Statuszeile des Nachfahrens in der Mitte der Ambient-Statuszeile (Nutzerwunsch 2026-11-23): sie steht nur in einer breiten Zeile (Querformat), zwischen Datum und Temperatur, ohne
 * die Hoehe der Zeile zu aendern, und „abseits“ traegt die Warnfarbe. Die Regeln dahinter pruefen `FollowStatusTest` und `FollowStatusViewModelTest`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AmbientStatusRowDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun show(
        status: FollowStatus?,
        widthDp: Int = 900,
        temperature: Float? = 18.4f,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                    Box(Modifier.requiredWidth(widthDp.dp).testTag(ROW_TAG)) {
                        AmbientStatusRow(outsideTempC = temperature, useImperial = false, followStatus = status)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun text(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    private fun statusNode() = composeRule.onNodeWithTag(FOLLOW_STATUS_TEST_TAG)

    @Test
    fun onTheTrackTheRemainingDistanceStandsInTheMiddle() {
        show(FollowStatus.OnTrack(12_400.0))
        statusNode().assertExists()
        composeRule.onNodeWithText(text(R.string.follow_status_on_track, formatFollowDistance(12_400.0, locale))).assertExists()
    }

    @Test
    fun offTheTrackTheDistanceToTheTrackStandsInTheMiddle() {
        show(FollowStatus.OffTrack(85.0))
        composeRule.onNodeWithText(text(R.string.follow_status_off_track, formatFollowDistance(85.0, locale))).assertExists()
    }

    @Test
    fun theStatesWithoutADistanceHaveTheirOwnText() {
        show(FollowStatus.Waiting)
        composeRule.onNodeWithText(text(R.string.follow_status_waiting)).assertExists()
    }

    @Test
    fun noLocationHasItsOwnText() {
        show(FollowStatus.NoLocation)
        composeRule.onNodeWithText(text(R.string.follow_status_no_location)).assertExists()
    }

    @Test
    fun theDestinationHasItsOwnText() {
        show(FollowStatus.Arrived)
        composeRule.onNodeWithText(text(R.string.follow_status_arrived)).assertExists()
    }

    @Test
    fun withoutAStatusThereIsNoMiddleText() {
        show(null)
        statusNode().assertDoesNotExist()
    }

    @Test
    fun inANarrowRowThereIsNoStatus() {
        // Hochformat: 448 dp, Datum und Temperatur lassen rund 62 dp frei — die Anzeige entfaellt (Entscheidung nach Rueckfrage).
        show(FollowStatus.OnTrack(12_400.0), widthDp = 448)
        statusNode().assertDoesNotExist()
    }

    @Test
    fun theWidthRuleHasItsBorderAtTheConstant() {
        show(FollowStatus.OnTrack(12_400.0), widthDp = FOLLOW_STATUS_MIN_ROW_WIDTH.value.toInt())
        statusNode().assertExists()
    }

    @Test
    fun justBelowTheConstantThereIsNoStatus() {
        show(FollowStatus.OnTrack(12_400.0), widthDp = FOLLOW_STATUS_MIN_ROW_WIDTH.value.toInt() - 1)
        statusNode().assertDoesNotExist()
    }

    @Test
    fun theStatusStandsBetweenTheDateAndTheTemperature() {
        show(FollowStatus.OnTrack(12_400.0))
        val status = statusNode().getBoundsInRoot()
        val temperature = composeRule.onNodeWithText("18 °C").getBoundsInRoot()
        val row = composeRule.onNodeWithTag(ROW_TAG).getBoundsInRoot()
        // Links vom Text steht das Datum: sein Anfang liegt am linken Rand der Zeile (16 dp), der Text beginnt weit dahinter.
        assertTrue("the status starts right of the date", status.left > row.left + 200.dp)
        assertTrue("the status ends before the temperature", status.right <= temperature.left)
        assertTrue("the temperature is the right edge", temperature.right <= row.right)
    }

    @Test
    fun withoutATemperatureTheStatusStillFitsAndKeepsItsPlace() {
        show(FollowStatus.OnTrack(12_400.0), temperature = null)
        statusNode().assertExists()
    }

    @Test
    fun theStatusDoesNotChangeTheHeightOfTheRow() {
        // Zwei Zeilen untereinander in einer Komposition: ohne und mit Status. Ein Hoehensprung beim Einblenden waere ein Sprung des ganzen Bildschirms darueber.
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                androidx.compose.foundation.layout.Column(Modifier.requiredWidth(900.dp)) {
                    Box(Modifier.testTag("without")) { AmbientStatusRow(outsideTempC = 18.4f, useImperial = false, followStatus = null) }
                    Box(Modifier.testTag("with")) { AmbientStatusRow(outsideTempC = 18.4f, useImperial = false, followStatus = FollowStatus.OnTrack(12_400.0)) }
                }
            }
        }
        composeRule.waitForIdle()
        val without = composeRule.onNodeWithTag("without").getBoundsInRoot().let { it.bottom - it.top }
        val with = composeRule.onNodeWithTag("with").getBoundsInRoot().let { it.bottom - it.top }
        assertEquals(without.value, with.value, 0.5f)
    }

    @Test
    fun withAHugeSystemFontTheStatusNeverPushesTheTemperatureOut() {
        // Die schmalste Zeile, in der der Status noch steht, mit doppelter Systemschrift: Datum, Status und Temperatur passen nicht mehr nebeneinander.
        show(FollowStatus.OffTrack(185.0), widthDp = FOLLOW_STATUS_MIN_ROW_WIDTH.value.toInt(), fontScale = 2f)
        val status = statusNode().getBoundsInRoot()
        val temperature = composeRule.onNodeWithText("18 °C").getBoundsInRoot()
        val row = composeRule.onNodeWithTag(ROW_TAG).getBoundsInRoot()
        assertTrue("the status ends before the temperature", status.right <= temperature.left + 0.5.dp)
        assertTrue("the temperature stays in the row", temperature.right <= row.right + 0.5.dp)
        // Ein Knoten mit Breite 0 liegt „im Rahmen“, ist aber verschwunden: die Temperatur behaelt ihre ganze Breite (5 Zeichen Plex Mono bei doppelter Schrift: rund 156 dp).
        assertTrue("the temperature keeps its width (${temperature.right - temperature.left})", temperature.right - temperature.left >= 100.dp)
    }

    @Test
    fun aStatusThatDoesNotFitEndsWithAnEllipsisInsteadOfPushingOthers() {
        show(FollowStatus.OffTrack(185.0), fontScale = 2f)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val node = statusNode().fetchSemanticsNode()
        val action = node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action
        composeRule.runOnUiThread { action?.invoke(results) }
        val layout = results.single()
        assertTrue("the text does not fit at twice the system font", layout.didOverflowWidth)
        assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layout.layoutInput.overflow)
    }

    // --- Farbe, aus den Bildpunkten gelesen ---

    private fun share(color: Color, tolerance: Float = 0.12f): Double {
        val image = statusNode().captureToImage().toPixelMap()
        var hit = 0
        var total = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image[x, y]
            total++
            if (abs(p.red - color.red) < tolerance && abs(p.green - color.green) < tolerance && abs(p.blue - color.blue) < tolerance) hit++
        }
        return hit.toDouble() / total
    }

    @Test
    fun offTheTrackTheTextHasTheWarningColor() {
        show(FollowStatus.OffTrack(85.0))
        // blueColorScheme ist dunkel: warningOn ist dort das kraeftige Gelb.
        assertTrue("some pixels are the warning color", share(StatusColors.warningOn(blueColorScheme.surface)) > 0.01)
    }

    @Test
    fun onTheTrackTheTextHasNoWarningColor() {
        show(FollowStatus.OnTrack(12_400.0))
        assertEquals(0.0, share(StatusColors.warningOn(blueColorScheme.surface)), 0.0)
    }

    @Test
    fun onTheTrackTheTextHasTheColorOfTheDate() {
        show(FollowStatus.OnTrack(12_400.0))
        assertTrue("some pixels have the secondary text color", share(blueColorScheme.onSurfaceVariant) > 0.01)
    }

    private companion object {
        const val ROW_TAG = "ambient_row"
    }
}
