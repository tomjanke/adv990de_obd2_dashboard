package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Ambient-Statuszeile mit dem Oel-Timer in der Mitte (Nutzerwunsch 2026-10-15), in festen Breiten gerendert: so zeigt sich, was auf einem
 * schmalen Hochformat-Display und was auf einem breiten Querformat-Display zu sehen ist.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AmbientStatusRowDeviceTest {

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

    private fun show(width: Dp, oilGreenInMillis: Long, outsideTempC: Float? = 18f) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                // requiredWidth: ein breiteres Display als das Testgeraet laesst sich nur so abbilden (width wird auf die Bildschirmbreite begrenzt).
                Box(Modifier.requiredWidth(width)) {
                    AmbientStatusRow(outsideTempC = outsideTempC, useImperial = false, oilGreenInMillis = oilGreenInMillis)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun oilText(timer: String) = "${composeRule.activity.getString(R.string.ambient_oil_label)} $timer"

    @Test
    fun theOilTimerShowsItsLabelAndTheStartTimeWhileItIsNotRunning() {
        show(width = 360.dp, oilGreenInMillis = 600_000L)

        composeRule.onNodeWithText(oilText("10:00")).assertIsDisplayed()
    }

    @Test
    fun theOilTimerShowsTheRemainingTimeWhileItRuns() {
        show(width = 360.dp, oilGreenInMillis = 372_000L)

        composeRule.onNodeWithText(oilText("6:12")).assertIsDisplayed()
        composeRule.onNodeWithText(oilText("10:00")).assertDoesNotExist()
    }

    @Test
    fun aWideRowKeepsTheFullDate() {
        show(width = 900.dp, oilGreenInMillis = 600_000L)   // breiter als das Display: gezeigt wird hier nur, was die Zeile komponiert

        assertTrue("das volle Datum steht in der breiten Zeile", composeRule.onAllNodesWithText(" · ", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aNarrowRowShowsOnlyTheTimeSoTheTimerFitsInTheMiddle() {
        show(width = 360.dp, oilGreenInMillis = 600_000L)

        assertTrue("kein volles Datum mehr", composeRule.onAllNodesWithText(" · ", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText(oilText("10:00")).assertIsDisplayed()
        composeRule.onNodeWithText("18 °C").assertIsDisplayed()
    }

    @Test
    fun theTimerSitsExactlyInTheMiddleOfTheRow() {
        val width = 360.dp
        show(width = width, oilGreenInMillis = 372_000L)

        val bounds = composeRule.onNodeWithText(oilText("6:12")).getUnclippedBoundsInRoot()
        val centre = (bounds.left + bounds.right).value / 2f

        assertEquals("Mitte der Zeile", width.value / 2f, centre, 1f)
    }

    @Test
    fun theTimerStaysInTheMiddleWithoutAnOutsideTemperature() {
        val width = 360.dp
        show(width = width, oilGreenInMillis = 372_000L, outsideTempC = null)

        val bounds = composeRule.onNodeWithText(oilText("6:12")).getUnclippedBoundsInRoot()

        assertEquals((bounds.left + bounds.right).value / 2f, width.value / 2f, 1f)
    }

    @Test
    fun timeTimerAndTemperatureStandInOrderWithoutOverlap() {
        show(width = 360.dp, oilGreenInMillis = 372_000L)

        // Der Kopf ist in der schmalen Zeile nur die Uhrzeit "HH:mm": unter den Knoten mit einem Doppelpunkt der, der nicht der Timer ist.
        val label = composeRule.activity.getString(R.string.ambient_oil_label)
        val withColon = composeRule.onAllNodesWithText(":", substring = true)
        val headerIndex = withColon.fetchSemanticsNodes().indexOfFirst { node ->
            node.config[SemanticsProperties.Text].none { it.text.contains(label) }
        }
        assertTrue("die Uhrzeit steht in der Zeile", headerIndex >= 0)
        val header = withColon[headerIndex].getUnclippedBoundsInRoot()
        val timer = composeRule.onNodeWithText(oilText("6:12")).getUnclippedBoundsInRoot()
        val temperature = composeRule.onNodeWithText("18 °C").getUnclippedBoundsInRoot()

        assertTrue("Uhrzeit links vom Timer: ${header.right} <= ${timer.left}", header.right <= timer.left)
        assertTrue("Timer links von der Temperatur: ${timer.right} <= ${temperature.left}", timer.right <= temperature.left)
    }
}
