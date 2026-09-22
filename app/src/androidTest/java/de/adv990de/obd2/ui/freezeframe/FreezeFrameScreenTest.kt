package de.adv990de.obd2.ui.freezeframe

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
import de.adv990de.obd2.obd.PidRegistry
import io.mockk.coEvery
import io.mockk.mockk
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
}
