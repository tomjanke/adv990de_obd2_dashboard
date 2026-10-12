package de.adv990de.obd2.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
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
 * Compose UI tests for [SectionLabel] — previously untested (documented coverage gap).
 *
 * Unlike [LiveValueTile]/[StatusDot], `SectionLabel`'s `color` parameter has no branching
 * selection logic to extract into a pure function (its default is a single
 * `MaterialTheme.colorScheme.onSurfaceVariant` expression, not a `when`/mapping), so there is
 * nothing analogous to `liveValueTileAccentColor`/`statusDotColor` to pure-function-test here.
 * There is also no precedent in this project for asserting an actually-rendered text *color* from
 * an androidTest (no `captureToImage`/pixel-level assertion anywhere in this codebase — checked).
 * Matching this project's own established ceiling for what gets asserted from Compose UI tests,
 * the color- and textAlign-path tests below instead exercise the call path (composition completes,
 * the expected text is displayed) without crashing, rather than asserting the resolved color/
 * alignment itself.
 *
 * A bare `createComposeRule()` (no Hilt) was tried first, on the theory that `SectionLabel` has
 * zero external/Hilt dependencies and the other tests' Hilt requirement might just be untested
 * assumption. Empirically it is not: run against this project's `HiltTestRunner`
 * (which swaps in `HiltTestApplication`), the bare rule failed every test with
 * `IllegalStateException: No compose hierarchies found in the app` — no crash, the generic
 * `ui-test-manifest` test activity simply never got a composition attached under this project's
 * test Application. So this class uses the same `@HiltAndroidTest` +
 * `createAndroidComposeRule<HiltTestActivity>()` pattern as every other Compose UI test in this
 * module (e.g. `LiveValueTileDeviceTest`), confirmed necessary rather than assumed — including the
 * `@BindValue` mock `ObdRepository` below: without it, `hiltJavaCompileDebugAndroidTest` fails to
 * even compile (`Dagger/MissingBinding` on `ConnectionFactory`/`PidParser`/`DtcParser`, reached
 * through `ObdForegroundService`'s Hilt entry point), regardless of `SectionLabel` needing none of
 * it itself — Hilt validates the full app graph per test class, not just what the test touches.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SectionLabelTest {

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

    @Test
    fun rendersGivenText_upperCased() {
        composeRule.setContent {
            MaterialTheme {
                SectionLabel(text = "Vehicle")
            }
        }

        composeRule.onNodeWithText("VEHICLE").assertTextEquals("VEHICLE")
    }

    @Test
    fun defaultColor_noOverride_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                SectionLabel(text = "Connection")
            }
        }

        composeRule.onNodeWithText("CONNECTION").assertTextEquals("CONNECTION")
    }

    @Test
    fun explicitColorOverride_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                SectionLabel(text = "About", color = Color.Red)
            }
        }

        composeRule.onNodeWithText("ABOUT").assertTextEquals("ABOUT")
    }

    @Test
    fun nonDefaultTextAlign_rendersWithoutCrash() {
        composeRule.setContent {
            MaterialTheme {
                SectionLabel(text = "Status", textAlign = TextAlign.Center)
            }
        }

        composeRule.onNodeWithText("STATUS").assertTextEquals("STATUS")
    }
}
