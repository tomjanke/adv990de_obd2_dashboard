package de.adv990de.obd2.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Compose UI tests for the three building blocks of the grouped Settings page: [SettingsGroupHeader],
 * [SettingsLinkRow] and [SettingsSwitchRow]. They carry the two rules of the design profile that the
 * old hand-written rows broke: every settings row is at least 64 dp high (glove-friendly), and a
 * switch row toggles from ANYWHERE on the row, not only from the small switch.
 *
 * The Hilt boilerplate (`@BindValue ObdRepository`) is required per class by this project's
 * convention, see [de.adv990de.obd2.ui.components.SectionLabelTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsRowsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // --- group header ---

    @Test
    fun groupHeader_isRenderedInCapitalsAndMarkedAsAHeading() {
        composeRule.setContent { MaterialTheme { SettingsGroupHeader("Verbindung") } }

        composeRule
            .onNode(hasText("VERBINDUNG") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
    }

    // --- link row ---

    @Test
    fun linkRow_showsTitleAndSummary() {
        composeRule.setContent {
            MaterialTheme { SettingsLinkRow(title = "Getriebe", summary = "Radumfang und Übersetzungen", onClick = {}) }
        }

        composeRule.onNodeWithText("Getriebe").assertIsDisplayed()
        composeRule.onNodeWithText("Radumfang und Übersetzungen").assertIsDisplayed()
    }

    @Test
    fun linkRow_tapOnTheRowCallsOnClick() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme { SettingsLinkRow(title = "Getriebe", summary = "Summary", onClick = { clicks++ }) }
        }

        composeRule.onNodeWithText("Getriebe").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun linkRow_isAtLeast64dpHigh() {
        composeRule.setContent {
            MaterialTheme { SettingsLinkRow(title = "Getriebe", summary = "S", onClick = {}, modifier = Modifier.testTag("row")) }
        }

        composeRule.onNodeWithTag("row").assertHeightIsAtLeast(64.dp)
    }

    // --- switch row ---

    @Test
    fun switchRow_exposesTheToggleStateOnTheRowItself() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSwitchRow("On row", "S", checked = true, onCheckedChange = {}, modifier = Modifier.testTag("on"))
                SettingsSwitchRow("Off row", "S", checked = false, onCheckedChange = {}, modifier = Modifier.testTag("off"))
            }
        }

        composeRule.onNodeWithTag("on").assertIsOn()
        composeRule.onNodeWithTag("off").assertIsOff()
    }

    @Test
    fun switchRow_tapOnTheTitleTogglesTheWholeRow() {
        // The whole row is the touch target, not just the small switch — glove-friendly.
        val reported = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme { SettingsSwitchRow("Imperial", "S", checked = false, onCheckedChange = { reported += it }) }
        }

        composeRule.onNodeWithText("Imperial").performClick()

        assertEquals(listOf(true), reported)
    }

    @Test
    fun switchRow_isAtLeast64dpHigh() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSwitchRow("T", "S", checked = false, onCheckedChange = {}, modifier = Modifier.testTag("row"))
            }
        }

        composeRule.onNodeWithTag("row").assertHeightIsAtLeast(64.dp)
    }
}
