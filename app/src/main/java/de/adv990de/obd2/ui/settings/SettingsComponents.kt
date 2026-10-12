package de.adv990de.obd2.ui.settings

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Minimum touch height of every segmented-button segment on the Settings page ([ChoiceSetting] and
 *  [ThemePickerItem]) — CLAUDE.md design principle 4 (glove-friendly tap targets, 56 dp). The M3
 *  default of a segmented button is only 40 dp. */
internal val SegmentedButtonMinHeight = 56.dp

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
 * Every segment is at least [SegmentedButtonMinHeight] high; the icon slot is empty (the check-mark
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
                    modifier = Modifier.heightIn(min = SegmentedButtonMinHeight),
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

/** Minimum height of every row of the Settings page (CLAUDE.md design principle 4: settings lists at
 *  least 64 dp, the whole row is the touch target). */
private val SettingsRowMinHeight = 64.dp

/**
 * Header of one group of the Settings page: the cockpit-style ALL-CAPS [SectionLabel] in the accent
 * color, with air above (the previous group ends there) and a little below, and marked as a heading
 * so screen-reader users can jump from group to group.
 */
@Composable
internal fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier) {
    SectionLabel(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * A row that opens a sub-page: title, a one-line [summary] of what is behind it and a chevron. The
 * whole row is the touch target (at least [SettingsRowMinHeight]) — replaces the bare full-width
 * `TextButton`s the page used to end with, which told the user nothing about their target.
 */
@Composable
internal fun SettingsLinkRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = modifier
            .heightIn(min = SettingsRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * A setting that is on or off. The whole row is the toggle — tapping anywhere on it flips it, not
 * just the small switch (glove-friendly, CLAUDE.md design principle 4); the [Switch] itself only
 * shows the state (`onCheckedChange = null`), so there is exactly one toggle node: the row. A
 * `testTag` passed in [modifier] therefore sits on the node that carries the toggle state
 * (`assertIsOn` / `performClick` work on it).
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = modifier
            .heightIn(min = SettingsRowMinHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/**
 * Hinweis unter einer Einstellung, deren Berechtigung fehlt (B10, B21): was nicht geht und ein Knopf zu den
 * App-Einstellungen. Der Knopf oeffnet immer die Systemeinstellungen statt den Dialog erneut anzufordern —
 * nach zweimaligem Ablehnen zeigt Android ihn ohnehin nicht mehr, dort ist das der einzige Weg. Text in
 * `colorScheme.error` (Empty-State-/Hinweis-Fehlertext, s. CLAUDE.md), Knopf >= 56 dp.
 */
@Composable
internal fun PermissionHintRow(
    text: String,
    buttonLabel: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.heightIn(min = SettingsRowMinHeight),
        ) { Text(buttonLabel) }
    }
}

// region — Previews

@Composable
private fun ChoiceSettingPreviewContent() {
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
        Surface(color = MaterialTheme.colorScheme.background) { ChoiceSettingPreviewContent() }
    }
}

@Preview(name = "ChoiceSetting · dark (blue)", showBackground = true)
@Composable
private fun PreviewChoiceSettingDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { ChoiceSettingPreviewContent() }
    }
}

@Composable
private fun SettingsRowsPreviewContent() {
    // Der seitliche Rand der Seite (SettingsScreen: contentPadding 16 dp) — ohne ihn klebte die Gruppenueberschrift am Fensterrand.
    Column(Modifier.padding(horizontal = 16.dp)) {
        SettingsGroupHeader("Dashboard & Anzeige")
        SettingsSwitchRow("Imperiale Einheiten", "Geschwindigkeit in mph, Temperatur in °F", checked = true, onCheckedChange = {})
        SettingsSwitchRow("GPS-Geschwindigkeit", "Wenn das Fahrzeug keine liefert", checked = false, onCheckedChange = {})
        SettingsLinkRow("Getriebe", "Radumfang und Übersetzungen für die Ganganzeige", onClick = {})
    }
}

@Preview(name = "Settings rows · light", showBackground = true)
@Composable
private fun PreviewSettingsRowsLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { SettingsRowsPreviewContent() }
    }
}

@Preview(name = "Settings rows · dark (blue)", showBackground = true)
@Composable
private fun PreviewSettingsRowsDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { SettingsRowsPreviewContent() }
    }
}

// endregion
