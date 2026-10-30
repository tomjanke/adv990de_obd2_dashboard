package de.adv990de.obd2.ui.dashboard

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Pixel tests of the red zone and the blinking of [RpmBar]: the start animation shows both in every connection state (`alertsEnabled`, Nutzerwunsch 2026-10-30),
 * a bar that is neither live nor part of the animation never does. The segment `Canvas` is read as image points (like `VoltageGaugeDeviceTest`); the blink
 * is stepped by hand on the test clock (`autoAdvance = false`).
 *
 * rpm 9000 / limit 7000: 45 of 50 segments are lit; above the limit the whole lit bar is [StatusColors.Critical] (`isAlert`); the blink period is
 * 500 - 420 * (9000 - 7000) / (10000 - 7000) = 220 ms.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RpmBarAlertsDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    private lateinit var scheme: ColorScheme

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private val unavailableDescription get() = composeRule.activity.getString(R.string.dashboard_rpm_bar_unavailable_desc)

    private fun show(isLive: Boolean, alertsEnabled: Boolean?) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            scheme = MaterialTheme.colorScheme
            if (alertsEnabled == null) {
                RpmBar(rpm = 9000f, threshold = 7000, gear = null, isLive = isLive)
            } else {
                RpmBar(rpm = 9000f, threshold = 7000, gear = null, isLive = isLive, alertsEnabled = alertsEnabled)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** The colour at the middle of segment [index] (of 50), on the middle row of the bar. */
    private fun segmentColor(index: Int, description: String): Color {
        val map = composeRule.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        val x = ((index + 0.5f) / 50f * map.width).toInt()
        return map[x, map.height / 2]
    }

    private fun assertNear(expected: Color, actual: Color, what: String) {
        val delta = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green), abs(expected.blue - actual.blue))
        assertTrue("$what: expected $expected, was $actual", delta < 0.03f)
    }

    @Test
    fun theStartAnimationWithoutAConnectionPaintsTheLitBarRed() {
        show(isLive = false, alertsEnabled = true)

        assertNear(StatusColors.Critical, segmentColor(40, unavailableDescription), "lit segment above the limit")
        // Über der Grenze färbt sich die ganze leuchtende Anzeige rot (isAlert), nicht nur die Segmente oberhalb.
        assertNear(StatusColors.Critical, segmentColor(10, unavailableDescription), "lit segment below the limit")
        assertNear(scheme.surfaceVariant, segmentColor(48, unavailableDescription), "unlit segment")
    }

    @Test
    fun theStartAnimationWithoutAConnectionBlinksOffAfterTheBlinkPeriod() {
        show(isLive = false, alertsEnabled = true)
        assertNear(StatusColors.Critical, segmentColor(40, unavailableDescription), "lit before the blink")

        composeRule.mainClock.advanceTimeBy(300)

        assertNear(scheme.surfaceVariant, segmentColor(40, unavailableDescription), "lit segment during the dark phase of the blink")
    }

    @Test
    fun aBarThatIsNeitherLiveNorPartOfTheAnimationNeverTurnsRedOrBlinks() {
        show(isLive = false, alertsEnabled = null)
        assertNear(scheme.primary, segmentColor(40, unavailableDescription), "lit segment, not live")

        composeRule.mainClock.advanceTimeBy(300)

        assertNear(scheme.primary, segmentColor(40, unavailableDescription), "lit segment, not live, after the blink period")
    }

    @Test
    fun theTalkBackDescriptionStaysUnavailableDuringAnOfflineStartAnimation() {
        // Die Animation zeigt Rot und Blinken, sagt aber keine Drehzahl an, die es nicht gibt.
        show(isLive = false, alertsEnabled = true)

        composeRule.onNodeWithContentDescription(unavailableDescription).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.dashboard_rpm_bar_desc, 9000)).assertDoesNotExist()
    }
}
