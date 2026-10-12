package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [SwipeToDeleteRow]: the swipe is only an ADDITION to a visible delete button
 * (CLAUDE.md design principle 4), so it must be hard to trigger by accident — one direction only,
 * a deliberate distance, and off for rows that must not be deleted (the running recording).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SwipeToDeleteRowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Required boilerplate of every @HiltAndroidTest class here, see [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private var deleted = 0

    private fun launch(enabled: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                SwipeToDeleteRow(onDelete = { deleted++ }, enabled = enabled) {
                    Text("Row content", Modifier.fillMaxWidth().height(80.dp).testTag(ROW_TAG))
                }
            }
        }
    }

    @Test
    fun showsItsContent() {
        launch()
        composeRule.onNodeWithTag(ROW_TAG).assertIsDisplayed()
    }

    @Test
    fun aLeftSwipeDeletesOnce() {
        launch()

        composeRule.onNodeWithTag(ROW_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(1, deleted)
    }

    @Test
    fun aRightSwipeDoesNothing() {
        launch()

        composeRule.onNodeWithTag(ROW_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(0, deleted)
    }

    @Test
    fun aShortSlowSwipeBelowTheThresholdDoesNothing() {
        launch()

        // 15 % of the width, very slow: neither the distance nor the speed of a deliberate swipe. Material3 also
        // completes a dismiss when the finger is released faster than 125 dp/s, whatever the distance — so the
        // duration must make the speed small in EVERY orientation (the width is 1008 px upright, 2244 px sideways;
        // 800 ms was slow enough upright and a 160 dp/s fling sideways). 3 s keeps it far below in both.
        composeRule.onNodeWithTag(ROW_TAG).performTouchInput {
            swipeLeft(startX = width * 0.9f, endX = width * 0.75f, durationMillis = 3_000)
        }
        composeRule.waitForIdle()

        assertEquals(0, deleted)
    }

    @Test
    fun aDisabledRowIgnoresTheSwipe() {
        launch(enabled = false)

        composeRule.onNodeWithTag(ROW_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(0, deleted)
    }

    private companion object {
        const val ROW_TAG = "row"
    }
}
