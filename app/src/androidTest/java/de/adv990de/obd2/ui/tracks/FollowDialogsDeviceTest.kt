package de.adv990de.obd2.ui.tracks

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Die drei Dialoge des Nachfahrens: Menue hinter dem gruenen Knopf, Wechsel des Tracks, Aufzeichnung laeuft. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FollowDialogsDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private var openMap = 0
    private var stop = 0
    private var dismiss = 0
    private var confirm = 0

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent { AppTheme(colorScheme = lightColorScheme(), typography = AppTypography) { block() } }
        composeRule.waitForIdle()
    }

    @Test
    fun theMenuNamesTheTrackAndOffersTheMapAndTheEnd() {
        content { FollowMenuDialog(FollowInfo(5, "Sonntagsrunde"), { openMap++ }, { stop++ }, { dismiss++ }) }
        composeRule.onNodeWithText(string(R.string.follow_menu_title, "Sonntagsrunde")).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_MENU_OPEN_MAP_TEST_TAG).assertIsDisplayed().assertTouchTargetAtLeast56(Density(composeRule.activity))
        composeRule.onNodeWithTag(FOLLOW_MENU_STOP_TEST_TAG).assertIsDisplayed().assertTouchTargetAtLeast56(Density(composeRule.activity))
        composeRule.onNodeWithText(string(R.string.follow_menu_open_map)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_stop)).assertIsDisplayed()
    }

    @Test
    fun theMenuButtonsCallTheirOwnHandlerOnly() {
        content { FollowMenuDialog(FollowInfo(5, "Sonntagsrunde"), { openMap++ }, { stop++ }, { dismiss++ }) }
        composeRule.onNodeWithTag(FOLLOW_MENU_OPEN_MAP_TEST_TAG).performClick()
        assertEquals(1, openMap)
        assertEquals(0, stop)
        composeRule.onNodeWithTag(FOLLOW_MENU_STOP_TEST_TAG).performClick()
        assertEquals(1, stop)
        assertEquals(1, openMap)
    }

    @Test
    fun theMenuWithoutANameStillHasATitle() {
        content { FollowMenuDialog(FollowInfo(5, null), {}, {}, {}) }
        composeRule.onNodeWithText(string(R.string.follow_menu_title_unnamed)).assertIsDisplayed()
    }

    @Test
    fun theReplaceDialogNamesTheRunningTrackAndAsksBeforeSwitching() {
        content { FollowReplaceDialog("Sonntagsrunde", { confirm++ }, { dismiss++ }) }
        composeRule.onNodeWithText(string(R.string.track_follow_replace_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_replace_text, "Sonntagsrunde")).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_REPLACE_CONFIRM_TEST_TAG).assertTouchTargetAtLeast56(Density(composeRule.activity))
        composeRule.onNodeWithTag(FOLLOW_REPLACE_CANCEL_TEST_TAG).assertTouchTargetAtLeast56(Density(composeRule.activity))
        composeRule.onNodeWithTag(FOLLOW_REPLACE_CANCEL_TEST_TAG).performClick()
        assertEquals(1, dismiss)
        assertEquals(0, confirm)
        composeRule.onNodeWithTag(FOLLOW_REPLACE_CONFIRM_TEST_TAG).performClick()
        assertEquals(1, confirm)
    }

    @Test
    fun theReplaceDialogWithoutANameStillReads() {
        content { FollowReplaceDialog(null, {}, {}) }
        composeRule.onNodeWithText(string(R.string.track_follow_replace_text, string(R.string.follow_menu_title_unnamed))).assertIsDisplayed()
    }

    @Test
    fun theRecordingDialogSaysSoAndClosesWithOk() {
        content { FollowRecordingDialog { dismiss++ } }
        composeRule.onNodeWithText(string(R.string.track_follow_recording_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_follow_recording_text)).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOW_RECORDING_OK_TEST_TAG).assertTouchTargetAtLeast56(Density(composeRule.activity)).performClick()
        assertEquals(1, dismiss)
    }
}
