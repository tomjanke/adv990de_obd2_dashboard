package de.adv990de.obd2.ui.freezeframe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import de.adv990de.obd2.obd.PidRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for [FreezeFrameScreen].
 *
 * [ObdRepository] is hardware-coupled and replaced via @BindValue, mirroring
 * DashboardScreenTest's strategy — see that file's class KDoc for the full rationale.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FreezeFrameScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        coEvery { readFreezeFrame() } returns mapOf(PidRegistry.RPM.id to "1726.0 rpm")
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchFreezeFrameScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            FreezeFrameScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    /**
     * The repository keys its result by PID id ("010C"), not by name — the screen must resolve
     * the localized label (PidRegistry.all[id].labelRes) itself. On a German device this is
     * "Drehzahl", never the raw id or the English PidDefinition.name ("RPM").
     */
    @Test
    fun freezeFrame_showsLocalizedPidLabel_notRawIdOrEnglishName() {
        launchFreezeFrameScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pid_rpm))
            .assertIsDisplayed()
    }

    /**
     * FreezeFrameViewModel.load() sets isLoading=true synchronously before the first suspension
     * point (repo.readFreezeFrame()), so a repo call that never resolves keeps the screen in the
     * loading state indefinitely — the same technique FreezeFrameViewModelTest's "isLoading is
     * true during load" test uses (CompletableDeferred instead of a StandardTestDispatcher, since
     * this is a real-device instrumentation test with no test dispatcher to advance).
     */
    @Test
    fun freezeFrame_showsLoadingIndicator_whileLoading() {
        val deferred = CompletableDeferred<Map<String, String>>()
        coEvery { obdRepository.readFreezeFrame() } coAnswers { deferred.await() }

        composeRule.setContent {
            val navController = rememberNavController()
            FreezeFrameScreen(navController = navController)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag(FREEZE_FRAME_LOADING_TEST_TAG).assertIsDisplayed()
            }.isSuccess
        }

        // Unblock the suspended coroutine so it doesn't leak past the test.
        deferred.complete(emptyMap())
    }

    /**
     * FreezeFrameViewModel.load() catches any non-cancellation Exception and stores e.message
     * verbatim; FreezeFrameScreen renders it via freeze_frame_error_prefix ("Error: %1$s"), so the
     * mock's exception message must appear inside that formatted string, same convention as
     * DtcScreenTest's "showsErrorMessage_whenReadDtcsFails" (there the screen renders the raw
     * message with no wrapping string resource).
     */
    @Test
    fun freezeFrame_showsErrorMessageAndRetryButton_whenLoadFails() {
        coEvery { obdRepository.readFreezeFrame() } throws RuntimeException("Connection lost")

        launchFreezeFrameScreen()

        composeRule
            .onNodeWithText(
                composeRule.activity.getString(R.string.freeze_frame_error_prefix, "Connection lost")
            )
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.retry))
            .assertIsDisplayed()
    }

    /**
     * The retry Button's onClick calls vm.load() again — the same function LaunchedEffect(Unit)
     * already invoked once on first composition — so a successful retry click must bring the
     * total readFreezeFrame() call count to 2. This exercises the actual retry callback rather
     * than only asserting on the button's presence.
     */
    @Test
    fun freezeFrame_retryButtonClick_reloadsFreezeFrameData() {
        coEvery { obdRepository.readFreezeFrame() } throws RuntimeException("Connection lost")

        launchFreezeFrameScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.retry))
            .assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.retry))
            .performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 2) { obdRepository.readFreezeFrame() }
    }

    /**
     * data.isEmpty() (no error, load finished) renders freeze_frame_empty — distinct from both the
     * error branch above and the default class-level stub (which returns one PID).
     */
    @Test
    fun freezeFrame_showsEmptyMessage_whenNoDataAvailable() {
        coEvery { obdRepository.readFreezeFrame() } returns emptyMap()

        launchFreezeFrameScreen()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.freeze_frame_empty))
            .assertIsDisplayed()
    }
}
