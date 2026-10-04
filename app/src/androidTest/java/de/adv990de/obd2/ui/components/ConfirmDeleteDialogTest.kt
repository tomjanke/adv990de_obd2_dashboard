package de.adv990de.obd2.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [ConfirmDeleteDialog]: the shared "are you sure" step of the local delete
 * contract (title · consequence · cancel/delete). Covers the visible texts, both callbacks, and the
 * glove-friendly 56 dp touch height of both buttons (CLAUDE.md design principle 4 — the M3 default
 * of a dialog `TextButton` is only 40 dp).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConfirmDeleteDialogTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Required boilerplate of every @HiltAndroidTest class here, see [SectionLabelTest]. */
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

    private var confirmed = 0
    private var dismissed = 0

    private fun launch() {
        composeRule.setContent {
            MaterialTheme {
                ConfirmDeleteDialog(
                    title = "Delete everything?",
                    message = "This cannot be taken back after a moment.",
                    confirmLabel = "Delete",
                    dismissLabel = "Cancel",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun showsTitleMessageAndBothButtons() {
        launch()
        composeRule.onNodeWithText("Delete everything?").assertIsDisplayed()
        composeRule.onNodeWithText("This cannot be taken back after a moment.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun confirmButtonCallsOnConfirmOnly() {
        launch()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun dismissButtonCallsOnDismissOnly() {
        launch()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun bothButtonsAreAtLeast56dpHigh() {
        launch()
        composeRule.onNodeWithText("Delete").assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText("Cancel").assertHeightIsAtLeast(56.dp)
    }
}
