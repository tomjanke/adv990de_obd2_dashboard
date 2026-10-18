package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Compose UI tests for [ChoiceSetting] — the fixed-choice row that replaced the free-text number
 * fields of the Settings "Connection" section.
 *
 * Covers: every option is shown; exactly the selected option is marked selected; a tap reports the
 * tapped value; each segment meets the 56 dp glove-friendly touch height (CLAUDE.md design
 * principle 4); five segments still fit a narrow 320 dp phone without being squeezed below a
 * usable width; each segment carries a spoken "N seconds" description (the segment itself shows
 * just the number); the optional extra supporting line appears only when given.
 *
 * `SegmentedButton` merges its label semantics onto one selectable node, so `onNodeWithText`
 * resolves to the button itself and carries the `selected` state and the click action — the same
 * convention [ThemePickerItemTest] documents. The Hilt boilerplate (`@BindValue ObdRepository`)
 * is required per class by this project's convention, see the identical block in
 * [ThemePickerItemTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChoiceSettingTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    private val options = listOf(10, 20, 30, 60, 120)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launch(
        selected: Int = 20,
        onSelect: (Int) -> Unit = {},
        additionalSupportingText: String? = null,
        widthDp: Int? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                val content: @Composable () -> Unit = {
                    ChoiceSetting(
                        headline = "Headline",
                        supportingText = "Supporting",
                        options = options,
                        selected = selected,
                        onSelect = onSelect,
                        optionDescriptionPlural = R.plurals.settings_seconds_a11y,
                        additionalSupportingText = additionalSupportingText,
                    )
                }
                if (widthDp != null) Box(Modifier.width(widthDp.dp)) { content() } else content()
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun allOptionsAreShown() {
        launch()
        options.forEach { composeRule.onNodeWithText(it.toString()).assertIsDisplayed() }
    }

    @Test
    fun onlyTheSelectedOptionIsMarkedSelected() {
        launch(selected = 60)
        options.forEach { option ->
            val node = composeRule.onNodeWithText(option.toString())
            if (option == 60) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    @Test
    fun tappingAnOptionReportsThatValue() {
        val reported = mutableListOf<Int>()
        launch(selected = 20, onSelect = { reported += it })

        composeRule.onNodeWithText("30").performClick()
        composeRule.onNodeWithText("120").performClick()

        assertEquals(listOf(30, 120), reported)
    }

    @Test
    fun everySegmentIsAtLeast56dpHigh() {
        launch()
        options.forEach { composeRule.onNodeWithText(it.toString()).assertHeightIsAtLeast(56.dp) }
    }

    @Test
    fun fiveSegmentsFitANarrow320dpPhone() {
        launch(widthDp = 320)
        options.forEach {
            composeRule.onNodeWithText(it.toString())
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun everySegmentAnnouncesItsValueInSeconds() {
        launch()
        options.forEach { option ->
            val spoken = composeRule.activity.resources
                .getQuantityString(R.plurals.settings_seconds_a11y, option, option)
            composeRule.onNode(hasContentDescription(spoken)).assertIsDisplayed()
        }
    }

    @Test
    fun theAdditionalSupportingTextIsShownOnlyWhenGiven() {
        launch(additionalSupportingText = "extra line")
        composeRule.onNodeWithText("extra line").assertIsDisplayed()
    }

    @Test
    fun withoutAnAdditionalLineNothingExtraIsShown() {
        launch(additionalSupportingText = null)
        composeRule.onNodeWithText("extra line").assertDoesNotExist()
    }

    // ── optionLabel / optionDescription: segments that are not a whole number (the alarm thresholds: "12,5" volts) ──

    private val voltOptions = listOf(123, 125, 128)

    /** "12,3" · "12,5" · "12,8" — fixed to German so the expected texts do not depend on the device. */
    private fun volt(option: Int) = formatVoltageOption(option, Locale.GERMANY)

    private fun launchLabelled(
        selected: Int = 125,
        onSelect: (Int) -> Unit = {},
        description: ((Int) -> String)? = { "${volt(it)} Volt" },
        plural: Int? = null,
        widthDp: Int? = null,
    ) {
        composeRule.setContent {
            MaterialTheme(typography = AppTypography) {
                val content: @Composable () -> Unit = {
                    ChoiceSetting(
                        headline = "Voltage",
                        supportingText = "Volts",
                        options = voltOptions,
                        selected = selected,
                        onSelect = onSelect,
                        optionDescriptionPlural = plural,
                        optionLabel = { volt(it) },
                        optionDescription = description,
                    )
                }
                if (widthDp != null) Box(Modifier.width(widthDp.dp)) { content() } else content()
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withAnOptionLabel_everySegmentShowsTheFormattedText_notTheBareNumber() {
        launchLabelled()
        voltOptions.forEach { composeRule.onNodeWithText(volt(it)).assertIsDisplayed() }
        voltOptions.forEach { composeRule.onNodeWithText(it.toString()).assertDoesNotExist() }
    }

    @Test
    fun withAnOptionLabel_theSelectionIsMarkedOnTheChosenValue() {
        launchLabelled(selected = 128)
        composeRule.onNodeWithText(volt(128)).assertIsSelected()
        composeRule.onNodeWithText(volt(125)).assertIsNotSelected()
        composeRule.onNodeWithText(volt(123)).assertIsNotSelected()
    }

    @Test
    fun withAnOptionLabel_tappingReportsTheOptionValue_notTheLabel() {
        val reported = mutableListOf<Int>()
        launchLabelled(onSelect = { reported += it })

        composeRule.onNodeWithText(volt(128)).performClick()
        composeRule.onNodeWithText(volt(123)).performClick()

        assertEquals(listOf(128, 123), reported)
    }

    @Test
    fun withAnOptionLabel_everySegmentIsStillAtLeast56dpHigh() {
        launchLabelled()
        voltOptions.forEach { composeRule.onNodeWithText(volt(it)).assertHeightIsAtLeast(56.dp) }
    }

    @Test
    fun anOptionDescription_isTheSpokenTextOfEverySegment() {
        launchLabelled()
        voltOptions.forEach { composeRule.onNode(hasContentDescription("${volt(it)} Volt")).assertIsDisplayed() }
    }

    @Test
    fun anOptionDescription_winsOverThePluralWhenBothAreGiven() {
        launchLabelled(plural = R.plurals.settings_seconds_a11y)
        voltOptions.forEach {
            composeRule.onNode(hasContentDescription("${volt(it)} Volt")).assertIsDisplayed()
            val fromPlural = composeRule.activity.resources.getQuantityString(R.plurals.settings_seconds_a11y, it, it)
            composeRule.onNode(hasContentDescription(fromPlural)).assertDoesNotExist()
        }
    }

    @Test
    fun withoutAnyDescription_theLabelItselfIsAllThatIsAnnounced() {
        launchLabelled(description = null, plural = null)
        voltOptions.forEach {
            composeRule.onNodeWithText(volt(it)).assertIsDisplayed()
            composeRule.onNode(hasContentDescription("${volt(it)} Volt")).assertDoesNotExist()
        }
    }

    @Test
    fun threeLabelledSegmentsFitANarrow320dpPhone() {
        launchLabelled(widthDp = 320)
        voltOptions.forEach { composeRule.onNodeWithText(volt(it)).assertIsDisplayed().assertWidthIsAtLeast(48.dp) }
    }

    @Test
    fun labelledSegmentsStayOnOneLineAtLargeFontScale() {
        // Wie labelsStayOnOneLineAtLargeFontScale, aber mit Beschriftung: "12,5" ist länger als eine zweistellige Zahl.
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(320.dp)) {
                        ChoiceSetting(
                            headline = "Voltage",
                            supportingText = "Volts",
                            options = voltOptions,
                            selected = 125,
                            onSelect = {},
                            optionLabel = { volt(it) },
                        )
                    }
                }
            }
        }
        for (scale in listOf(1.0f, 1.3f, 1.5f)) {
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            voltOptions.forEach { option ->
                val layout = labelLayout(volt(option))
                assertFalse("label ${volt(option)} overflows its segment at fontScale $scale: ${layout.describe()}", layout.overflows())
            }
        }
    }

    /** The text layout of the label of [option]. Read from the unmerged tree: the merged button
     *  node carries no text layout. */
    private fun labelLayout(option: Int): TextLayoutResult = labelLayout(option.toString())

    private fun labelLayout(text: String): TextLayoutResult {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** Whether the label needed more width than its segment offered (wrapped or clipped). NOT
     *  `didOverflowWidth`: with `softWrap = false` the paragraph is laid out at the full available
     *  width, so that flag is true for every label narrower than its segment. The natural text width
     *  against the available width is the reliable test. */
    private fun TextLayoutResult.overflows(): Boolean =
        lineCount > 1 || multiParagraph.maxIntrinsicWidth > layoutInput.constraints.maxWidth

    private fun TextLayoutResult.describe(): String =
        "lines=$lineCount naturalWidth=${multiParagraph.maxIntrinsicWidth}px " +
            "availableWidth=${layoutInput.constraints.maxWidth}px fontSize=${layoutInput.style.fontSize}"

    @Test
    fun labelsStayOnOneLineAtLargeFontScale() {
        // "Große Schrift" (system font scale) with the app's own typography (BIKE_SCALE 1.18) on a
        // narrow 320 dp phone: five three-digit-capable segments must not wrap or clip their number.
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(320.dp)) {
                        ChoiceSetting(
                            headline = "Headline",
                            supportingText = "Supporting",
                            options = options,
                            selected = 20,
                            onSelect = {},
                            optionDescriptionPlural = R.plurals.settings_seconds_a11y,
                        )
                    }
                }
            }
        }
        for (scale in listOf(1.0f, 1.3f, 1.5f)) {
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            options.forEach { option ->
                val layout = labelLayout(option)
                assertFalse(
                    "label $option overflows its segment at fontScale $scale: ${layout.describe()}",
                    layout.overflows()
                )
            }
        }
    }
}
