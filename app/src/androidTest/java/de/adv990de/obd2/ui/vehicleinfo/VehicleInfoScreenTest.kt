package de.adv990de.obd2.ui.vehicleinfo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.ObdException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [VehicleInfoScreen].
 *
 * [VehicleInfoScreen] renders one of four states from a single `when` block keyed on
 * error / isLoading / vin.isNotEmpty(). [VehicleInfoViewModelTest] already covers the VM's own
 * state transitions (success / null-VIN / error / isLoading) exhaustively — this class instead
 * pins down that the SCREEN picks the correct branch for each VM state, so a reordering bug in
 * that `when` (e.g. checking `isLoading` after `vin.isNotEmpty()`) would fail a test instead of
 * silently shipping the wrong UI.
 *
 * [ObdRepository] is hardware-coupled and replaced via @BindValue, mirroring
 * DashboardScreenTest's / FreezeFrameScreenTest's strategy — see FreezeFrameScreenTest's class
 * KDoc for the full rationale. VehicleInfoViewModel only ever calls [ObdRepository.readVin], so
 * no other member needs stubbing here (unlike DtcScreenTest/GearRatioFieldUiTest, which also
 * stub isConnectedFlow/isMovingFlow for sibling ViewModels elsewhere in the graph — this screen
 * has none).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VehicleInfoScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Never completes unless a test resolves/replaces it — keeps the VM in isLoading=true. */
    private val readVinDeferred = CompletableDeferred<String?>()

    /** Drives `isConnectedFlow`: the screen reads nothing without a connection and reloads when it comes up. */
    private val connected = MutableStateFlow(true)

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns connected
        coEvery { readVin() } coAnswers { readVinDeferred.await() }
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchVehicleInfoScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            VehicleInfoScreen(navController = navController)
        }
    }

    @Test
    fun vehicleInfoScreen_showsLoadingIndicator_whileReadVinIsPending() {
        // Default stub (readVinDeferred, unresolved) never completes -> isLoading stays true.
        launchVehicleInfoScreen()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.vehicle_info_loading))
            .assertIsDisplayed()
    }

    @Test
    fun vehicleInfoScreen_showsErrorMessageAndRetryButton_whenReadVinThrows() {
        coEvery { obdRepository.readVin() } throws RuntimeException("not connected")

        launchVehicleInfoScreen()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("not connected").assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.retry))
            .assertIsDisplayed()
    }

    @Test
    fun vehicleInfoScreen_showsVin_whenReadVinSucceeds() {
        coEvery { obdRepository.readVin() } returns "1HGBH41JXMN109186"

        launchVehicleInfoScreen()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1HGBH41JXMN109186").assertIsDisplayed()
    }

    @Test
    fun vehicleInfoScreen_tappingRetryWhileInErrorState_reInvokesLoad() {
        coEvery { obdRepository.readVin() } throws RuntimeException("not connected")

        launchVehicleInfoScreen()
        composeRule.waitForIdle()
        // The initial LaunchedEffect(Unit) already triggered one load() -> one readVin() call.
        coVerify(timeout = 5_000) { obdRepository.readVin() }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.retry))
            .performClick()

        // Retry's onClick calls vm.load() again, independent of the initial LaunchedEffect call.
        coVerify(timeout = 5_000, exactly = 2) { obdRepository.readVin() }
    }

    /** B19: without a connection the screen says "Nicht verbunden" — not "no VIN available" — and reads nothing. */
    @Test
    fun vehicleInfoScreen_saysNotConnected_andReadsNothing_withoutAConnection() {
        connected.value = false

        launchVehicleInfoScreen()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.not_connected))
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText(composeRule.activity.getString(R.string.vehicle_info_empty))
            .assertCountEquals(0)
        coVerify(exactly = 0) { obdRepository.readVin() }
    }

    /** B19: when the connection comes up while the page is open, the VIN is read by itself. */
    @Test
    fun vehicleInfoScreen_loadsByItself_whenTheConnectionComesUp() {
        coEvery { obdRepository.readVin() } returns "1HGBH41JXMN109186"
        connected.value = false
        launchVehicleInfoScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.not_connected))
            .assertIsDisplayed()

        connected.value = true

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("1HGBH41JXMN109186").assertIsDisplayed()
            }.isSuccess
        }
    }

    /** B18: a known adapter failure is shown as its localized text. */
    @Test
    fun vehicleInfoScreen_showsALocalizedReason_forAKnownAdapterFailure() {
        coEvery { obdRepository.readVin() } throws ObdException.ConnectionLost()

        launchVehicleInfoScreen()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.obd_err_connection_lost))
            .assertIsDisplayed()
    }
}
