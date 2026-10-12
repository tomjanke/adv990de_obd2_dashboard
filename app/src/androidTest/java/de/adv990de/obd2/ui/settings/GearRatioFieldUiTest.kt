package de.adv990de.obd2.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
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
 * Compose UI tests for [GearRatioField] covering the staleness bug found in code review:
 *
 * [GearSettingsViewModel]'s StateFlows are built with
 * `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_*)`. `collectAsState()`
 * seeds synchronously from that DEFAULT_* constant at first composition, while the real
 * AppPreferences/DataStore-backed value only arrives asynchronously afterwards. Before the fix,
 * [GearRatioField]'s local text buffer (`remember { mutableStateOf(value.toString()) }`, unkeyed)
 * was seeded once from the first (default) `value` and never re-synced, so a user with a
 * non-default saved value would see the wrong number for as long as the screen stayed open — and
 * risk overwriting their real value with the stale default on any edit.
 *
 * These tests drive `value` directly (bypassing AppPreferences/DataStore entirely) so the
 * "value arrives after first composition" race is deterministic instead of depending on real
 * DataStore I/O timing.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GearRatioFieldUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Required for every @HiltAndroidTest class in this module, not just this test: Hilt
     * validates the full app graph (incl. ObdForegroundService's entry point) per test class, and
     * ObdRepository's real constructor needs Bluetooth/connection dependencies not available in
     * tests. See the identical binding + rationale in SettingsScreenDeviceTest.
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

    @Test
    fun displaysValue_thatArrivesAfterFirstComposition() {
        var value by mutableFloatStateOf(2096f) // e.g. DEFAULT_WHEEL_CIRCUMFERENCE_MM
        composeRule.setContent {
            GearRatioField(label = "Wheel circumference", value = value, onValueChange = {})
        }
        composeRule.onNodeWithText("2096.0").assertIsDisplayed()

        // Simulates the real AppPreferences value arriving asynchronously after first
        // composition — the exact race the review flagged. Without the fix, the field would
        // keep showing "2096.0" forever.
        composeRule.runOnIdle { value = 2200f }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2200.0").assertIsDisplayed()
    }

    @Test
    fun doesNotClobberInProgressEdit_whenValueRoundTripsBackFromViewModel() {
        var value by mutableFloatStateOf(2096f)
        composeRule.setContent {
            // Mirrors the real wiring: onValueChange writes straight back into the value the
            // field reads from, the same way GearSettingsViewModel's setter round-trips through
            // AppPreferences/DataStore and back out through the StateFlow.
            GearRatioField(
                label = "Wheel circumference",
                value = value,
                onValueChange = { value = it },
            )
        }

        composeRule.onNodeWithText("2096.0").performTextReplacement("2200")
        composeRule.waitForIdle()

        // The round-trip above already pushed value = 2200f back in; this must not reformat the
        // field back to the value's default toString() representation ("2200.0") mid-edit, and
        // must not have reverted to the pre-edit value.
        composeRule.onNodeWithText("2200").assertIsDisplayed()
    }

    // --- UID-23: die Eingabe ueberlebt die Neuerstellung der Activity (Drehung) ---

    @Test
    fun aTypedValue_survivesRecreation_evenAnInvalidOne() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { GearRatioField(label = "Radumfang", value = 2096f, onValueChange = {}) }
        composeRule.onNodeWithText("2096.0").performTextReplacement("2,0x")

        restoration.emulateSavedInstanceStateRestore()

        // Mit `remember` stuende hier wieder "2096.0": die halb getippte Eingabe waere verloren.
        composeRule.onNodeWithText("2,0x").assertIsDisplayed()
    }
}
