package de.adv990de.obd2.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.UiText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [PreviewBanner] (B25): the banner says WHY there is no connection. Which
 * reason is passed when (hidden during a running reconnect) is the pure [previewBannerReason], tested
 * on the JVM in `DashboardStatusDotTest`; this class covers what is drawn for either input.
 *
 * Hilt setup and the `@BindValue` repository mirror `SectionLabelTest` (the Hilt graph is validated per
 * test class, whether or not the composable under test touches it).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PreviewBannerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

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

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun withoutAReason_itIsThePlainPreviewBanner() {
        composeRule.setContent { MaterialTheme { PreviewBanner() } }

        // SectionLabel draws the label ALL-CAPS.
        composeRule.onNodeWithText(string(R.string.dashboard_preview_banner).uppercase()).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.err_reconnect_fail)).assertCountEquals(0)
    }

    @Test
    fun withAReason_itIsWrittenOutBesideTheBannerLabel() {
        composeRule.setContent {
            MaterialTheme { PreviewBanner(reason = UiText.StringResource(R.string.err_reconnect_fail)) }
        }

        composeRule.onNodeWithText(string(R.string.dashboard_preview_banner).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.err_reconnect_fail)).assertIsDisplayed()
    }

    @Test
    fun aDynamicReasonIsShownVerbatim() {
        composeRule.setContent {
            MaterialTheme { PreviewBanner(reason = UiText.DynamicString("socket closed")) }
        }

        composeRule.onNodeWithText("socket closed").assertIsDisplayed()
    }
}
