package de.adv990de.obd2.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Pixel tests of the rpm digits in [RpmBar] (Nutzerwunsch 2026-11-01, Variante „Fenster“, s. `RpmDigits.kt`): the window sits flush left in the middle of the segment
 * field, the digits stand on it. The segment `Canvas` is read as image points (like `RpmBarAlertsDeviceTest`).
 *
 * Reading the window: it is drawn in `background`, so its right edge is the first pixel that differs from `background` on a row inside the window but above the digits
 * (the padding band); its rows are those at the left edge (x = 1 px) that are still `background`. Ink = a pixel inside the window that is closer to the text colour than
 * to the window colour.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RpmDigitsDeviceTest {

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

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun show(
        rpm: Float,
        isLive: Boolean = true,
        digitsEnabled: Boolean? = null,
        threshold: Int = 7000,
        fontScale: Float? = null,
        colorScheme: ColorScheme = blueColorScheme,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme(colorScheme = colorScheme, typography = AppTypography) {
                scheme = MaterialTheme.colorScheme
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    if (digitsEnabled == null) {
                        RpmBar(rpm = rpm, threshold = threshold, gear = null, isLive = isLive)
                    } else {
                        RpmBar(rpm = rpm, threshold = threshold, gear = null, isLive = isLive, digitsEnabled = digitsEnabled)
                    }
                }
                if (fontScale == null) {
                    content()
                } else {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) { content() }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun description(rpm: Float, isLive: Boolean) =
        if (isLive) composeRule.activity.getString(R.string.dashboard_rpm_bar_desc, rpmForSpeech(rpm))
        else composeRule.activity.getString(R.string.dashboard_rpm_bar_unavailable_desc)

    private fun barImage(rpm: Float, isLive: Boolean = true): Bitmap =
        composeRule.onNodeWithContentDescription(description(rpm, isLive)).captureToImage().asAndroidBitmap()

    private fun color(bitmap: Bitmap, x: Int, y: Int) = Color(bitmap.getPixel(x, y))

    /** The colour at the middle of segment [index] (of 50), on the middle row of the bar — the middle of the bar itself can lie in a gap. */
    private fun segmentColor(bitmap: Bitmap, index: Int) = color(bitmap, ((index + 0.5f) / 50f * bitmap.width).toInt(), bitmap.height / 2)

    private fun near(a: Color, b: Color, tolerance: Float = 0.03f) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) < tolerance

    private fun distance(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** The window as measured on the image: its rows (at the left edge) and its right edge (on a row of the padding band above the digits). */
    private data class Plate(val top: Int, val bottom: Int, val right: Int)

    private fun plateOf(bitmap: Bitmap): Plate? {
        val background = scheme.background
        val rows = (0 until bitmap.height).filter { near(color(bitmap, 1, it), background, 0.02f) }
        if (rows.isEmpty()) return null
        val top = rows.first()
        val bottom = rows.last()
        // A row inside the window but above the digits: 2 px below the top edge of the window is padding (the padding is 5 dp = 11 px at 2.25).
        val probeY = top + 2
        var right = 1
        while (right < bitmap.width && near(color(bitmap, right, probeY), background, 0.02f)) right++
        return Plate(top, bottom, right)
    }

    private class Ink(val count: Int, val left: Int, val right: Int, val top: Int, val bottom: Int)

    private fun inkOf(bitmap: Bitmap, plate: Plate): Ink {
        var count = 0; var left = Int.MAX_VALUE; var right = -1; var top = Int.MAX_VALUE; var bottom = -1
        for (y in plate.top..plate.bottom) {
            for (x in 0 until plate.right) {
                val p = color(bitmap, x, y)
                if (distance(p, scheme.onSurface) < distance(p, scheme.background)) {
                    count++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return Ink(count, left, right, top, bottom)
    }

    private fun widthDp(plate: Plate) = plate.right / density

    // --- the window and the bar around it ---

    @Test
    fun theWindowSitsFlushLeftInTheMiddle_andTheBarStaysVisibleAboveAndBelow() {
        show(rpm = 4500f)
        val bar = barImage(4500f)
        val plate = plateOf(bar)!!

        assertTrue("the window is smaller than the bar: rows ${plate.top}..${plate.bottom} of ${bar.height}", plate.top > 0 && plate.bottom < bar.height - 1)
        // Above and below the window the first segment is still lit (lit segments are `primary`).
        assertTrue("strip above the window: ${color(bar, 2, 1)}", near(scheme.primary, color(bar, 2, 1)))
        assertTrue("strip below the window: ${color(bar, 2, bar.height - 2)}", near(scheme.primary, color(bar, 2, bar.height - 2)))
        // Right of the window the lit segments go on in the middle of the bar.
        assertTrue("lit segment right of the window: ${color(bar, plate.right + 4, bar.height / 2)}", near(scheme.primary, color(bar, plate.right + 4, bar.height / 2)))
    }

    @Test
    fun theWindowIsAsHighAsTheDigitsPlusFiveDpAboveAndBelow() {
        // 18.1 dp digits + 2 x 5 dp = 28.1 dp, in the 40 dp high segment field (6 dp of the bar stay visible above and below).
        show(rpm = 4500f)
        val bar = barImage(4500f)
        val plate = plateOf(bar)!!
        assertEquals("window height ${(plate.bottom - plate.top + 1) / density} dp", 28.1f, (plate.bottom - plate.top + 1) / density, 1.2f)
        assertEquals("bar height ${bar.height / density} dp", 40f, bar.height / density, 0.6f)
    }

    @Test
    fun theWindowIsAsWideAsFourDigitsPlusPadding_whatEverTheNumber() {
        // 8 dp + 4 digits of 0.6 em at 25.96 sp (titleLarge x 1.18) + 8 dp = 78.3 dp.
        show(rpm = 1800f)
        val fourDigits = widthDp(plateOf(barImage(1800f))!!)
        assertTrue("window for 1800: $fourDigits dp", fourDigits in 76.5f..80f)
    }

    @Test
    fun aShorterNumberKeepsTheWindowAsWideAsFourDigits() {
        show(rpm = 700f)
        val threeDigits = widthDp(plateOf(barImage(700f))!!)
        assertEquals("window for 700 (3 digits) like 1800 (4 digits)", 78.3f, threeDigits, 1.5f)
    }

    @Test
    fun aLongerNumberWidensTheWindowByOneDigit() {
        show(rpm = 12000f)
        val fiveDigits = widthDp(plateOf(barImage(12000f))!!)
        assertEquals("window for 12000 (5 digits): one more digit of 15.6 dp", 78.3f + 15.6f, fiveDigits, 1.5f)
    }

    // --- the digits ---

    @Test
    fun theDigitsStandLeftAligned_inTheTextColour() {
        show(rpm = 4500f)
        val bar = barImage(4500f)
        val plate = plateOf(bar)!!
        val ink = inkOf(bar, plate)

        assertTrue("digits drawn: ${ink.count} ink points", ink.count > 300)
        // 8 dp from the left edge, plus the side bearing of the first digit.
        assertTrue("left edge of the digits ${ink.left} px (8 dp = ${8 * density} px)", ink.left >= (8 * density - 2f) && ink.left <= (8 * density + 5 * density))
        // The last digit ends one padding before the window does.
        assertTrue("right edge of the digits ${ink.right} px, window ${plate.right} px", ink.right <= plate.right - (8 * density - 3f))
    }

    @Test
    fun theDigitsAreCentredVerticallyInTheWindow() {
        show(rpm = 1800f)
        val bar = barImage(1800f)
        val plate = plateOf(bar)!!
        val ink = inkOf(bar, plate)

        val inkMiddle = (ink.top + ink.bottom) / 2f
        val windowMiddle = (plate.top + plate.bottom) / 2f
        assertEquals("middle of the digits $inkMiddle px, of the window $windowMiddle px", windowMiddle, inkMiddle, 1.5f * density)
        assertEquals("middle of the window ${windowMiddle} px, of the bar ${bar.height / 2f} px", bar.height / 2f, windowMiddle, 1.5f)
    }

    @Test
    fun theDigitsAreAsHighAsTheTitleLargeNumeralsAreTall() {
        // 0.698 em of 25.96 sp = 18.1 dp; a size that is not the one in use would show here.
        show(rpm = 1800f)
        val bar = barImage(1800f)
        val ink = inkOf(bar, plateOf(bar)!!)
        val heightDp = (ink.bottom - ink.top + 1) / density
        assertEquals("height of the digits $heightDp dp", 18.1f, heightDp, 1.5f)
    }

    @Test
    fun anEngineAtRestShowsZero() {
        show(rpm = 0f)
        val bar = barImage(0f)
        val plate = plateOf(bar)!!
        val ink = inkOf(bar, plate)

        assertTrue("a 0 is drawn: ${ink.count} ink points", ink.count > 200)
        // One digit only (0.6 em = 15.6 dp wide): the ink is narrower than two digits and ends well before the right padding of the four-digit window.
        assertTrue("one digit only: ink ${(ink.right - ink.left + 1) / density} dp wide", (ink.right - ink.left + 1) / density < 16f)
        assertTrue("ink to ${ink.right} px of ${plate.right} px", ink.right < plate.right - 30 * density)
        assertEquals("the window keeps its width", 78.3f, widthDp(plate), 1.5f)
    }

    // --- neither window nor digits ---

    @Test
    fun withoutAConnectionThereIsNoWindowAndNoDigits() {
        show(rpm = 4500f, isLive = false)
        val bar = barImage(4500f, isLive = false)

        assertEquals("no window at the left edge of the middle row", true, near(scheme.primary, color(bar, 1, bar.height / 2)))
        val ink = (0 until bar.height).sumOf { y -> (0 until bar.width).count { x -> near(color(bar, x, y), scheme.onSurface, 0.1f) } }
        assertEquals("no digit-coloured pixels in the bar", 0, ink)
    }

    @Test
    fun whileTheStartAnimationRunsThereAreNoDigitsEvenWithALiveConnection() {
        // DashboardScreen: rpmDigitsEnabled(isLive = true, demoRpm = 5000f) = false.
        show(rpm = 4500f, isLive = true, digitsEnabled = false)
        val bar = barImage(4500f)

        assertTrue("no window: ${color(bar, 1, bar.height / 2)}", near(scheme.primary, color(bar, 1, bar.height / 2)))
    }

    // --- robust against the system font size and the blinking ---

    private fun assertFixedAtFontScale(scale: Float) {
        show(rpm = 1800f, fontScale = scale)
        val bar = barImage(1800f)
        val plate = plateOf(bar)!!
        val ink = inkOf(bar, plate)
        // The same absolute sizes as at font scale 1.0 (see the tests above): the window has fixed measures in the 40 dp high segment field.
        assertEquals("window width at font scale $scale", 78.3f, widthDp(plate), 1.5f)
        assertEquals("digit height at font scale $scale", 18.1f, (ink.bottom - ink.top + 1) / density, 1.5f)
    }

    @Test
    fun theWindowAndTheDigitsDoNotGrowWithTheSystemFontSize_1_3() = assertFixedAtFontScale(1.3f)

    @Test
    fun theWindowAndTheDigitsDoNotGrowWithTheSystemFontSize_2_0() = assertFixedAtFontScale(2.0f)

    @Test
    fun theWindowAndTheDigitsStayWhileTheBarBlinksOff() {
        show(rpm = 9000f)
        val before = barImage(9000f)
        val plateBefore = plateOf(before)!!
        val inkBefore = inkOf(before, plateBefore)
        // rpm 9000 / limit 7000: 45 of 50 segments lit, all red (alert).
        assertTrue("lit before the blink: ${segmentColor(before, 25)}", !near(scheme.surfaceVariant, segmentColor(before, 25)))

        composeRule.mainClock.advanceTimeBy(300) // blink period at 9000 rpm / limit 7000: 220 ms

        val after = barImage(9000f)
        assertTrue("the bar is dark in the off phase: ${segmentColor(after, 25)}", near(scheme.surfaceVariant, segmentColor(after, 25)))
        val plateAfter = plateOf(after)!!
        assertEquals("the window stays", plateBefore.right.toFloat(), plateAfter.right.toFloat(), 1.5f)
        assertEquals("the digits stay", inkBefore.count.toFloat(), inkOf(after, plateAfter).count.toFloat(), inkBefore.count * 0.05f)
    }

    // --- every colour scheme ---

    @Test
    fun inTheLightSchemeTheDigitsAreDarkOnALightWindow() {
        show(rpm = 4500f, colorScheme = lightColorScheme())
        val bar = barImage(4500f)
        val plate = plateOf(bar)!!
        val ink = inkOf(bar, plate)

        assertTrue("window in the colour of the background: ${color(bar, 1, bar.height / 2)}", near(scheme.background, color(bar, 1, bar.height / 2), 0.02f))
        assertTrue("digits drawn in the light scheme: ${ink.count} ink points", ink.count > 300)
    }
}
