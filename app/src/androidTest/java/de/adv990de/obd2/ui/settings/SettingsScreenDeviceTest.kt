package de.adv990de.obd2.ui.settings

import android.Manifest
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.components.FUEL_GAUGE_ROW_TEST_TAG
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.transfer.TRANSFER_MODE_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PART_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PICK_ARG
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Compose UI tests for [SettingsScreen].
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn, replaces AppModule) provides BluetoothDeviceScanner (relaxed
 *   mock), BluetoothAdapter (relaxed mock), DataStore, and in-memory AppDatabase/AppLogDao.
 *   AppPreferences and AppLogRepository are therefore constructed normally by Hilt from those.
 * - [ObdRepository] is a concrete @Singleton with Bluetooth/connection dependencies not available
 *   in tests, so it is supplied via @BindValue and replaced for this test class.
 * - The four @HiltViewModels (SettingsViewModel, RecordingSettingsViewModel,
 *   DisplaySettingsViewModel, DriveStateViewModel) are constructed by Hilt automatically once
 *   all their transitive dependencies are satisfied.
 *
 * GPS-speed switch tests below pre-grant ACCESS_FINE_LOCATION
 * via [GrantPermissionRule] (the same precedent
 * `ObdForegroundServiceDeviceTest`/`GearRatioFieldUiTest` use for permission-gated instrumentation
 * tests) so that toggling the switch to "on" never has to open a real system permission dialog —
 * there is no established pattern in this codebase for driving or dismissing one mid-test, and a
 * lingering dialog would risk breaking whichever test runs next. That constraint means the
 * "permission not yet granted -> request launched" branch cannot be exercised end-to-end here;
 * see `SettingsScreenTest` in `test` (JVM) for full coverage of that branch via the extracted
 * pure function `determineGpsSpeedSwitchAction`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Replaces the ObdRepository @Singleton binding for this test class.
     * isConnectedFlow and isMovingFlow are StateFlows that the ViewModels subscribe to
     * eagerly in init{} / stateIn(SharingStarted.Eagerly), so they must be non-null at
     * injection time.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    /**
     * The DataStore-backed instance (a per-test store, see `TestAppModule`) — same direct-injection precedent
     * as `SimulatedDashboardTest.appPreferences`. Used to pre-seed `useGpsSpeed` to a known state
     * before each test, since the DataStore file persists across test runs on a real device/emulator
     * and must not be trusted to already be at its default.
     */
    @Inject
    lateinit var appPreferences: AppPreferences

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchSettingsScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            SettingsScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    /**
     * Scrolls [testTag] into view and then 300 px back. `performScrollToNode` aligns the row with the TOP
     * edge of the list, and this list extends under the pinned top app bar (the Scaffold padding is its
     * `contentPadding`): with the short viewport of a sideways-lying phone the row ends up BEHIND the bar,
     * and a click at the centre of a short row (hide-nav: 198 px) hits the bar instead. Scrolling back
     * brings it fully into view in either orientation.
     */
    private fun scrollToClearView(testTag: String) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(testTag))
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -300f) }
        composeRule.waitForIdle()
    }

    /** Polls (like `SimulatedDashboardTest.awaitLiveTiles()`) instead of a single `waitForIdle()`:
     *  the switches' StateFlows (`useGpsSpeed`, `hideNavOnDrive`, ...) are seeded from
     *  AppPreferences/DataStore, whose real value can arrive a beat after the frame Compose
     *  considers idle.
     *
     *  Scrolls the target node into view first: the settings list has grown past what fits in
     *  the initially-composed [LazyColumn] viewport (since the reconnect-base-delay field landed
     *  right above these switches), so `onNodeWithTag` alone would never find them. */
    private fun waitUntilSwitchIsOn(testTag: String, expected: Boolean) {
        // Die Zeile wird in jedem Durchlauf neu angesteuert: ein Schalter, der umspringt, kann das Layout verschieben (im Querformat, 448 dp hoch, schiebt es die Zeile aus dem komponierten Bereich),
        // und eine nicht komponierte Zeile gibt es im Baum nicht. Erst danach wird sie fuer den naechsten Klick frei gestellt.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(testTag))
                if (expected) {
                    composeRule.onNodeWithTag(testTag).assertIsOn()
                } else {
                    composeRule.onNodeWithTag(testTag).assertIsOff()
                }
            }.isSuccess
        }
        scrollToClearView(testTag)
    }

    /** Steuert die Zeile neu an und loest den Klick ueber die Semantik aus (ohne Koordinaten): nach `scrollToClearView` kann sie im Querformat ausserhalb des komponierten Bereichs liegen. */
    private fun clickSwitchRow(testTag: String) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(testTag))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitUntilGpsSpeedSwitchIsOn(expected: Boolean) =
        waitUntilSwitchIsOn(GPS_SPEED_SWITCH_TEST_TAG, expected)

    @Test
    fun fuelRangeRow_isInTheVehicleGroup_andSavesTheEnteredRange() {
        runBlocking { appPreferences.setFuelRangeFullKm(0) }
        launchSettingsScreen()
        scrollToClearView(FUEL_RANGE_ROW_TEST_TAG)

        composeRule.onNodeWithTag(FUEL_RANGE_ROW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_fuel_range_not_set)).assertIsDisplayed()

        composeRule.onNodeWithTag(FUEL_RANGE_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).performTextInput("320")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_fuel_range_save)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { runBlocking { appPreferences.fuelRangeFullKm.first() } == 320 }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_fuel_range_summary, 320)).assertIsDisplayed()
    }

    @Test
    fun theFuelGaugeRow_isNotShownOnTheSettingsPage_evenWithAConfiguredRange() {
        runBlocking { appPreferences.setFuelRangeFullKm(320) }
        launchSettingsScreen()

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsTitle_isVisible() {
        launchSettingsScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.settings_title))
            .assertIsDisplayed()
    }

    @Test
    fun pairedDevicesSection_isVisible() {
        launchSettingsScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.settings_paired_devices))
            .assertIsDisplayed()
    }

    @Test
    fun emptyState_noPairedDevicesMessage_isVisible() {
        // BluetoothDeviceScanner is a relaxed mock from TestAppModule.
        // In DEBUG builds bondedDevices() returns [OBD Sim, ...real...]; the relaxed mock
        // returns an empty list, so the "No paired OBD2 devices found" text is shown.
        launchSettingsScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.settings_no_paired_devices))
            .assertIsDisplayed()
    }

    // ── GPS-speed switch: granted / off branches (see class KDoc for what's out of scope) ────

    @Test
    fun gpsSpeedSwitch_turnsOn_whenFineLocationIsAlreadyGranted() = runBlocking {
        // Starts from the StateFlow's own default (false) — no DataStore pre-seed/wait needed.
        appPreferences.setUseGpsSpeed(false)

        launchSettingsScreen()
        waitUntilGpsSpeedSwitchIsOn(expected = false)

        clickSwitchRow(GPS_SPEED_SWITCH_TEST_TAG)
        waitUntilGpsSpeedSwitchIsOn(expected = true)

        // ACCESS_FINE_LOCATION is pre-granted by permissionRule, so this must have taken the
        // `granted -> displayVm.setUseGpsSpeed(true)` branch directly, never the permission
        // request branch.
        assertEquals(true, appPreferences.useGpsSpeed.first())
    }

    @Test
    fun gpsSpeedSwitch_turnsOff_regardlessOfPermissionGrant() = runBlocking {
        appPreferences.setUseGpsSpeed(true)

        launchSettingsScreen()
        waitUntilGpsSpeedSwitchIsOn(expected = true)

        clickSwitchRow(GPS_SPEED_SWITCH_TEST_TAG)
        waitUntilGpsSpeedSwitchIsOn(expected = false)

        assertEquals(false, appPreferences.useGpsSpeed.first())
    }

    // ── hideNavOnDrive switch: follows the toggle; location is only a side request ───────────
    //
    // permissionRule pre-grants ACCESS_FINE_LOCATION, so shouldRequestLocationForWeather() is false
    // here and no system dialog can open — the "request launched" branch is covered by the JVM
    // SettingsScreenDeviceTest (see class KDoc for why it cannot be driven end-to-end).

    @Test
    fun hideNavOnDriveSwitch_turnsOn_withoutPrompt_whenLocationIsAlreadyGranted() = runBlocking {
        appPreferences.setHideNavOnDrive(false)
        // Other tests in this class leave useGpsSpeed=true behind; seed it so the check below
        // really proves this switch did not touch it.
        appPreferences.setUseGpsSpeed(false)

        launchSettingsScreen()
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = false)

        clickSwitchRow(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG)
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = true)

        assertEquals(true, appPreferences.hideNavOnDrive.first())
        // Unrelated to hideNavOnDrive: turning it on must not touch the GPS-speed setting.
        assertEquals(false, appPreferences.useGpsSpeed.first())

        appPreferences.setHideNavOnDrive(false) // leave the shared on-device DataStore as found
    }

    @Test
    fun hideNavOnDriveSwitch_turnsOff() = runBlocking {
        appPreferences.setHideNavOnDrive(true)

        launchSettingsScreen()
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = true)

        clickSwitchRow(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG)
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = false)

        assertEquals(false, appPreferences.hideNavOnDrive.first())
    }

    // ── "Bei Dunkelheit dimmen": Standard an, der Schalter schreibt nur seinen Schluessel ────────

    @Test
    fun dimInDarkSwitch_isOnByDefault_andTurnsOffAndOn() = runBlocking {
        // frischer DataStore je Test (TestAppModule): nichts gesetzt = der Standard
        launchSettingsScreen()
        waitUntilSwitchIsOn(DIM_IN_DARK_SWITCH_TEST_TAG, expected = true)
        assertEquals(true, appPreferences.dimInDark.first())

        composeRule.onNodeWithTag(DIM_IN_DARK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(DIM_IN_DARK_SWITCH_TEST_TAG, expected = false)
        assertEquals(false, appPreferences.dimInDark.first())

        composeRule.onNodeWithTag(DIM_IN_DARK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(DIM_IN_DARK_SWITCH_TEST_TAG, expected = true)
        assertEquals(true, appPreferences.dimInDark.first())
    }

    // ── Roadbook: Schalter (Standard an) und Empfindlichkeit (nur bei eingeschaltetem Roadbook) ────────

    private fun sensitivityOption(level: Int) = composeRule.onNode(
        hasText(composeRule.activity.getString(
            when (level) {
                1 -> R.string.roadbook_sens_only_sharp
                2 -> R.string.roadbook_sens_default
                else -> R.string.roadbook_sens_also_slight
            },
        )) and hasAnyAncestor(hasTestTag(ROADBOOK_SENSITIVITY_CHOICE_TEST_TAG)),
    )

    @Test
    fun roadbookSwitch_isOnByDefault_andTurnsOffAndOn() = runBlocking {
        launchSettingsScreen()
        waitUntilSwitchIsOn(ROADBOOK_SWITCH_TEST_TAG, expected = true)
        assertEquals(true, appPreferences.roadbookEnabled.first())

        composeRule.onNodeWithTag(ROADBOOK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(ROADBOOK_SWITCH_TEST_TAG, expected = false)
        assertEquals(false, appPreferences.roadbookEnabled.first())

        composeRule.onNodeWithTag(ROADBOOK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(ROADBOOK_SWITCH_TEST_TAG, expected = true)
        assertEquals(true, appPreferences.roadbookEnabled.first())
    }

    @Test
    fun roadbookSwitch_showsTitleAndDescription_andTouchesNoOtherSwitch() = runBlocking {
        appPreferences.setDimInDark(true)
        launchSettingsScreen()
        scrollToClearView(ROADBOOK_SWITCH_TEST_TAG)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_roadbook)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_roadbook_desc)).assertIsDisplayed()
        composeRule.onNodeWithTag(ROADBOOK_SWITCH_TEST_TAG).assertHeightIsAtLeast(64.dp)

        composeRule.onNodeWithTag(ROADBOOK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(ROADBOOK_SWITCH_TEST_TAG, expected = false)
        assertEquals(true, appPreferences.dimInDark.first())
    }

    @Test
    fun roadbookSensitivity_isStandardByDefault_andWritesOnlyItsKey() = runBlocking {
        launchSettingsScreen()
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(ROADBOOK_SENSITIVITY_CHOICE_TEST_TAG))
        for (level in 1..3) sensitivityOption(level).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        sensitivityOption(2).assertIsSelected()
        assertEquals(2, appPreferences.roadbookSensitivity.first())

        sensitivityOption(1).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runBlocking { appPreferences.roadbookSensitivity.first() } == 1 }
        sensitivityOption(1).assertIsSelected()
        sensitivityOption(2).assertIsNotSelected()
        assertEquals("the switch is untouched", true, appPreferences.roadbookEnabled.first())

        sensitivityOption(3).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { runBlocking { appPreferences.roadbookSensitivity.first() } == 3 }
    }

    @Test
    fun roadbookSensitivity_isHiddenWhileTheRoadbookIsOff() = runBlocking<Unit> {
        appPreferences.setRoadbookEnabled(false)
        launchSettingsScreen()
        scrollToClearView(ROADBOOK_SWITCH_TEST_TAG)
        composeRule.onAllNodesWithTag(ROADBOOK_SENSITIVITY_CHOICE_TEST_TAG).assertCountEquals(0)

        composeRule.onNodeWithTag(ROADBOOK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(ROADBOOK_SWITCH_TEST_TAG, expected = true)
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(ROADBOOK_SENSITIVITY_CHOICE_TEST_TAG))
        composeRule.onNodeWithTag(ROADBOOK_SENSITIVITY_CHOICE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun dimInDarkSwitch_showsTitleAndDescription_andTouchesNoOtherSwitch() = runBlocking {
        appPreferences.setHideNavOnDrive(false)
        launchSettingsScreen()
        scrollToClearView(DIM_IN_DARK_SWITCH_TEST_TAG)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_dim_in_dark)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_dim_in_dark_desc)).assertIsDisplayed()
        composeRule.onNodeWithTag(DIM_IN_DARK_SWITCH_TEST_TAG).assertHeightIsAtLeast(64.dp)

        composeRule.onNodeWithTag(DIM_IN_DARK_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(DIM_IN_DARK_SWITCH_TEST_TAG, expected = false)
        assertEquals(false, appPreferences.hideNavOnDrive.first())
    }

    // ── Connection choices (fixed options instead of free-text number fields) ──

    /** The segment showing [option] inside the choice group tagged [groupTag]. "10" and "30" exist
     *  in BOTH groups, so the segment cannot be found by its text alone. */
    private fun choiceOption(groupTag: String, option: Int) =
        composeRule.onNode(hasText(option.toString()) and hasAnyAncestor(hasTestTag(groupTag)))

    private fun waitUntilSelected(groupTag: String, option: Int) =
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { choiceOption(groupTag, option).assertIsSelected() }.isSuccess
        }

    private fun seriesText(base: Int): String {
        val summary = reconnectBackoffSummary(base)
        return composeRule.activity.getString(
            R.string.settings_reconnect_base_delay_series,
            summary.delaysSeconds.joinToString(" · "),
            formatMinutesSeconds(summary.totalSeconds)
        )
    }

    @Test
    fun connectionChoices_showTheFixedOptions_andPersistTheSelection() = runBlocking<Unit> {
        // The DataStore file persists across tests on a real device: start from the defaults and
        // put them back afterwards.
        appPreferences.setObdWatchdogTimeoutSeconds(AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS)
        appPreferences.setReconnectBaseDelaySeconds(AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS)
        try {
            launchSettingsScreen()

            // Watchdog timeout: 10/20/30/60/120, the default 20 selected.
            composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)
                .performScrollToNode(hasTestTag(OBD_WATCHDOG_CHOICE_TEST_TAG))
            for (option in AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS) {
                choiceOption(OBD_WATCHDOG_CHOICE_TEST_TAG, option).assertIsDisplayed()
            }
            waitUntilSelected(OBD_WATCHDOG_CHOICE_TEST_TAG, 20)

            choiceOption(OBD_WATCHDOG_CHOICE_TEST_TAG, 60).performClick()
            waitUntilSelected(OBD_WATCHDOG_CHOICE_TEST_TAG, 60)
            assertEquals(60, appPreferences.obdWatchdogTimeoutSeconds.first())

            // Reconnect base delay: 3/10/30, the default 3 selected, with its wait series shown.
            composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)
                .performScrollToNode(hasTestTag(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG))
            for (option in AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS) {
                choiceOption(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG, option).assertIsDisplayed()
            }
            waitUntilSelected(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG, 3)
            composeRule.onNodeWithText(seriesText(3)).assertIsDisplayed()

            choiceOption(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG, 10).performClick()
            waitUntilSelected(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG, 10)
            assertEquals(10, appPreferences.reconnectBaseDelaySeconds.first())
            composeRule.onNodeWithText(seriesText(10)).assertIsDisplayed()
        } finally {
            appPreferences.setObdWatchdogTimeoutSeconds(AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS)
            appPreferences.setReconnectBaseDelaySeconds(AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS)
        }
    }

    // ── Grouped page: headers, link rows, the merged dashboard configuration ──

    private lateinit var navController: NavHostController

    /** The page inside a real NavHost, so link rows can really navigate (marker text per target). */
    private fun launchSettingsInNavHost() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.Settings.route) {
                composable(Screen.Settings.route) { SettingsScreen(navController = navController) }
                for (route in SUB_PAGE_ROUTES) composable(route) { Text("DESTINATION:$route") }
                // The transfer page has optional arguments (the row opens it with the defaults); the pattern is its route.
                composable(
                    Screen.DataTransfer.route,
                    arguments = listOf(
                        navArgument(TRANSFER_MODE_ARG) { type = NavType.StringType; defaultValue = "export" },
                        navArgument(TRANSFER_PART_ARG) { type = NavType.StringType; defaultValue = "" },
                        navArgument(TRANSFER_PICK_ARG) { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { Text("DESTINATION:${Screen.DataTransfer.route}") }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasText(text))
    }

    private fun str(resId: Int): String = composeRule.activity.getString(resId)

    @Test
    fun allFiveGroupHeadersArePresent() {
        launchSettingsInNavHost()

        for (header in listOf(
            R.string.settings_connection_section,
            R.string.settings_display_section,
            R.string.settings_recording_section,
            R.string.settings_group_vehicle,
            R.string.settings_group_info,
        )) {
            val text = str(header).uppercase()
            scrollTo(text)
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun theDataTransferRow_isThere_andAtLeast64dpHigh() {
        launchSettingsInNavHost()

        scrollTo(str(R.string.settings_data_transfer_summary))
        composeRule.onNodeWithText(str(R.string.settings_data_transfer)).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun linkRows_showASummary_andOpenTheirSubPage() {
        launchSettingsInNavHost()

        for ((title, summary, route) in listOf(
            Triple(R.string.settings_gear_ratios, R.string.settings_gear_ratios_summary, Screen.GearSettings.route),
            Triple(R.string.settings_maintenance_title, R.string.settings_maintenance_summary, Screen.MaintenanceIntervals.route),
            Triple(R.string.settings_vehicle_info, R.string.settings_vehicle_info_summary, Screen.VehicleInfo.route),
            Triple(R.string.settings_data_transfer, R.string.settings_data_transfer_summary, Screen.DataTransfer.route),
            Triple(R.string.settings_app_logs, R.string.settings_app_logs_summary, Screen.AppLogs.route),
            Triple(R.string.settings_app_info, R.string.settings_app_info_summary, Screen.AppInfo.route),
        )) {
            scrollTo(str(summary))
            composeRule.onNodeWithText(str(summary)).assertIsDisplayed()

            // Ohne Koordinaten: im Querformat liegt eine bis zur Oberkante gescrollte Zeile hinter der Kopfleiste, ein Klick auf ihre Mitte traefe die Leiste (s. scrollToClearView).
            composeRule.onNodeWithText(str(title)).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertEquals(route, navController.currentDestination?.route)

            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.waitForIdle()
        }
    }

    /**
     * Die Liste bleibt waehrend der Ueberblendung zur Unterseite beruehrbar (700 ms): ein zweiter Tipp legte die Unterseite vorher ein zweites Mal an, "Zurueck" brauchte dann
     * zwei Schritte. Ohne laufende Uhr steht die Zeile nach dem ersten Tipp noch unter dem Finger, wie in der App.
     */
    @Test
    fun aDoubleTapOnALinkRow_opensItsSubPageOnce() {
        launchSettingsInNavHost()
        scrollTo(str(R.string.settings_app_info_summary))

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(str(R.string.settings_app_info)).performClick()
        composeRule.onNodeWithText(str(R.string.settings_app_info)).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertEquals(Screen.AppInfo.route, navController.currentDestination?.route)
            assertEquals(1, navController.currentBackStack.value.count { it.destination.route == Screen.AppInfo.route })
        }
    }

    @Test
    fun theSeparateDashboardConfigurationEntryIsGone_itsContentLivesInDashboardAndDisplay() {
        launchSettingsInNavHost()

        // no stand-alone entry any more (either language)
        composeRule.onNodeWithText("Dashboard konfigurieren").assertDoesNotExist()
        composeRule.onNodeWithText("Configure Dashboard").assertDoesNotExist()

        // ... the blink threshold and its dynamic variant are part of the page itself
        scrollTo(str(R.string.dashboard_rpm_threshold_label))
        composeRule.onNodeWithText(str(R.string.dashboard_rpm_threshold_label)).assertIsDisplayed()
    }

    private companion object {
        val SUB_PAGE_ROUTES = listOf(
            Screen.GearSettings.route, Screen.VehicleInfo.route, Screen.MaintenanceIntervals.route,
            Screen.AppLogs.route, Screen.AppInfo.route,
        )
    }

    // ── QS B33: "Absturzberichte senden" erscheint nur in einer Version mit eingebautem Sentry-DSN ─────────────────

    private fun launchSettingsScreenWithCrashController(available: Boolean) {
        composeRule.setContent {
            val navController = rememberNavController()
            // Auf dem Main-Thread im Composable gebaut (der ViewModel-Konstruktor startet stateIn auf dem Main-Dispatcher).
            val crashVm = remember {
                CrashReportingSettingsViewModel(appPreferences, mockk { every { isAvailable } returns available })
            }
            SettingsScreen(navController = navController, crashVm = crashVm)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun crashReportsSwitch_isHidden_inABuildWithoutDsn() {
        launchSettingsScreenWithCrashController(available = false)

        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasText(composeRule.activity.getString(R.string.settings_app_info)))
        composeRule.onAllNodesWithTag(CRASH_REPORTS_SWITCH_TEST_TAG).assertCountEquals(0)
    }

    /**
     * Die Zeile steht am Ende der Liste: `scrollToClearView` (Rueckwaerts-Scroll um 300 px) schoebe sie dort wieder aus dem
     * Sichtbereich, und eine nicht komponierte LazyColumn-Zeile gibt es im Baum nicht. Am Listenende genuegt das
     * Scrollen zum Knoten — die Zeile liegt dann ohnehin nicht hinter der Leiste.
     */
    private fun waitUntilBottomSwitchIs(testTag: String, expected: Boolean) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(testTag))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                if (expected) composeRule.onNodeWithTag(testTag).assertIsOn() else composeRule.onNodeWithTag(testTag).assertIsOff()
            }.isSuccess
        }
    }

    @Test
    fun crashReportsSwitch_isShownAndWritesThePreference_inABuildWithDsn() {
        runBlocking { appPreferences.setCrashReportsEnabled(true) }
        launchSettingsScreenWithCrashController(available = true)

        waitUntilBottomSwitchIs(CRASH_REPORTS_SWITCH_TEST_TAG, expected = true)
        composeRule.onNodeWithTag(CRASH_REPORTS_SWITCH_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { !runBlocking { appPreferences.crashReportsEnabled.first() } }
        waitUntilBottomSwitchIs(CRASH_REPORTS_SWITCH_TEST_TAG, expected = false)
        runBlocking { appPreferences.setCrashReportsEnabled(true) }   // Standard wiederherstellen
    }
}
