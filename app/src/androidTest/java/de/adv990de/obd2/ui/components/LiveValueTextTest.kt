package de.adv990de.obd2.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Compose UI tests for [LiveValueText] — previously untested (no JVM or androidTest file existed
 * for it anywhere in the repo before this class; documented coverage gap), despite being the
 * canonical live-value text primitive used throughout the dashboard.
 *
 * Covers the one real branch in the composable: [LiveValueText.animated] picks between a plain
 * [androidx.compose.material3.Text] (`animated = false`, the default) and the same `Text` wrapped
 * in [androidx.compose.animation.AnimatedContent] keyed on `value`, which slides+fades between the
 * old and new value on every change (`animated = true`). Both branches pass `style`/`color`/
 * `textAlign`/`maxLines`/`softWrap`/`overflow` through to the inner `Text` identically, so the
 * passthrough-parameter tests below exercise the default (`animated = false`) path once each,
 * plus one combined check that the same parameters don't crash on the `animated = true` path.
 *
 * Matching [SectionLabelTest]'s established ceiling for this project (no `captureToImage`/
 * pixel-level assertion anywhere in this codebase — checked): `color`/`textAlign`/`overflow` have
 * no branching selection logic to extract into a pure function (unlike `liveValueTileAccentColor`/
 * `statusDotColor`), so there is nothing to unit-test there. Those tests exercise the call path
 * (composition completes, the expected text is displayed) without crashing, rather than asserting
 * the resolved color/alignment/overflow behavior itself. `maxLines`/`softWrap` are plain passthrough
 * with no logic of their own and are left untested for the same reason `SectionLabelTest` doesn't
 * test every parameter it forwards — see "Deliberately left uncovered" below.
 *
 * The `animated = true` recomposition test (`animatedTrue_valueChange_rendersNewValue`) uses a
 * [androidx.compose.runtime.MutableState] created *outside* composition and read via `.value`
 * inside it — the standard Compose-test pattern for driving a recomposition from the test body:
 * mutating the state through [dagger.hilt.android.testing.HiltAndroidRule]'s
 * `composeRule.runOnUiThread { }` (required — Compose snapshot writes must happen on the thread
 * that owns the snapshot) triggers `AnimatedContent`'s enter/exit transition, and
 * `composeRule.waitForIdle()` afterwards is confirmed necessary: it drives both the 180 ms
 * `tween(EaseOutCubic)` slide+fade transition to completion (this project's Compose test
 * synchronization treats a running animation clock as busy, per the same mechanism
 * [LiveValueTileTest]/[BottomNavTest] rely on for gesture/navigation settling) and the
 * recomposition itself to a stable state before assertions run.
 *
 * `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>` + the `@BindValue`
 * `ObdRepository` mock are the same required boilerplate as every other Compose UI test in this
 * module ([LiveValueTileTest], [SectionLabelTest], [BottomNavTest]): Hilt validates the whole app
 * graph per test class (reached through `ObdForegroundService`'s entry point) regardless of what
 * the composable under test itself resolves via Hilt — `LiveValueText` resolves nothing via Hilt
 * at all.
 *
 * ## Deliberately left uncovered
 * - `maxLines`/`softWrap`: pure passthrough, no branching/selection logic, no precedent in this
 *   project for testing every forwarded `Text` parameter (see `SectionLabelTest`'s KDoc, which
 *   draws the same line at `color`/`textAlign`).
 * - The exact animation curve/timing values (180 ms, `EaseOutCubic`, slide direction/distance) are
 *   not asserted frame-by-frame — this project has no precedent for animation-progress assertions
 *   anywhere, only before/after state checks (e.g. `StatusDotTest`'s pulsing-dot coverage is a pure
 *   `statusDotColor` unit test, not a Compose animation-frame test).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiveValueTextTest {

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

    // --- value rendering, both animation modes ---

    @Test
    fun animatedFalse_rendersGivenValue() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "87", animated = false)
            }
        }

        composeRule.onNodeWithText("87").assertIsDisplayed()
    }

    @Test
    fun animatedTrue_rendersGivenValue() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "87", animated = true)
            }
        }

        composeRule.onNodeWithText("87").assertIsDisplayed()
    }

    @Test
    fun animatedFalse_isDisplayedWithoutAnyTransition() {
        // No AnimatedContent involved on this path: the value must already be on screen as soon
        // as composition settles, with no separate "wait for the transition to finish" step.
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "12.7", animated = false)
            }
        }

        composeRule.onNodeWithText("12.7").assertIsDisplayed()
    }

    // --- animated=true recomposition on value change ---

    @Test
    fun animatedTrue_valueChange_rendersNewValue() {
        val state = mutableStateOf("100")
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = state.value, animated = true)
            }
        }
        composeRule.onNodeWithText("100").assertIsDisplayed()

        composeRule.runOnUiThread { state.value = "200" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("200").assertIsDisplayed()
        composeRule.onNodeWithText("100").assertDoesNotExist()
    }

    // --- passthrough parameters ---

    @Test
    fun explicitColorOverride_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "42", color = Color.Red)
            }
        }

        composeRule.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun nonDefaultTextAlign_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "42", textAlign = TextAlign.Center)
            }
        }

        composeRule.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun explicitOverflow_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(value = "42", overflow = TextOverflow.Ellipsis)
            }
        }

        composeRule.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun animatedTrue_withPassthroughParams_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                LiveValueText(
                    value = "42",
                    animated = true,
                    color = Color.Red,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        composeRule.onNodeWithText("42").assertIsDisplayed()
    }
}
