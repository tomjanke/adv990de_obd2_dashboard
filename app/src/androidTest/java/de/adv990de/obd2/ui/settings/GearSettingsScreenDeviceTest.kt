package de.adv990de.obd2.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
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
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Die Seite "Getriebe" ([GearSettingsScreen]) im echten Hilt-Graphen: neun Felder (Radumfang, Primaer-, Sekundaeruebersetzung, sechs Gaenge), gespeichert im echten
 * (je Test eigenen) DataStore. Das einzelne Feld ([GearRatioField]) prueft `GearRatioFieldUiTest` zustandslos; hier steht die Verdrahtung — was die Seite aus dem Speicher zeigt,
 * was eine Eingabe dort ablegt, was sie verwirft, die Warnung bei zu dicht liegenden Gaengen und der Weg zurueck.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GearSettingsScreenDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var navController: NavHostController

    @Before fun setup() { hiltRule.inject() }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun showScreen() {
        composeRule.setContent {
            GearSettingsScreen(navController = rememberNavController())
        }
        composeRule.waitForIdle()
    }

    /** Das Feld mit dieser Beschriftung: erst hinscrollen (im Querformat passen nicht alle neun auf den Schirm, eine Liste setzt nur Sichtbares zusammen). */
    private fun field(label: String): SemanticsNodeInteraction {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        composeRule.waitForIdle()
        return composeRule.onNodeWithText(label)
    }

    private fun gearLabel(n: Int) = string(R.string.gear_settings_gear_n, n)

    private fun awaitStored(description: String, check: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) { check() }
        assertEquals(description, true, check())
    }

    @Test
    fun theNineFieldsAreThere_inTheOrderOfTheGearbox() {
        showScreen()

        val labels = listOf(string(R.string.gear_settings_wheel_circumference), string(R.string.gear_settings_primary_ratio), string(R.string.gear_settings_secondary_ratio)) +
            (1..6).map(::gearLabel)
        labels.forEach { field(it).assertIsDisplayed() }
    }

    @Test
    fun theFieldsShowWhatIsStored_notTheDefaults() {
        runBlocking {
            appPreferences.setWheelCircumferenceMm(2060f)
            appPreferences.setPrimaryRatio(1.8f)
            appPreferences.setGearRatio(0, 13.5f)
        }
        showScreen()

        // Der gespeicherte Wert kommt asynchron aus dem DataStore: erst warten, bis das Feld ihn zeigt (davor steht der Standardwert da).
        field(string(R.string.gear_settings_wheel_circumference))
        composeRule.waitUntilAtLeastOneExists(hasText("2060.0"), timeoutMillis = 5_000)
        composeRule.onNodeWithText("2060.0").assertIsDisplayed()
        field(string(R.string.gear_settings_primary_ratio))
        composeRule.onNodeWithText("1.8").assertIsDisplayed()
        field(gearLabel(1))
        composeRule.onNodeWithText("13.5").assertIsDisplayed()
    }

    @Test
    fun aValidInputIsStoredInThePreferences() {
        showScreen()

        field(string(R.string.gear_settings_wheel_circumference)).performTextReplacement("2050")

        awaitStored("Radumfang gespeichert") { runBlocking { appPreferences.wheelCircumferenceMm.first() } == 2050f }
    }

    @Test
    fun theTwoReductionRatiosEachGoToTheirOwnPreference() {
        showScreen()

        field(string(R.string.gear_settings_primary_ratio)).performTextReplacement("1.9")
        awaitStored("Primaeruebersetzung gespeichert") { runBlocking { appPreferences.primaryRatio.first() } == 1.9f }
        assertEquals("die Sekundaeruebersetzung bleibt", AppPreferences.DEFAULT_SECONDARY_RATIO, runBlocking { appPreferences.secondaryRatio.first() }, 0.0001f)

        field(string(R.string.gear_settings_secondary_ratio)).performTextReplacement("2.7")
        awaitStored("Sekundaeruebersetzung gespeichert") { runBlocking { appPreferences.secondaryRatio.first() } == 2.7f }
        assertEquals("die Primaeruebersetzung bleibt", 1.9f, runBlocking { appPreferences.primaryRatio.first() }, 0.0001f)
    }

    @Test
    fun aGearFieldWritesItsOwnGearAndNoOtherOne() {
        showScreen()
        val before = runBlocking { appPreferences.gearRatios.first() }

        field(gearLabel(3)).performTextReplacement("7.5")

        awaitStored("Gang 3 gespeichert") { runBlocking { appPreferences.gearRatios.first() }[2] == 7.5f }
        val after = runBlocking { appPreferences.gearRatios.first() }
        assertEquals("alle anderen Gaenge bleiben", before.filterIndexed { i, _ -> i != 2 }, after.filterIndexed { i, _ -> i != 2 })
    }

    @Test
    fun aValueOutsideTheLimitsNamesTheLimitsAndStoresNothing() {
        showScreen()
        val before = runBlocking { appPreferences.wheelCircumferenceMm.first() }

        // "2.096": gemeint 2096 mm mit deutschem Tausenderpunkt — als Zahl 2,096 laege es weit unter 1000 mm
        field(string(R.string.gear_settings_wheel_circumference)).performTextReplacement("2.096")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.gear_settings_out_of_range, "1000", "4000")).assertIsDisplayed()
        assertEquals("der zuletzt gespeicherte Wert bleibt", before, runBlocking { appPreferences.wheelCircumferenceMm.first() }, 0.0001f)
    }

    @Test
    fun aGearCanBeSwitchedOffWithZero_andThatIsNoError() {
        showScreen()

        field(gearLabel(6)).performTextReplacement("0")

        awaitStored("Gang 6 abgeschaltet") { runBlocking { appPreferences.gearRatios.first() }[5] == 0f }
        composeRule.onNodeWithText(string(R.string.gear_settings_out_of_range_or_off, "0.2", "8")).assertDoesNotExist()
    }

    @Test
    fun twoGearsThatAreTooCloseTogetherShowTheWarning() {
        runBlocking {
            appPreferences.setGearRatio(4, 1.0f)
            appPreferences.setGearRatio(5, 1.0f)   // gleiche Uebersetzung: die Anzeige koennte die zwei Gaenge nicht trennen
        }
        showScreen()

        composeRule.onNodeWithText(string(R.string.gear_settings_ratio_conflict, 5, 6)).assertIsDisplayed()
    }

    @Test
    fun gearsWithPlentyOfRoomShowNoWarning() {
        runBlocking {
            listOf(3.0f, 2.2f, 1.7f, 1.4f, 1.15f, 1.0f).forEachIndexed { index, ratio -> appPreferences.setGearRatio(index, ratio) }
        }
        showScreen()

        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.gear_settings_ratio_conflict, 5, 6)).assertDoesNotExist()
    }

    @Test
    fun theBackArrowReturnsToThePreviousPage() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            NavHost(controller, startDestination = "home") {
                composable("home") { }
                composable("gear") { GearSettingsScreen(controller) }
            }
        }
        composeRule.runOnUiThread { navController.navigate("gear") }
        composeRule.waitForIdle()
        assertEquals("gear", navController.currentDestination?.route)

        composeRule.onNodeWithContentDescription(string(R.string.nav_back_desc)).performClick()
        composeRule.waitForIdle()

        assertEquals("home", navController.currentDestination?.route)
    }
}
