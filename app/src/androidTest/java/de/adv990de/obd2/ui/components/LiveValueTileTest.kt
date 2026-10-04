package de.adv990de.obd2.ui.components

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * Compose UI tests for [LiveValueTile]'s `combinedClickable` wiring (onClick / onLongPress).
 *
 * Documented gap (project memory): no androidTest in this repo previously simulated a long-press
 * anywhere. This class establishes that pattern — `performTouchInput { longClick() }` (part of
 * `androidx.compose.ui.test`, already a project dependency) dispatches a real long-press gesture
 * through `combinedClickable`, exactly like `performClick()` dispatches a tap. Reuse this for any
 * future long-press-driven composable (e.g. other dashboard tiles) instead of re-deriving it.
 *
 * Both gestures are targeted via `onNodeWithText(label)` rather than a dedicated test tag:
 * `combinedClickable`/`clickable` merges descendant semantics upward (the standard Compose
 * accessibility convention for any clickable composable, so a screen reader announces the tile's
 * text together with its action as one unit) — the label Text's merged semantics node therefore
 * carries the Surface's own bounds and click/long-click actions. This is the same mechanism
 * [de.adv990de.obd2.ui.dashboard.SlotPickerSheetTest] already relies on for `performClick()` in this
 * codebase, extended here to long-press. No production change (e.g. a new test tag) was needed to
 * make the tile targetable.
 *
 * The status accent-color branch (`TileStatus.Warning`/`Critical`) is covered separately as a
 * pure-function test in the JVM unit test of the same name
 * (`app/src/test/java/de/adv990de/obd2/ui/components/LiveValueTileTest.kt`) against the extracted
 * `liveValueTileAccentColor()` — no real Compose rendering needed there, matching this project's
 * `statusDotColor`-style convention (see `StatusDotTest.kt`).
 *
 * Hilt/HiltTestActivity setup mirrored from [de.adv990de.obd2.ui.settings.GearRatioFieldUiTest]:
 * `@HiltAndroidTest` is required for every test class in this module (Hilt validates the full app
 * graph, incl. ObdForegroundService's entry point, per test class) even though this composable
 * itself needs no Hilt object — `ObdRepository` still needs a `@BindValue` mock because its real
 * constructor needs Bluetooth/connection dependencies unavailable in tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiveValueTileTest {

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

    private val label = "COOLANT"

    @Test
    fun longPress_invokesOnLongPress_andNotOnClick() {
        var clicked = false
        var longPressed = false
        composeRule.setContent {
            LiveValueTile(
                label = label,
                value = "87",
                unit = "°C",
                onClick = { clicked = true },
                onLongPress = { longPressed = true },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assert(longPressed) { "expected onLongPress to fire on a long-press gesture" }
        assert(!clicked) { "onClick must not also fire for a long-press gesture" }
    }

    @Test
    fun click_invokesOnClick_andNotOnLongPress() {
        var clicked = false
        var longPressed = false
        composeRule.setContent {
            LiveValueTile(
                label = label,
                value = "87",
                unit = "°C",
                onClick = { clicked = true },
                onLongPress = { longPressed = true },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()

        assert(clicked) { "expected onClick to fire on a plain click" }
        assert(!longPressed) { "onLongPress must not also fire for a plain click" }
    }
}
