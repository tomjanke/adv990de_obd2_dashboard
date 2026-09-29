package de.adv990de.obd2.ui.settings

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
 * GPS-speed switch / background-location escalation tests below pre-grant ACCESS_FINE_LOCATION
 * and ACCESS_BACKGROUND_LOCATION via [GrantPermissionRule] (the same precedent
 * `ObdForegroundServiceTest`/`GearRatioFieldUiTest` use for permission-gated instrumentation
 * tests) so that toggling the switch to "on" never has to open a real system permission dialog —
 * there is no established pattern in this codebase for driving or dismissing one mid-test, and a
 * lingering dialog would risk breaking whichever test runs next. That constraint means the
 * "permission not yet granted -> request launched" branch and the "escalation actually fires" path
 * cannot be exercised end-to-end here; see `SettingsScreenTest` in `test` (JVM) for full coverage
 * of those branches via the extracted pure functions `determineGpsSpeedSwitchAction` and
 * `shouldEscalateToBackgroundLocation`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
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
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    /**
     * The real DataStore-backed instance (see `TestAppModule`) — same direct-injection precedent
     * as `SimulatedDashboardTest.appPreferences`. Used to pre-seed `useGpsSpeed` /
     * `backgroundLocationPrompted` to a known state before each test and to read back
     * `backgroundLocationPrompted` afterwards, since the DataStore file persists across test runs
     * on a real device/emulator and must not be trusted to already be at its default.
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

    /** Polls (like `SimulatedDashboardTest.awaitLiveTiles()`) instead of a single `waitForIdle()`:
     *  the switches' StateFlows (`useGpsSpeed`, `hideNavOnDrive`, ...) are seeded from
     *  AppPreferences/DataStore, whose real value can arrive a beat after the frame Compose
     *  considers idle.
     *
     *  Scrolls the target node into view first: the settings list has grown past what fits in
     *  the initially-composed [LazyColumn] viewport (since the reconnect-base-delay field landed
     *  right above these switches), so `onNodeWithTag` alone would never find them. */
    private fun waitUntilSwitchIsOn(testTag: String, expected: Boolean) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(testTag))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                if (expected) {
                    composeRule.onNodeWithTag(testTag).assertIsOn()
                } else {
                    composeRule.onNodeWithTag(testTag).assertIsOff()
                }
            }.isSuccess
        }
    }

    private fun waitUntilGpsSpeedSwitchIsOn(expected: Boolean) =
        waitUntilSwitchIsOn(GPS_SPEED_SWITCH_TEST_TAG, expected)

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

        composeRule.onNodeWithTag(GPS_SPEED_SWITCH_TEST_TAG).performClick()
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

        composeRule.onNodeWithTag(GPS_SPEED_SWITCH_TEST_TAG).performClick()
        waitUntilGpsSpeedSwitchIsOn(expected = false)

        assertEquals(false, appPreferences.useGpsSpeed.first())
    }

    // ── escalateToBackgroundLocationIfNeeded(): the one branch safe to drive end-to-end ───────

    @Test
    fun escalateToBackgroundLocation_doesNotPrompt_whenBackgroundLocationAlreadyGranted() = runBlocking {
        // permissionRule grants ACCESS_BACKGROUND_LOCATION for this whole class, so
        // shouldEscalateToBackgroundLocation()'s needsBackgroundLocationRequest(...) check is
        // false regardless of backgroundLocationPrompted — the real, safe-to-drive case.
        appPreferences.setUseGpsSpeed(false)
        appPreferences.setBackgroundLocationPrompted(false)

        launchSettingsScreen()
        waitUntilGpsSpeedSwitchIsOn(expected = false)

        // Flips useGpsSpeed true, which re-runs the screen's LaunchedEffect(useGpsSpeed) and
        // therefore escalateToBackgroundLocationIfNeeded() for real.
        composeRule.onNodeWithTag(GPS_SPEED_SWITCH_TEST_TAG).performClick()
        waitUntilGpsSpeedSwitchIsOn(expected = true)
        composeRule.waitForIdle()

        // Had escalation incorrectly fired despite the grant, this would have flipped to true.
        assertEquals(false, appPreferences.backgroundLocationPrompted.first())
    }

    // ── hideNavOnDrive switch: follows the toggle; location is only a side request ───────────
    //
    // permissionRule pre-grants ACCESS_FINE_LOCATION, so shouldRequestLocationForWeather() is false
    // here and no system dialog can open — the "request launched" branch is covered by the JVM
    // SettingsScreenTest (see class KDoc for why it cannot be driven end-to-end).

    @Test
    fun hideNavOnDriveSwitch_turnsOn_withoutPrompt_whenLocationIsAlreadyGranted() = runBlocking {
        appPreferences.setHideNavOnDrive(false)
        // Other tests in this class leave useGpsSpeed=true behind; seed it so the check below
        // really proves this switch did not touch it.
        appPreferences.setUseGpsSpeed(false)

        launchSettingsScreen()
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = false)

        composeRule.onNodeWithTag(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG).performClick()
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

        composeRule.onNodeWithTag(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG).performClick()
        waitUntilSwitchIsOn(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG, expected = false)

        assertEquals(false, appPreferences.hideNavOnDrive.first())
    }
}
