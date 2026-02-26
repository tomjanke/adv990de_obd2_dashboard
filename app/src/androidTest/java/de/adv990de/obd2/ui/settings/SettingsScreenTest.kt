package de.adv990de.obd2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
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

    @Test
    fun settingsTitle_isVisible() {
        launchSettingsScreen()
        composeRule
            .onNodeWithText("Settings")
            .assertIsDisplayed()
    }

    @Test
    fun pairedDevicesSection_isVisible() {
        launchSettingsScreen()
        composeRule
            .onNodeWithText("Paired Devices")
            .assertIsDisplayed()
    }

    @Test
    fun emptyState_noPairedDevicesMessage_isVisible() {
        // BluetoothDeviceScanner is a relaxed mock from TestAppModule.
        // In DEBUG builds bondedDevices() returns [OBD Sim, ...real...]; the relaxed mock
        // returns an empty list, so the "No paired OBD2 devices found" text is shown.
        launchSettingsScreen()
        composeRule
            .onNodeWithText("No paired OBD2 devices found.\nPlease pair the adapter in system Bluetooth settings first.")
            .assertIsDisplayed()
    }
}
