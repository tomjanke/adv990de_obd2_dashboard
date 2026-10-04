package de.adv990de.obd2.ui.settings

import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * Klassifiziert einen Theme-Konstantenwert (s. `AppPreferences.THEME_*`) als Farbschema
 * (`blue`/`amber`/`green` — eigenstaendige Farbpalette, keine Helligkeits-Wahl) oder nicht
 * (`system`/`light`/`dark` — Helligkeits-Varianten des Default-Farbschemas). Steuert in
 * [ThemePickerItem], welcher Segmented-Button-Eintrag markiert ist UND ob die Helligkeits-Zeile
 * (Helligkeits-`AnimatedVisibility`) ueberhaupt sichtbar ist. Eigene Top-Level-Funktion statt
 * Inline-Lambda, damit die Klassifikation unabhaengig vom Composable testbar ist (s.
 * `SettingsComponentsTest`).
 */
internal fun isColorTheme(theme: String): Boolean = theme in listOf(
    AppPreferences.THEME_BLUE, AppPreferences.THEME_AMBER, AppPreferences.THEME_GREEN
)

@Composable
internal fun ThemePickerItem(
    theme: String,
    onThemeChange: (String) -> Unit
) {
    val isColorTheme = isColorTheme(theme)

    // ── Color scheme row ─────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_color_scheme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isColorTheme,
                onClick = { onThemeChange(AppPreferences.THEME_SYSTEM) },
                shape = SegmentedButtonDefaults.itemShape(0, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_default), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_BLUE,
                onClick = { onThemeChange(AppPreferences.THEME_BLUE) },
                shape = SegmentedButtonDefaults.itemShape(1, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFF38BDF8), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_blue), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_AMBER,
                onClick = { onThemeChange(AppPreferences.THEME_AMBER) },
                shape = SegmentedButtonDefaults.itemShape(2, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFFFB923C), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_amber), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_GREEN,
                onClick = { onThemeChange(AppPreferences.THEME_GREEN) },
                shape = SegmentedButtonDefaults.itemShape(3, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFF4ADE80), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_green), softWrap = false)
                    }
                }
            )
        }
    }

    // ── Brightness row (only when Default is selected) ───────────────────
    AnimatedVisibility(visible = !isColorTheme) {
        val brightnessOptions = listOf(
            AppPreferences.THEME_SYSTEM,
            AppPreferences.THEME_LIGHT,
            AppPreferences.THEME_DARK
        )
        val brightnessLabels = listOf(
            stringResource(R.string.settings_theme_system),
            stringResource(R.string.settings_theme_light),
            stringResource(R.string.settings_theme_dark)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_brightness),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                brightnessOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = theme == option,
                        onClick = { onThemeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, brightnessOptions.size),
                        icon = {},
                        label = { Text(brightnessLabels[index]) }
                    )
                }
            }
        }
    }
}

/** Minimum touch height of one [ChoiceSetting] segment — CLAUDE.md design principle 4
 *  (glove-friendly tap targets, 56 dp). The M3 default of a segmented button is only 40 dp. */
private val ChoiceSegmentMinHeight = 56.dp

/** Horizontal content padding of one [ChoiceSetting] segment. The M3 default (12 dp per side)
 *  leaves only ~34 dp for the number on a 320 dp phone — "120" then overflows at a system font
 *  scale of 1.3 (`ChoiceSettingTest.labelsStayOnOneLineAtLargeFontScale`). The touch target stays
 *  the whole segment; only the text area grows. */
private val ChoiceSegmentContentPadding = PaddingValues(horizontal = 4.dp)

/**
 * A setting that is chosen from a few fixed whole numbers: a [ListItem] (headline + supporting
 * text) with a [SingleChoiceSegmentedButtonRow] below it. Replaced the free-text number fields of
 * the Settings "Connection" section — a typo can no longer produce a useless value, and every
 * option is visible at a glance (same segmented-button pattern as [ThemePickerItem]).
 *
 * The segments show the bare number so five of them fit a narrow phone; the unit belongs in
 * [supportingText], and each segment announces "N seconds" to screen readers via
 * [optionDescriptionPlural] (a plurals resource taking the number twice, e.g.
 * `<item quantity="other">%1$d seconds</item>`).
 *
 * Domain-agnostic like `LiveValueTile`: no preferences or ViewModel in here — the caller passes the
 * current [selected] value and handles [onSelect]. If [selected] is not in [options] (a stale
 * value) simply no segment is highlighted. [additionalSupportingText] is an optional extra line
 * under the supporting text (the reconnect row uses it for the resulting wait series).
 *
 * Every segment is at least [ChoiceSegmentMinHeight] high; the icon slot is empty (the check-mark
 * would eat the width the digits need).
 */
@Composable
internal fun ChoiceSetting(
    headline: String,
    supportingText: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    @PluralsRes optionDescriptionPlural: Int,
    modifier: Modifier = Modifier,
    additionalSupportingText: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(headline) },
            supportingContent = {
                Column {
                    Text(supportingText)
                    if (additionalSupportingText != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            additionalSupportingText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            options.forEachIndexed { index, option ->
                val description = pluralStringResource(optionDescriptionPlural, option, option)
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    contentPadding = ChoiceSegmentContentPadding,
                    // The min height has to sit on EACH segment: a min height on the row only makes
                    // the row taller, the buttons keep their 40 dp M3 default (ChoiceSettingTest).
                    modifier = Modifier.heightIn(min = ChoiceSegmentMinHeight),
                    icon = {},
                    label = {
                        Text(
                            option.toString(),
                            maxLines = 1,
                            softWrap = false,
                            // Spoken as "20 seconds" — the bare digits say nothing about the unit. On
                            // the label, not on the button: a description on the button showed up as
                            // a separate child node next to the digits in the accessibility tree.
                            modifier = Modifier.semantics { contentDescription = description }
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// region — Previews

@Composable
private fun PreviewChoiceSettingContent() {
    ChoiceSetting(
        headline = "Reconnect-Basisverzögerung",
        supportingText = "Sekunden bis zum ersten Reconnect-Versuch; jeder weitere Versuch verdoppelt die Wartezeit",
        options = AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS,
        selected = 10,
        onSelect = {},
        optionDescriptionPlural = R.plurals.settings_seconds_a11y,
        additionalSupportingText = "Wartezeiten: 10 · 20 · 40 · 80 · 160 s (insgesamt 5:10 min)",
    )
}

@Preview(name = "ChoiceSetting · light", showBackground = true)
@Composable
private fun PreviewChoiceSettingLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewChoiceSettingContent() }
    }
}

@Preview(name = "ChoiceSetting · dark (blue)", showBackground = true)
@Composable
private fun PreviewChoiceSettingDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewChoiceSettingContent() }
    }
}

// endregion
