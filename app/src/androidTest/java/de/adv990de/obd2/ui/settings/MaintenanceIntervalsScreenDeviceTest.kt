package de.adv990de.obd2.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
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
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.ui.logbook.INTERVAL_FIELD_TEST_TAG
import de.adv990de.obd2.ui.logbook.INTERVAL_SAVE_TEST_TAG
import de.adv990de.obd2.ui.logbook.formatOdometer
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import javax.inject.Inject

/**
 * Die Seite "Wartungsintervalle" (Einstellungen, Gruppe "Fahrzeug"): sechs Zeilen mit dem Intervall oder "Nicht festgelegt" (nichts vorbelegt), derselbe Eingabedialog wie im Logbuch, Speichern im
 * echten Speicher, der Weg hin und zurueck. Der Inhalt ([MaintenanceIntervalsList]) wird zustandslos geprueft, die Seite im echten Hilt-Graphen.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MaintenanceIntervalsScreenDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var navController: NavHostController

    @Before fun setup() { hiltRule.inject() }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private val saved = mutableListOf<Pair<LogType, Int>>()

    /** Die Liste mit Werten, die der Test haelt (wie das ViewModel es tut): ein Speichern aendert die Zeile. */
    private fun showList(initial: Map<LogType, Int> = emptyMap()) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                var intervals by remember { mutableStateOf(initial) }
                MaintenanceIntervalsList(
                    intervals,
                    onChange = { type, km ->
                        saved += type to km
                        intervals = if (km == 0) intervals - type else intervals + (type to km)
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Die Zeile der Art: erst zu ihr scrollen (im Querformat passen nicht alle sechs auf den Schirm, eine Liste setzt nur Sichtbares zusammen). */
    private fun row(type: LogType): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(MAINTENANCE_LIST_TEST_TAG).performScrollToNode(hasTestTag(maintenanceIntervalRowTestTag(type)))
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(maintenanceIntervalRowTestTag(type))
    }

    /** Der Text der Zeile einer Art (Zusammenfassung unter dem Namen). */
    private fun rowText(type: LogType, text: String): SemanticsNodeInteraction {
        row(type)
        return composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(maintenanceIntervalRowTestTag(type))), useUnmergedTree = true)
    }

    // --- Inhalt ---

    @Test
    fun theListHasASentenceAndOneRowForEachOfTheSixKinds_nothingPreset() {
        showList()

        composeRule.onNodeWithText(string(R.string.settings_maintenance_intro)).assertIsDisplayed()
        for (type in MAINTENANCE_TYPES) {
            row(type).assertExists()
            rowText(type, string(R.string.settings_maintenance_not_set)).assertExists()
        }
    }

    @Test
    fun aSetIntervalShowsAsEveryNKm() {
        showList(mapOf(LogType.CHAIN_SMALL to 1_000))

        rowText(LogType.CHAIN_SMALL, string(R.string.logbook_interval_every, formatOdometer(1_000, locale))).assertExists()
        rowText(LogType.CHAIN_LARGE, string(R.string.settings_maintenance_not_set)).assertExists()
    }

    @Test
    fun everyRowIsAtLeast64dpHigh() {
        showList()

        for (type in MAINTENANCE_TYPES) row(type).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun aTap_opensTheDialogWithTheKindInItsTitle_andSavingReportsTheValue() {
        showList()

        row(LogType.SERVICE_LARGE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.logbook_interval_title, string(R.string.logbook_type_service_large))).assertIsDisplayed()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("11000")
        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertTouchTargetAtLeast56(Density(composeRule.activity)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(LogType.SERVICE_LARGE to 11_000), saved)
        rowText(LogType.SERVICE_LARGE, string(R.string.logbook_interval_every, formatOdometer(11_000, locale))).assertExists()
    }

    @Test
    fun theDialogShowsTheStoredValue_andAnEmptyFieldReportsZero() {
        showList(mapOf(LogType.TIRE_CHANGE to 15_000))

        row(LogType.TIRE_CHANGE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).assertTextContains("15000")
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(LogType.TIRE_CHANGE to 0), saved)
        rowText(LogType.TIRE_CHANGE, string(R.string.settings_maintenance_not_set)).assertExists()
    }

    @Test
    fun theDoneKeyOfTheKeyboardSavesAValidValue() {
        showList()

        row(LogType.CHAIN_LARGE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("5000")
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performImeAction()
        composeRule.waitForIdle()

        assertEquals(listOf(LogType.CHAIN_LARGE to 5_000), saved)
    }

    @Test
    fun theFieldTakesOnlyDigits_andAnInvalidValueDisablesSaving() {
        showList()

        row(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("1a2,5")
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).assertTextContains("125")
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("49")

        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.logbook_interval_error)).assertIsDisplayed()
    }

    @Test
    fun cancellingReportsNothing() {
        showList()

        row(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("1000")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Pair<LogType, Int>>(), saved)
    }

    // --- Die Seite im echten Graphen ---

    private fun launchScreen() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = "home") {
                composable("home") { Text(HOME_MARKER) }
                composable(Screen.MaintenanceIntervals.route) { MaintenanceIntervalsScreen(navController) }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { navController.navigate(Screen.MaintenanceIntervals.route) }
        composeRule.waitForIdle()
    }

    @Test
    fun thePage_hasItsTitle_savesIntoTheRealStore_andTheBackButtonLeavesIt() {
        launchScreen()

        composeRule.onNodeWithText(string(R.string.settings_maintenance_title)).assertIsDisplayed()
        row(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("750")
        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(mapOf(LogType.CHAIN_SMALL to 750), runBlocking { appPreferences.maintenanceIntervals.first() })
        rowText(LogType.CHAIN_SMALL, string(R.string.logbook_interval_every, formatOdometer(750, locale))).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.nav_back_desc)).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(HOME_MARKER).assertIsDisplayed()
    }

    @Test
    fun aStoredIntervalIsShownWhenThePageOpens() {
        runBlocking { appPreferences.setMaintenanceInterval(LogType.TIRE_CHANGE, 15_000) }
        launchScreen()

        rowText(LogType.TIRE_CHANGE, string(R.string.logbook_interval_every, formatOdometer(15_000, locale))).assertExists()
    }

    private companion object {
        const val HOME_MARKER = "MAINTENANCE_HOME_MARKER"
    }
}
