package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [ThemePickerItem] — previously untested anywhere in the repo (only the
 * extracted [isColorTheme] pure predicate had JVM coverage, via `SettingsComponentsTest`).
 *
 * Covers:
 * - Each of the 4 color-scheme `SegmentedButton`s' onClick wiring to `onThemeChange`
 *   (Default -> [AppPreferences.THEME_SYSTEM], Blue/Amber/Green -> their own constant).
 * - The color-scheme row's 'selected' highlighting, keyed off the current `theme` value —
 *   including that `THEME_LIGHT`/`THEME_DARK` (brightness variants of the Default scheme, not
 *   color schemes) still highlight the "Default" button, exactly like `THEME_SYSTEM` does.
 * - The brightness row's `AnimatedVisibility` show/hide, driven by [isColorTheme]: visible for the
 *   Default scheme (`system`/`light`/`dark`), hidden for a cockpit scheme (`blue`/`amber`/`green`).
 * - Each of the 3 brightness `SegmentedButton`s' onClick wiring to `onThemeChange`.
 *
 * `SegmentedButton` merges its icon+label composable's semantics upward onto one selectable node
 * (the same "clickable/selectable merges descendants" convention documented in
 * [de.adv990de.obd2.ui.components.BottomNavTest] for `NavigationBarItem` and
 * [de.adv990de.obd2.ui.components.LiveValueTileTest] for `combinedClickable`), so
 * `onNodeWithText(label)` resolves to the button's own node and carries both its `selected` state
 * and its click action — `assertIsSelected()`/`assertIsNotSelected()`/`performClick()` all work
 * directly against it, exactly like [de.adv990de.obd2.ui.components.BottomNavTest] already established
 * for `NavigationBarItem`.
 *
 * Labels are resolved via `composeRule.activity.getString(...)` rather than hardcoded strings, to
 * stay correct regardless of the device locale (`values-de` supplies different button text) — same
 * convention as [de.adv990de.obd2.ui.components.BottomNavTest].
 *
 * `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>` + the `@BindValue`
 * `ObdRepository` mock are required boilerplate per this project's established convention
 * ([GearRatioFieldUiTest], [de.adv990de.obd2.ui.components.LiveValueTileTest],
 * [de.adv990de.obd2.ui.components.BottomNavTest]): Hilt validates the whole app graph per test class
 * (reached through `ObdForegroundService`'s entry point) even though `ThemePickerItem` itself
 * resolves nothing via Hilt.
 *
 * Not covered here: the actual pixel-level appearance of each color swatch `Box` (already
 * implicitly exercised — swatches render unconditionally — but there is no established convention
 * in this repo for asserting drawn colors from Compose UI tests), and `AppPreferences`/DataStore
 * persistence of the chosen theme, which belongs to whichever ViewModel owns the real
 * `onThemeChange` wiring (out of scope for this pure-composable test; `ThemePickerItem` itself only
 * forwards the callback).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ThemePickerItemTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Required for every @HiltAndroidTest class in this module, not just this test: Hilt
     * validates the full app graph (incl. ObdForegroundService's entry point) per test class, and
     * ObdRepository's real constructor needs Bluetooth/connection dependencies not available in
     * tests. See the identical binding + rationale in GearRatioFieldUiTest/LiveValueTileTest.
     */
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

    private fun label(resId: Int): String = composeRule.activity.getString(resId)

    private fun launch(theme: String, onThemeChange: (String) -> Unit) {
        composeRule.setContent {
            // ThemePickerItem emits two sibling top-level composables (the color-scheme Column
            // and the brightness row's AnimatedVisibility/Column). Its only real call site
            // (SettingsScreen.kt) invokes it inside a LazyColumn `item { }` block, which stacks
            // multiple root children of an item vertically like a Column. Without that wrapper,
            // Compose's raw root instead stacks siblings on top of each other at (0,0) like a Box
            // -- verified via onRoot().printToLog(): the brightness row's bounds fully overlapped
            // the color-scheme row's -- which made performClick() (a real touch at the node's
            // center coordinate) land on the wrong, visually-overlapping button. Wrapping in a
            // Column here reproduces the real call site's layout so clicks land correctly.
            Column {
                ThemePickerItem(theme = theme, onThemeChange = onThemeChange)
            }
        }
        composeRule.waitForIdle()
    }

    // --- color-scheme SegmentedButtons: onClick -> onThemeChange wiring ---

    @Test
    fun clickingDefaultButton_invokesOnThemeChange_withSystem() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_BLUE) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_default)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_SYSTEM), got $newTheme",
            newTheme == AppPreferences.THEME_SYSTEM,
        )
    }

    @Test
    fun clickingBlueButton_invokesOnThemeChange_withBlue() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_SYSTEM) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_blue)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_BLUE), got $newTheme",
            newTheme == AppPreferences.THEME_BLUE,
        )
    }

    @Test
    fun clickingAmberButton_invokesOnThemeChange_withAmber() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_SYSTEM) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_amber)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_AMBER), got $newTheme",
            newTheme == AppPreferences.THEME_AMBER,
        )
    }

    @Test
    fun clickingGreenButton_invokesOnThemeChange_withGreen() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_SYSTEM) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_green)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_GREEN), got $newTheme",
            newTheme == AppPreferences.THEME_GREEN,
        )
    }

    // --- color-scheme SegmentedButtons: 'selected' highlighting ---

    @Test
    fun currentTheme_blue_showsBlueButtonSelected_andOthersNotSelected() {
        launch(theme = AppPreferences.THEME_BLUE) {}

        composeRule.onNodeWithText(label(R.string.settings_theme_blue)).assertIsSelected()
        composeRule.onNodeWithText(label(R.string.settings_theme_default)).assertIsNotSelected()
        composeRule.onNodeWithText(label(R.string.settings_theme_amber)).assertIsNotSelected()
        composeRule.onNodeWithText(label(R.string.settings_theme_green)).assertIsNotSelected()
    }

    @Test
    fun currentTheme_system_showsDefaultButtonSelected_andColorButtonsNotSelected() {
        launch(theme = AppPreferences.THEME_SYSTEM) {}

        composeRule.onNodeWithText(label(R.string.settings_theme_default)).assertIsSelected()
        composeRule.onNodeWithText(label(R.string.settings_theme_blue)).assertIsNotSelected()
    }

    @Test
    fun currentTheme_light_stillShowsDefaultButtonSelected() {
        // THEME_LIGHT/THEME_DARK are brightness variants of the Default scheme, not color
        // schemes -> isColorTheme() is false, so the "Default" button (not a color button) shows
        // selected, same as for THEME_SYSTEM.
        launch(theme = AppPreferences.THEME_LIGHT) {}

        composeRule.onNodeWithText(label(R.string.settings_theme_default)).assertIsSelected()
    }

    // --- brightness row: AnimatedVisibility show/hide ---

    @Test
    fun defaultSchemeSelected_showsBrightnessRow() {
        launch(theme = AppPreferences.THEME_SYSTEM) {}

        composeRule.onNodeWithText(label(R.string.settings_brightness)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.settings_theme_light)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.settings_theme_dark)).assertIsDisplayed()
    }

    @Test
    fun colorSchemeSelected_hidesBrightnessRow() {
        launch(theme = AppPreferences.THEME_BLUE) {}

        composeRule.onNodeWithText(label(R.string.settings_brightness)).assertDoesNotExist()
        composeRule.onNodeWithText(label(R.string.settings_theme_light)).assertDoesNotExist()
        composeRule.onNodeWithText(label(R.string.settings_theme_dark)).assertDoesNotExist()
    }

    // --- brightness SegmentedButtons: onClick -> onThemeChange wiring ---

    @Test
    fun clickingLightButton_invokesOnThemeChange_withLight() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_SYSTEM) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_light)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_LIGHT), got $newTheme",
            newTheme == AppPreferences.THEME_LIGHT,
        )
    }

    @Test
    fun clickingDarkButton_invokesOnThemeChange_withDark() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_SYSTEM) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_dark)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_DARK), got $newTheme",
            newTheme == AppPreferences.THEME_DARK,
        )
    }

    @Test
    fun clickingSystemBrightnessButton_invokesOnThemeChange_withSystem() {
        var newTheme: String? = null
        launch(theme = AppPreferences.THEME_LIGHT) { newTheme = it }

        composeRule.onNodeWithText(label(R.string.settings_theme_system)).performClick()

        assertTrue(
            "expected onThemeChange(THEME_SYSTEM), got $newTheme",
            newTheme == AppPreferences.THEME_SYSTEM,
        )
    }

    // --- glove-friendly touch height (CLAUDE.md design principle 4) ---

    @Test
    fun everySegmentOfBothRows_isAtLeast56dpHigh() {
        // THEME_SYSTEM = the Default scheme, so both the color-scheme row and the brightness row show.
        launch(theme = AppPreferences.THEME_SYSTEM) {}

        for (labelRes in listOf(
            R.string.settings_theme_default, R.string.settings_theme_blue, R.string.settings_theme_amber,
            R.string.settings_theme_green, R.string.settings_theme_system, R.string.settings_theme_light,
            R.string.settings_theme_dark,
        )) {
            composeRule.onNodeWithText(label(labelRes)).assertHeightIsAtLeast(56.dp)
        }
    }
}
