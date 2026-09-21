package de.adv990de.obd2.ui.dtc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [DtcScreen].
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn, replaces AppModule) provides the infrastructure bindings
 *   (BluetoothAdapter, BluetoothDeviceScanner, DataStore, in-memory AppDatabase/AppLogDao).
 * - [ObdRepository] is supplied via @BindValue so that its suspend fun [readDtcs] can be
 *   stubbed per test with [coEvery] before [setContent] is called.
 * - [DtcViewModel] calls [refresh] via LaunchedEffect(Unit) immediately after composition;
 *   the mock must therefore be configured before [setContent].
 * - [isConnectedFlow] and [isMovingFlow] are stubbed because other @HiltViewModels in the
 *   component graph (SettingsViewModel, DriveStateViewModel) subscribe to them eagerly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DtcScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
        coEvery { readDtcs() } returns emptyList()
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // --- helpers ---

    private fun launchDtcScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            DtcScreen(navController = navController)
        }
    }

    // --- tests ---

    @Test
    fun dtcScreen_showsTitle() {
        launchDtcScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_title))
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_rendersWithoutCrash_whenNoDtcs() {
        // readDtcs() already stubbed to return emptyList() in @BindValue initialiser.
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_title))
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsFaultCode_whenDtcListNonEmpty() {
        coEvery { obdRepository.readDtcs() } returns listOf(
            DtcEntry(code = "P0300", description = "Random Misfire", isPending = false)
        )
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("P0300")
            .assertIsDisplayed()
    }
}
