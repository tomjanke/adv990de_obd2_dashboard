package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        every { userDisconnectCount } returns MutableStateFlow(0)
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

        assertTrue("expected onLongPress to fire on a long-press gesture", longPressed)
        assertTrue("onClick must not also fire for a long-press gesture", !clicked)
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

        assertTrue("expected onClick to fire on a plain click", clicked)
        assertTrue("onLongPress must not also fire for a plain click", !longPressed)
    }

    // ── B27: a value that does not fit shrinks instead of being clipped ────────────────────────────────

    /** Das Layout des Wert-Texts (ungemergter Baum: der Text steckt sonst im Semantik-Knoten der klickbaren Kachel). */
    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun aFiveDigitValueShrinksToFitInsteadOfBeingClipped() {
        composeRule.setContent {
            Box(Modifier.size(180.dp, 140.dp)) {
                LiveValueTile(label = "RPM", value = "11500", unit = "rpm", modifier = Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()

        assertFalse(
            "\"11500\" darf nicht abgeschnitten werden (frueher: \"1150\")",
            layoutOf("11500").didOverflowWidth,
        )
        composeRule.onNodeWithText(" rpm", useUnmergedTree = true).assertIsDisplayed()   // die Einheit bleibt sichtbar
    }

    @Test
    fun aLargeSystemFontKeepsEveryDigit() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                Box(Modifier.size(200.dp, 160.dp)) {
                    LiveValueTile(label = "RPM", value = "8500", unit = "rpm", modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()

        assertFalse("bei Schriftgroesse 1,5 muss \"8500\" noch passen", layoutOf("8500").didOverflowWidth)
        composeRule.onNodeWithText(" rpm", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aValueThatFitsKeepsTheFullSize_onlyTheTooLongOneShrinks() {
        composeRule.setContent {
            Row {
                Box(Modifier.size(180.dp, 140.dp)) {
                    LiveValueTile(label = "COOLANT", value = "87", unit = "°C", modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.size(180.dp, 140.dp)) {
                    LiveValueTile(label = "RPM", value = "11500", unit = "rpm", modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()

        val short = layoutOf("87")
        val long = layoutOf("11500")

        assertFalse(short.didOverflowWidth)
        assertTrue(
            "der kurze Wert behaelt die volle Schriftgroesse (${short.size.height} px), nur der zu lange schrumpft (${long.size.height} px)",
            short.size.height > long.size.height,
        )
    }
}
