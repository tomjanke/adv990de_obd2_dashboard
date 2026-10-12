package de.adv990de.obd2.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import io.mockk.every
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [RpmThresholdSection], extracted (2026-09-23) out of the former
 * "Dashboard konfigurieren" page (now part of `SettingsScreen`'s "Dashboard & Anzeige" group) so this
 * ViewModel-less local-state buffer can be driven directly, the
 * same way [de.adv990de.obd2.ui.settings.GearRatioFieldUiTest] drives `GearRatioField` (see that file
 * and `GearRatioField`'s KDoc in `GearSettingsScreen.kt` for the direct precedent this mirrors).
 *
 * [DisplaySettingsViewModel] wires `rpmBlinkThreshold` / `rpmDynamicThreshold` from StateFlows
 * built with `stateIn(viewModelScope, WhileSubscribed(5_000), DEFAULT)`: `collectAsState()` reads
 * the DEFAULT (0 / false) synchronously at first composition, while the real, DataStore-backed
 * value only arrives asynchronously afterwards. These tests drive `rpmBlinkThreshold` directly
 * (bypassing AppPreferences/DataStore entirely, same rationale as GearRatioFieldUiTest) so that
 * race is deterministic instead of depending on real DataStore I/O timing.
 *
 * Unlike `GearRatioField` (unkeyed `remember` + `LaunchedEffect(value)`, guarded by a
 * `hasBeenEdited` flag), [RpmThresholdSection]'s local buffer uses a **keyed**
 * `remember(rpmBlinkThreshold) { mutableIntStateOf(rpmBlinkThreshold) }` with no such guard — see
 * [RpmThresholdSection]'s own KDoc for why that is safe today (single writer, discrete Slider
 * gestures) but has no protection against a differing/stale echo arriving mid-gesture were a
 * second writer ever added.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RpmThresholdSectionTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Required for every @HiltAndroidTest class in this module, not just this test: Hilt
     * validates the full app graph per test class, and ObdRepository's real constructor needs
     * Bluetooth/connection dependencies not available in tests. See the identical binding +
     * rationale in GearRatioFieldUiTest / SettingsScreenDeviceTest. [RpmThresholdSection] itself does
     * not touch ObdRepository at all.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    /** Matches the Slider's semantics node without relying on a production-only test tag. */
    private val sliderNode = SemanticsMatcher("has ProgressBarRangeInfo") {
        it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // ── thresholdLabel derivation ────────────────────────────────────────────

    @Test
    fun thresholdLabel_showsDisabled_whenThresholdIsZero() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 0,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_threshold_disabled))
            .assertIsDisplayed()
    }

    // --- QS C (UID-13/SCR-18): the slider says what it controls and what it is set to ---

    @Test
    fun slider_isDescribedByItsPurposeAndStateText_forAConcreteValue() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 3000, rpmDynamicThreshold = false,
                onThresholdChangeFinished = {}, onDynamicThresholdChange = {},
            )
        }

        composeRule.onNode(sliderNode)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.dashboard_rpm_threshold_label))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3000 rpm"))
    }

    @Test
    fun slider_stateText_saysDisabled_atZero_notABarePercentage() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 0, rpmDynamicThreshold = false,
                onThresholdChangeFinished = {}, onDynamicThresholdChange = {},
            )
        }

        composeRule.onNode(sliderNode).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                composeRule.activity.getString(R.string.dashboard_rpm_threshold_disabled),
            )
        )
    }

    @Test
    fun thresholdLabel_showsRpmValue_forConcreteValue_3000() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 3000,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule.onNodeWithText("3000 rpm").assertIsDisplayed()
    }

    @Test
    fun thresholdLabel_showsRpmValue_forConcreteValue_7500() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 7500,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule.onNodeWithText("7500 rpm").assertIsDisplayed()
    }

    // ── dynamic-threshold switch visibility ──────────────────────────────────

    @Test
    fun dynamicThresholdSwitch_isHidden_whenThresholdIsZero() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 0,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_dynamic_label))
            .assertDoesNotExist()
    }

    @Test
    fun dynamicThresholdSwitch_isShown_whenThresholdIsPositive() {
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = 3000,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_dynamic_label))
            .assertIsDisplayed()
    }

    // ── local buffer vs. upstream StateFlow race ─────────────────────────────

    @Test
    fun localThreshold_reflectsUpstreamValue_thatArrivesAfterFirstComposition() {
        var threshold by mutableIntStateOf(0) // stateIn's synchronous DEFAULT (0) seed
        composeRule.setContent {
            RpmThresholdSection(
                rpmBlinkThreshold = threshold,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_threshold_disabled))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_dynamic_label))
            .assertDoesNotExist()

        // Simulates the real AppPreferences/DataStore value arriving asynchronously after first
        // composition — the exact stateIn race GearRatioField's KDoc documents for its own field.
        // Without a re-sync this would keep showing "Disabled" forever.
        composeRule.runOnIdle { threshold = 4500 }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("4500 rpm").assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_rpm_dynamic_label))
            .assertIsDisplayed()
    }

    @Test
    fun localThreshold_isNotClobbered_whenUpstreamEchoesBackTheSameValueTheUserJustSet() {
        var threshold by mutableIntStateOf(2000)
        composeRule.setContent {
            // Mirrors the real wiring: SettingsScreen passes displayVm::setRpmBlinkThreshold as
            // onThresholdChangeFinished, whose write round-trips through AppPreferences/DataStore
            // and back out through the StateFlow, landing here as the same value the user set.
            RpmThresholdSection(
                rpmBlinkThreshold = threshold,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = { threshold = it },
                onDynamicThresholdChange = {},
            )
        }
        composeRule.onNodeWithText("2000 rpm").assertIsDisplayed()

        // Simulates the user finishing a Slider drag at 5000 rpm (a valid step: 10000/19 steps
        // => 500-rpm increments). This fires onValueChangeFinished exactly like a real drag
        // release, which round-trips through the test's onThresholdChangeFinished callback above.
        composeRule.onNode(sliderNode)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
        composeRule.waitForIdle()

        // The round-trip already pushed rpmBlinkThreshold = 5000 back in; the keyed remember()
        // recreates the local buffer from that echo, but since it's the SAME value the user just
        // set, this must not revert to the pre-edit value (2000) or show anything else.
        composeRule.onNodeWithText("5000 rpm").assertIsDisplayed()
    }
}
