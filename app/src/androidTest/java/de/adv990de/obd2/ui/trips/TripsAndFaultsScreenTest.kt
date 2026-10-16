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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der Menuepunkt "Fahrten" ([TripsAndFaultsScreen]): ein dreiteiliger Umschalter "Fahrten | Aktuell | Historie" ueber der Fahrtenliste und den
 * beiden Ansichten der Fehlercodes. Die Inhalte der Seiten selbst pruefen `HistoryScreenTest` und `DtcScreenTest`; hier die Verdrahtung: welcher
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
    fun itStartsOnTheTrips_withThreeSegmentsOfAtLeast56dp() {
        launch()

        composeRule.onNodeWithText(string(R.string.history_title)).assertIsDisplayed()
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
