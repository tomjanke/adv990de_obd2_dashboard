package de.adv990de.obd2.ui.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.dashboard.OIL_BAR_STRIP_TEST_TAG
import de.adv990de.obd2.ui.logbook.LOGBOOK_SAVE_TEST_TAG
import de.adv990de.obd2.ui.logbook.LOG_ENTRY_ID_ARG
import de.adv990de.obd2.ui.logbook.LOG_STATS_TYPE_ARG
import de.adv990de.obd2.ui.logbook.LOGBOOK_STATS_LIST_TEST_TAG
import de.adv990de.obd2.ui.licenses.LICENSES_LIST_TEST_TAG
import de.adv990de.obd2.ui.settings.MAINTENANCE_LIST_TEST_TAG
import de.adv990de.obd2.ui.settings.SETTINGS_LIST_TEST_TAG
import de.adv990de.obd2.ui.trips.TripsSection
import de.adv990de.obd2.ui.trips.tripsSectionTestTag
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import javax.inject.Inject

/**
 * Der echte [AppNavHost]: jeder [Screen] hat eine Route UND ein Ziel, das tatsaechlich seinen Bildschirm zeigt. Ein Screen ohne `composable(...)` im Host liesse die App beim
 * Navigieren abstuerzen (`IllegalArgumentException: route cannot be found`), und kein anderer Test komponiert den Host selbst — `MainActivity` ist der einzige Aufrufer.
 *
 * **Neuer Screen = Compilerfehler hier:** beide `when` ueber [Screen] sind erschoepfend (sealed), ein neuer Screen zwingt dazu, seine Route und ein erkennbares Merkmal
 * seines Bildschirms einzutragen.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AppNavHostDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
        every { latestReadings } returns MutableStateFlow(emptyList())
    }

    @Inject lateinit var logbook: VehicleLogRepository

    private lateinit var navController: NavHostController

    @Before fun setup() { hiltRule.inject() }

    private fun showHost() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            AppNavHost(controller)
        }
        composeRule.waitForIdle()
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    /** Die Route, mit der der Screen geoeffnet wird (die Eintragsseite und die Detailseite brauchen ein Argument). */
    private fun routeToOpen(screen: Screen): String = when (screen) {
        Screen.Dashboard -> Screen.Dashboard.route
        Screen.FreezeFrame -> Screen.FreezeFrame.route
        Screen.VehicleInfo -> Screen.VehicleInfo.route
        Screen.TripsAndFaults -> Screen.TripsAndFaults.route
        Screen.Settings -> Screen.Settings.route
        Screen.AppInfo -> Screen.AppInfo.route
        Screen.AppLogs -> Screen.AppLogs.route
        Screen.GearSettings -> Screen.GearSettings.route
        Screen.Licenses -> Screen.Licenses.route
        Screen.MaintenanceIntervals -> Screen.MaintenanceIntervals.route
        Screen.LogEntry -> Screen.LogEntry.routeFor(0)
        Screen.LogbookStats -> Screen.LogbookStats.routeFor(LogType.FUEL)
    }

    /** Ein Merkmal, das nur dieser Bildschirm hat — kein Text, den auch das Hauptmenue traegt (Beschriftung und Titel koennen gleich lauten). */
    private fun assertShows(screen: Screen) {
        when (screen) {
            Screen.Dashboard -> composeRule.onNodeWithTag(OIL_BAR_STRIP_TEST_TAG).assertIsDisplayed()
            Screen.FreezeFrame -> composeRule.onNodeWithText(string(R.string.freeze_frame_title)).assertIsDisplayed()
            Screen.VehicleInfo -> composeRule.onNodeWithText(string(R.string.vehicle_info_title)).assertIsDisplayed()
            Screen.TripsAndFaults -> composeRule.onNodeWithTag(tripsSectionTestTag(TripsSection.Trips)).assertIsDisplayed()
            Screen.Settings -> composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).assertIsDisplayed()
            Screen.AppInfo -> composeRule.onNodeWithText(string(R.string.app_info_title)).assertIsDisplayed()
            Screen.AppLogs -> composeRule.onNodeWithText(string(R.string.app_logs_title)).assertIsDisplayed()
            Screen.GearSettings -> composeRule.onNodeWithText(string(R.string.gear_settings_title)).assertIsDisplayed()
            Screen.Licenses -> composeRule.onNodeWithTag(LICENSES_LIST_TEST_TAG).assertIsDisplayed()
            Screen.MaintenanceIntervals -> composeRule.onNodeWithTag(MAINTENANCE_LIST_TEST_TAG).assertIsDisplayed()
            Screen.LogEntry -> composeRule.onNodeWithTag(LOGBOOK_SAVE_TEST_TAG).assertIsDisplayed()
            Screen.LogbookStats -> composeRule.onNodeWithTag(LOGBOOK_STATS_LIST_TEST_TAG).assertIsDisplayed()
        }
    }

    private val allScreens = listOf(
        Screen.Dashboard, Screen.FreezeFrame, Screen.VehicleInfo, Screen.TripsAndFaults, Screen.Settings, Screen.AppInfo,
        Screen.AppLogs, Screen.GearSettings, Screen.Licenses, Screen.MaintenanceIntervals, Screen.LogEntry, Screen.LogbookStats,
    )

    private fun navigateTo(route: String) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    @Test
    fun theAppStartsOnTheDashboard() {
        showHost()

        assertEquals(Screen.Dashboard.route, navController.currentDestination?.route)
        assertShows(Screen.Dashboard)
    }

    @Test
    fun everyScreenOfTheAppIsRegisteredInTheHostAndShowsItsOwnContent() {
        showHost()

        for (screen in allScreens) {
            navigateTo(routeToOpen(screen))
            try {
                assertShows(screen)
            } catch (e: AssertionError) {
                throw AssertionError("Screen ${screen.route} zeigt seinen Bildschirm nicht: ${e.message}", e)
            }
            assertEquals("die Zielroute von ${screen.route}", screen.route, navController.currentDestination?.route)
        }
    }

    @Test
    fun backFromASubPageReturnsToThePageItCameFrom() {
        showHost()
        navigateTo(Screen.Settings.route)
        navigateTo(Screen.GearSettings.route)

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()

        assertEquals(Screen.Settings.route, navController.currentDestination?.route)
        assertShows(Screen.Settings)
    }

    @Test
    fun theLogEntryPageTakesItsIdFromTheRoute() {
        // Eine echte Zeile: fuer eine unbekannte Id geht die Seite zurueck (s. der naechste Test).
        val id = runBlocking {
            logbook.save(LogEntry(type = LogType.OIL, date = LocalDate.of(2026, 10, 1), odometerKm = 1_000, amountMl = 250, fullTank = null, costCents = null, note = null, brand = null))
        }
        showHost()

        navigateTo(Screen.LogEntry.routeFor(id))

        assertEquals(id, navController.currentBackStackEntry?.arguments?.getLong(LOG_ENTRY_ID_ARG))
        assertShows(Screen.LogEntry)
    }

    @Test
    fun aLogEntryIdThatDoesNotExistLeadsBackToThePageTheUserCameFrom() {
        showHost()

        navigateTo(Screen.LogEntry.routeFor(424_242))

        assertEquals("nichts zu bearbeiten: zurueck zum Dashboard", Screen.Dashboard.route, navController.currentDestination?.route)
        assertShows(Screen.Dashboard)
    }

    @Test
    fun theStatsPageTakesItsTypeFromTheRoute() {
        showHost()

        navigateTo(Screen.LogbookStats.routeFor(LogType.OIL))

        assertEquals(LogType.OIL.name, navController.currentBackStackEntry?.arguments?.getString(LOG_STATS_TYPE_ARG))
        assertShows(Screen.LogbookStats)
    }
}
