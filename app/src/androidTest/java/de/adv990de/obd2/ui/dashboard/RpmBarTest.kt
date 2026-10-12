package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * Compose UI tests for [RpmBar] — previously without any instrumentation coverage (documented gap,
 * confirmed via grep before writing these: zero test references to either
 * `R.string.dashboard_gear_desc` or `R.string.dashboard_gear_unknown_desc` anywhere in the repo).
 *
 * The four pure decision functions `RpmBar` delegates to (`rpmBlinkPeriodMs`, `rpmSegmentColor`,
 * `rpmThresholdTempScale`, `shouldStartRpmDemoSweep`) are already exhaustively covered as JVM tests
 * in `DashboardComponentsTest` (`app/src/test/java/de/adv990de/obd2/ui/dashboard/DashboardComponentsTest.kt`).
 * This class covers only what those cannot reach: the actual Composable output — the gear digit
 * (or the neutral "N", or the "–" placeholder) and its accompanying `contentDescription`, the [OilTemperatureBarStrip]
 * `contentDescription` for each `OilBarColor` state (that composable has no visible text of its own,
 * s. its KDoc) — plus a basic "composes without crashing" smoke check across the segment `Canvas`
 * and the blink `produceState` loop's below-/above-threshold branches.
 *
 * The oil-bar's actual on-screen *color* per state (as opposed to its `contentDescription`, asserted
 * below) was verified visually instead, via this project's established temporary-`captureToImage`
 * pattern (s. MEMORY.md) — not re-added here permanently, matching this class's existing precedent of
 * not asserting pixel-level `Canvas` output (s. paragraph below).
 *
 * Deliberately NOT covered (matching this project's established ceiling elsewhere, e.g.
 * `SlotPickerSheetTest`/`LiveValueTileDeviceTest`/`SectionLabelTest`, none of which assert pixel- or
 * frame-level state): the blink toggle's actual timing/frequency. `visible` in `RpmBar` is driven by
 * a `produceState` coroutine calling `delay(blinkPeriodMs)` in a `while(true)` loop — asserting *when*
 * it flips would need either idling-resource plumbing or a real-time wait, neither of which exists
 * anywhere in this codebase (`rpmBlinkPeriodMs` itself, the pure function computing that period, is
 * already exhaustively tested on the JVM side). The segment `Canvas`'s drawn pixels are likewise not
 * asserted — no `captureToImage`/pixel-level assertion exists anywhere in this project's test suite.
 *
 * Hilt/HiltTestActivity setup mirrored from [SlotPickerSheetTest] in this same package (and
 * `de.adv990de.obd2.ui.components.LiveValueTileDeviceTest`): `@HiltAndroidTest` is required for every test class
 * in this module (Hilt validates the full app graph, incl. `ObdForegroundService`'s entry point, per
 * test class) even though `RpmBar` itself needs no Hilt object — `ObdRepository` still needs a
 * `@BindValue` mock because its real constructor needs Bluetooth/connection dependencies unavailable
 * in tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RpmBarTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    /** The undetermined-gear placeholder digit box shows — an EN DASH (U+2013), not a hyphen. */
    private val undeterminedGearGlyph = "–"

    // --- QS C (UID-13/SCR-18): the segment bar was a bare Canvas — invisible to TalkBack ---

    @Test
    fun rpmBar_isDescribedWithTheRpmRoundedTo100() {
        composeRule.setContent { RpmBar(rpm = 2840f, threshold = 7000, gear = 3) }
        composeRule.waitForIdle()

        // 2840 -> 2800: TalkBack must not chatter at every 20-Hz sweep step.
        val expected = composeRule.activity.getString(R.string.dashboard_rpm_bar_desc, 2800)
        composeRule.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun rpmBar_saysNoValue_whenNotLive_insteadOfADemoSweepRpm() {
        composeRule.setContent { RpmBar(rpm = 4500f, threshold = 7000, gear = null, isLive = false) }
        composeRule.waitForIdle()

        val expected = composeRule.activity.getString(R.string.dashboard_rpm_bar_unavailable_desc)
        composeRule.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun determinedGear_showsDigit_andMatchingContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_desc, 3)
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun undeterminedGear_showsDash_andUnknownContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = null)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun notLive_hidesGear_evenWhenADeterminedGearIsPassedIn() {
        // isLive=false forces displayGear=null regardless of `gear` (RpmBar: "keine Zahl, die eine
        // Drehzahl vortaeuscht, die es nicht gibt" applies to the neighbouring gear digit too, not
        // only to the segment bar's own alert colour/blink).
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = 5, isLive = false)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun neutral_showsN_andNeutralContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 1100f, threshold = 7000, gear = null, isNeutral = true)
        }
        composeRule.waitForIdle()

        val symbol = composeRule.activity.getString(R.string.dashboard_gear_neutral_symbol)
        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_neutral_desc)
        composeRule.onNodeWithText(symbol).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun neutral_winsOverAGearThatIsStillPassedIn() {
        composeRule.setContent {
            RpmBar(rpm = 1100f, threshold = 7000, gear = 2, isNeutral = true)
        }
        composeRule.waitForIdle()

        val symbol = composeRule.activity.getString(R.string.dashboard_gear_neutral_symbol)
        composeRule.onNodeWithText(symbol).assertIsDisplayed()
        composeRule.onNodeWithText("2").assertDoesNotExist()
    }

    @Test
    fun notLive_hidesNeutral_evenWhenNeutralIsPassedIn() {
        // Ohne Verbindung gibt es keinen Stillstand zu melden — dieselbe Regel wie fuer die Gangziffer.
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = null, isNeutral = true, isLive = false)
        }
        composeRule.waitForIdle()

        val neutralDescription = composeRule.activity.getString(R.string.dashboard_gear_neutral_desc)
        val unknownDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(unknownDescription).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(neutralDescription).assertDoesNotExist()
    }

    @Test
    fun oilBarRed_showsColdContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Red)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_red_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarYellow_showsWarmingUpContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Yellow)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_yellow_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarGreen_showsOperatingTemperatureContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Green)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_green_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarNullColor_showsUnknownContentDescription() {
        // Live, but no color yet — e.g. before the first coolant reading has arrived.
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = null)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_unknown_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun notLive_showsUnknownOilBarContentDescription_evenWhenAColorIsPassedIn() {
        // isLive=false forces the grey/unknown oil bar regardless of a stale oilBarColor — same
        // rationale as notLive_hidesGear_evenWhenADeterminedGearIsPassedIn for the gear digit.
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = 5, oilBarColor = OilBarColor.Red, isLive = false)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_unknown_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashing_belowBlinkThreshold() {
        // rpm < threshold: rpmBlinkPeriodMs() == 0, the produceState loop takes its idle-poll branch.
        composeRule.setContent {
            RpmBar(rpm = 1500f, threshold = 7000, gear = 2)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashing_aboveBlinkThreshold() {
        // rpm > threshold with isLive=true: rpmBlinkPeriodMs() > 0, the produceState loop takes its
        // delay-and-toggle branch. Only that composition succeeds is asserted — see class KDoc for
        // why the toggle's timing itself is out of scope.
        composeRule.setContent {
            RpmBar(rpm = 8000f, threshold = 7000, gear = 6)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("6").assertIsDisplayed()
    }
}
