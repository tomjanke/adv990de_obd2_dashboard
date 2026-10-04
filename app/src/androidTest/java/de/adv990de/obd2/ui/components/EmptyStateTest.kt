package de.adv990de.obd2.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * Compose UI tests for [EmptyState]. The icon is decorative (`contentDescription = null`, the text
 * carries the meaning) and there is no pixel-assertion precedent in this project, so the visible
 * text is what can be asserted.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EmptyStateTest {

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

    @Test
    fun showsTheMessage() {
        composeRule.setContent {
            MaterialTheme { EmptyState(icon = Icons.AutoMirrored.Filled.List, text = "Nothing here yet") }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }
}
