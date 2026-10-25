package de.adv990de.obd2.ui.trips

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredWidth
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der Menuepunkt "Fahrten" ([TripsAndFaultsScreen]): ein vierteiliger Umschalter "Fahrten | Aktuell | Historie | Logbuch" ueber der Fahrtenliste, den
 * beiden Ansichten der Fehlercodes und dem Logbuch. Die Inhalte der Seiten selbst pruefen `HistoryScreenTest` und `DtcScreenTest`; hier die Verdrahtung: welcher
 * Bereich steht wo, bleibt er nach einer Drehung und nach der Rueckkehr aus dem Freeze-Frame, und liest ein Wechsel nicht erneut vom Fahrzeug.
 *
 * Der Hilt-Graph kommt aus `TestAppModule` (echte `SessionRepository` und `DtcHistoryRepository` auf der Test-Datenbank); nur das
 * [ObdRepository] ist ersetzt, damit sich `readDtcs()` zaehlen laesst.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TripsAndFaultsScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val connected = MutableStateFlow(true)

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository {
        every { isConnectedFlow } returns connected
        coEvery { readDtcs() } returns emptyList()
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun segment(section: TripsSection) = composeRule.onNodeWithTag(tripsSectionTestTag(section))

    private fun launch(initial: TripsSection = TripsSection.Trips) {
        composeRule.setContent { TripsAndFaultsScreen(navController = rememberNavController(), initialSection = initial) }
        composeRule.waitForIdle()
    }

    @Test
    fun itStartsOnTheTrips_withFourSegmentsOfAtLeast56dp() {
        launch()

        composeRule.onNodeWithText(string(R.string.history_title)).assertIsDisplayed()
        assertEquals(4, TripsSection.entries.size)
        for (section in TripsSection.entries) {
            segment(section).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            composeRule.onNodeWithText(string(section.labelRes)).assertIsDisplayed()
        }
        segment(TripsSection.Trips).assertIsSelected()
    }

    @Test
    fun theSegmentsSwitchBetweenTheTripsAndTheTwoViewsOfTheFaultCodes() {
        launch()

        segment(TripsSection.Current).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.dtc_title)).assertIsDisplayed()
        segment(TripsSection.Current).assertIsSelected()

        segment(TripsSection.History).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.dtc_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dtc_history_empty)).assertIsDisplayed()
        segment(TripsSection.History).assertIsSelected()

        segment(TripsSection.Trips).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.history_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.dtc_title)).assertCountEquals(0)
        segment(TripsSection.Trips).assertIsSelected()
    }

    @Test
    fun theLogbookSegmentShowsTheLogbook_andLeavingItBringsTheTripsBack() {
        launch()

        segment(TripsSection.Logbook).performClick()
        composeRule.waitForIdle()
        // "Logbuch" steht zweimal da: als Segment und als Titel der Seite.
        composeRule.onAllNodesWithText(string(R.string.logbook_title)).assertCountEquals(2)
        composeRule.onNodeWithText(string(R.string.logbook_empty)).assertIsDisplayed()
        segment(TripsSection.Logbook).assertIsSelected()
        composeRule.onAllNodesWithText(string(R.string.history_title)).assertCountEquals(0)

        segment(TripsSection.Trips).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.history_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_title)).assertCountEquals(1)   // nur noch das Segment
    }

    @Test
    fun theLogbookSegmentDoesNotReadTheVehicle() {
        launch(initial = TripsSection.Logbook)

        coVerify(exactly = 0) { obdRepository.readDtcs() }
    }

    /** Zeigt den Umschalter in einem Fenster der Breite [widthDp] mit der Systemschrift [fontScale] und liefert je Bereich das Textlayout seiner Beschriftung. */
    private fun labelLayouts(widthDp: Int, fontScale: Float): Map<TripsSection, androidx.compose.ui.text.TextLayoutResult> {
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = fontScale),
            ) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredWidth(widthDp.dp)) {
                    SectionSwitch(selected = TripsSection.Trips, onSelect = {})
                }
            }
        }
        composeRule.waitForIdle()
        return TripsSection.entries.associateWith { section ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            composeRule.onNodeWithText(string(section.labelRes)).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.first()
        }
    }

    // Das Wagnis der vier Segmente ("Historie" und "Logbuch" sind die laengsten Woerter). Gemessen am Pixel 8 Pro am 2026-10-25: auf einer Zeile bei 448 dp mit Schrift bis 130 %
    // und bei 360 dp mit normaler Schrift; bei 360 dp und 130 % (auch 392 dp und 130 % bei zwei Woertern) brechen sie um. `hasVisualOverflow` taugt dafuer nicht (es meldet auch
    // passende Texte), gezaehlt werden die Zeilen.

    private fun assertOneLineAt(widthDp: Int, fontScale: Float) {
        for ((section, layout) in labelLayouts(widthDp, fontScale)) {
            assertEquals("${string(section.labelRes)} bei $widthDp dp, Schrift $fontScale", 1, layout.lineCount)
        }
    }

    // `setContent` darf je Test nur einmal laufen: ein Test je Schriftgroesse.
    @Test
    fun theFourLabelsFitOnOneLine_at448dp_withTheNormalFont() = assertOneLineAt(448, 1.0f)

    @Test
    fun theFourLabelsFitOnOneLine_at448dp_withTheFontAt115Percent() = assertOneLineAt(448, 1.15f)

    @Test
    fun theFourLabelsFitOnOneLine_at448dp_withTheFontAt130Percent() = assertOneLineAt(448, 1.3f)

    @Test
    fun theFourLabelsFitOnOneLine_inTheNarrowest360dpWindow_withTheNormalFont() = assertOneLineAt(360, 1.0f)

    @Test
    fun inTheWorstCase_360dpWithTheFontAt130Percent_theLabelsWrapToTwoLines_butNothingIsCutOff() {
        for ((section, layout) in labelLayouts(widthDp = 360, fontScale = 1.3f)) {
            assertTrue("${string(section.labelRes)}: ${layout.lineCount} Zeilen", layout.lineCount <= 2)
            assertTrue("${string(section.labelRes)} wird in der Hoehe abgeschnitten", !layout.didOverflowHeight)
        }
    }

    @Test
    fun switchingBetweenCurrentAndHistory_doesNotReadTheVehicleAgain() {
        // Die DTC-Seite liest beim Betreten vom Fahrzeug; ein Wechsel zwischen ihren zwei Ansichten darf sie nicht neu aufbauen.
        launch(initial = TripsSection.Current)
        composeRule.waitForIdle()
        coVerify(exactly = 1) { obdRepository.readDtcs() }

        segment(TripsSection.History).performClick()
        composeRule.waitForIdle()
        segment(TripsSection.Current).performClick()
        composeRule.waitForIdle()
        segment(TripsSection.History).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { obdRepository.readDtcs() }
    }

    @Test
    fun theSelectedSectionSurvivesARestoreOfTheSavedState() {
        // Eine Drehung baut die Oberflaeche neu auf; der Bereich gehoert zur Seite (rememberSaveable).
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { TripsAndFaultsScreen(navController = rememberNavController()) }
        composeRule.waitForIdle()
        segment(TripsSection.History).performClick()
        composeRule.waitForIdle()
        segment(TripsSection.History).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        segment(TripsSection.History).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.dtc_title)).assertIsDisplayed()
    }

    @Test
    fun backFromTheFreezeFrame_showsTheSameSectionAgain() {
        coEvery { obdRepository.readDtcs() } returns listOf(DtcEntry("P0300", false))
        val freezeFrameMarker = "FREEZE_FRAME_DESTINATION_MARKER"
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.TripsAndFaults.route) {
                composable(Screen.TripsAndFaults.route) { TripsAndFaultsScreen(navController = navController) }
                composable(Screen.FreezeFrame.route) { Text(freezeFrameMarker) }
            }
        }
        composeRule.waitForIdle()
        segment(TripsSection.Current).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.dtc_freeze_frame_link)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(freezeFrameMarker).assertIsDisplayed()

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()

        segment(TripsSection.Current).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.dtc_freeze_frame_link)).assertIsDisplayed()
    }
}
