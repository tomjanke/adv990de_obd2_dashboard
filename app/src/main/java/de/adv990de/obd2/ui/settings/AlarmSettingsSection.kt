package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.ui.theme.PreviewTheme

/*
 * Die Gruppe „Alarme“ der Einstellungsseite (zwischen „Dashboard & Anzeige“ und „Aufzeichnung“), als `LazyListScope`-Erweiterung wie die Gruppen in
 * `SettingsSections.kt`. Zustandslos: `SettingsScreen` reicht den Zustand des `AlarmSettingsViewModel` und die Setter herein, ein Test und die Vorschau feste Eingaben.
 */

/**
 * Drei Schalter (Spannung, Kühlmittel, Fehler unterwegs), je Schalter **nur solange er an ist** seine zwei Schwellen als Segmente, am Ende die Hinweiszeile, wenn
 * Benachrichtigungen fehlen ([showNotificationHint], entschieden in [alarmNotificationHint]; der Knopf öffnet die App-Einstellungen über [onOpenAppSettings], nicht den Dialog —
 * nach zweimaligem Ablehnen zeigt Android ihn nicht mehr, wie bei den Standort-Hinweisen).
 *
 * Die Segmente bieten genau die Werte der Auswahllisten von [AlarmSettings] an; die Spannung steht in Volt mit einer Nachkommastelle, das Kühlmittel in ganzen °C — **auch
 * bei imperialen Einheiten** (diese Funktion kennt die Einheiten-Einstellung gar nicht). Jede Zeile ist mindestens 64 dp hoch, jedes Segment 56 dp (`ChoiceSetting`).
 */
internal fun LazyListScope.alarmSection(
    settings: AlarmSettings,
    showNotificationHint: Boolean,
    onOpenAppSettings: () -> Unit,
    onVoltageEnabledChange: (Boolean) -> Unit,
    onCoolantEnabledChange: (Boolean) -> Unit,
    onFaultsEnabledChange: (Boolean) -> Unit,
    onVoltageNoticeChange: (Int) -> Unit,
    onVoltageWarningChange: (Int) -> Unit,
    onCoolantNoticeChange: (Int) -> Unit,
    onCoolantWarningChange: (Int) -> Unit,
) {
    // ═══ Alarme ══════════════════════════════════════════════════
    item { SettingsGroupHeader(stringResource(R.string.settings_alarms_section)) }

    item {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_alarm_voltage_title),
            summary = stringResource(R.string.settings_alarm_voltage_summary),
            checked = settings.voltageEnabled,
            onCheckedChange = onVoltageEnabledChange,
            modifier = Modifier.testTag(ALARM_VOLTAGE_SWITCH_TEST_TAG),
        )
        HorizontalDivider()
    }
    if (settings.voltageEnabled) {
        item {
            VoltageChoiceSetting(
                headline = stringResource(R.string.settings_alarm_voltage_notice),
                supportingText = stringResource(R.string.settings_alarm_voltage_notice_desc),
                options = AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV,
                selected = settings.voltageNoticeDeciV,
                onSelect = onVoltageNoticeChange,
                modifier = Modifier.testTag(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG),
            )
            HorizontalDivider()
        }
        item {
            VoltageChoiceSetting(
                headline = stringResource(R.string.settings_alarm_voltage_warning),
                supportingText = stringResource(R.string.settings_alarm_voltage_warning_desc),
                options = AlarmSettings.VOLTAGE_WARNING_OPTIONS_DV,
                selected = settings.voltageWarningDeciV,
                onSelect = onVoltageWarningChange,
                modifier = Modifier.testTag(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG),
            )
            HorizontalDivider()
        }
    }

    item {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_alarm_coolant_title),
            summary = stringResource(R.string.settings_alarm_coolant_summary),
            checked = settings.coolantEnabled,
            onCheckedChange = onCoolantEnabledChange,
            modifier = Modifier.testTag(ALARM_COOLANT_SWITCH_TEST_TAG),
        )
        HorizontalDivider()
    }
    if (settings.coolantEnabled) {
        item {
            CoolantChoiceSetting(
                headline = stringResource(R.string.settings_alarm_coolant_notice),
                supportingText = stringResource(R.string.settings_alarm_coolant_notice_desc),
                options = AlarmSettings.COOLANT_NOTICE_OPTIONS_C,
                selected = settings.coolantNoticeC,
                onSelect = onCoolantNoticeChange,
                modifier = Modifier.testTag(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG),
            )
            HorizontalDivider()
        }
        item {
            CoolantChoiceSetting(
                headline = stringResource(R.string.settings_alarm_coolant_warning),
                supportingText = stringResource(R.string.settings_alarm_coolant_warning_desc),
                options = AlarmSettings.COOLANT_WARNING_OPTIONS_C,
                selected = settings.coolantWarningC,
                onSelect = onCoolantWarningChange,
                modifier = Modifier.testTag(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG),
            )
            HorizontalDivider()
        }
    }

    item {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_alarm_faults_title),
            summary = stringResource(R.string.settings_alarm_faults_summary),
            checked = settings.faultsEnabled,
            onCheckedChange = onFaultsEnabledChange,
            modifier = Modifier.testTag(ALARM_FAULTS_SWITCH_TEST_TAG),
        )
        HorizontalDivider()
    }

    // Ohne Benachrichtigungen bleibt das Alarmband im Dashboard die einzige Anzeige — der Nutzer erfährt es hier, nicht erst im Ernstfall.
    if (showNotificationHint) {
        item {
            PermissionHintRow(
                text = stringResource(R.string.settings_alarm_notifications_hint),
                buttonLabel = stringResource(R.string.settings_permission_open_settings),
                onOpenSettings = onOpenAppSettings,
                modifier = Modifier.testTag(ALARM_NOTIFICATION_HINT_TEST_TAG),
            )
            HorizontalDivider()
        }
    }
}

/**
 * Eine Spannungs-Schwelle: die Optionen sind Zehntelvolt (123, 125, …), das Segment zeigt sie als Volt mit einer Nachkommastelle in der Sprache des Geräts („12,5“), und der
 * Vorlesetext nennt die Einheit („12,5 Volt“). Die Locale kommt aus der Konfiguration der Oberfläche (`LocalResources`): ein Sprachwechsel zur Laufzeit schreibt die Segmente um.
 */
@Composable
private fun VoltageChoiceSetting(
    headline: String,
    supportingText: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    ChoiceSetting(
        headline = headline,
        supportingText = supportingText,
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        optionLabel = { formatVoltageOption(it, locale) },
        optionDescription = { resources.getString(R.string.settings_alarm_volts_a11y, formatVoltageOption(it, locale)) },
    )
}

/**
 * Eine Kühlmittel-Schwelle: das Segment zeigt die Zahl der Option, **immer °C und unverändert** (keine Einheiten-Umrechnung); der Vorlesetext nennt die Einheit („95 Grad Celsius“).
 */
@Composable
private fun CoolantChoiceSetting(
    headline: String,
    supportingText: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    ChoiceSetting(
        headline = headline,
        supportingText = supportingText,
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        optionDescription = { resources.getString(R.string.settings_alarm_celsius_a11y, it.toString()) },
    )
}

// region — Previews

@Composable
private fun AlarmSettingsSectionPreviewContent() {
    // Der seitliche Rand der Seite (SettingsScreen: contentPadding 16 dp).
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
        alarmSection(
            settings = AlarmSettings(),
            showNotificationHint = true,
            onOpenAppSettings = {},
            onVoltageEnabledChange = {}, onCoolantEnabledChange = {}, onFaultsEnabledChange = {},
            onVoltageNoticeChange = {}, onVoltageWarningChange = {}, onCoolantNoticeChange = {}, onCoolantWarningChange = {},
        )
    }
}

@Preview(name = "Alarm settings · light", showBackground = true)
@Composable
private fun PreviewAlarmSettingsSectionLight() {
    PreviewTheme(dark = false) { AlarmSettingsSectionPreviewContent() }
}

@Preview(name = "Alarm settings · dark (blue)", showBackground = true)
@Composable
private fun PreviewAlarmSettingsSectionDark() {
    PreviewTheme(dark = true) { AlarmSettingsSectionPreviewContent() }
}

// endregion
