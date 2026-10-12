package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.navigation.Screen

/*
 * Die drei unteren Gruppen der Einstellungsseite, aus `SettingsScreen` herausgeloest (die Datei war 558 Zeilen lang). Reine Verschiebung:
 * Sie sind Erweiterungen von `LazyListScope` und bekommen von `SettingsScreen` genau das, was sie vorher aus dessen Umfeld lasen.
 */


internal fun LazyListScope.recordingSection(
    logOnlyDtc: Boolean,
    logOnlyDiscovered: Boolean,
    recordingVm: RecordingSettingsViewModel,
    navController: NavController,
) {
    // ═══ Aufzeichnung ════════════════════════════════════════════
    item { SettingsGroupHeader(stringResource(R.string.settings_recording_section)) }
    item {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_log_only_dtc),
            summary = stringResource(R.string.settings_log_only_dtc_desc),
            checked = logOnlyDtc,
            onCheckedChange = { recordingVm.toggleLogOnlyDtc() },
        )
        HorizontalDivider()
    }
    item {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_log_only_discovered),
            summary = stringResource(R.string.settings_log_only_discovered_desc),
            checked = logOnlyDiscovered,
            onCheckedChange = { recordingVm.toggleLogOnlyDiscovered() },
        )
        HorizontalDivider()
    }
    item {
        SettingsLinkRow(
            title = stringResource(R.string.settings_logging_pids),
            summary = stringResource(R.string.settings_logging_pids_summary),
            onClick = { navController.navigate(Screen.LoggingConfig.route) },
        )
        HorizontalDivider()
    }
}


internal fun LazyListScope.vehicleSection(navController: NavController) {
    // ═══ Fahrzeug ════════════════════════════════════════════════
    item { SettingsGroupHeader(stringResource(R.string.settings_group_vehicle)) }
    item {
        SettingsLinkRow(
            title = stringResource(R.string.settings_vehicle_info),
            summary = stringResource(R.string.settings_vehicle_info_summary),
            onClick = { navController.navigate(Screen.VehicleInfo.route) },
        )
        HorizontalDivider()
    }
}


internal fun LazyListScope.infoSection(navController: NavController, crashVm: CrashReportingSettingsViewModel) {
    // ═══ Info & Wartung ══════════════════════════════════════════
    item { SettingsGroupHeader(stringResource(R.string.settings_group_info)) }
    // Nur in einer Version mit eingebautem DSN: ohne ihn gibt es nichts, was dieser Schalter abschalten koennte (QS B33).
    if (crashVm.available) {
        item {
            val crashReportsEnabled by crashVm.enabled.collectAsStateWithLifecycle()
            SettingsSwitchRow(
                title = stringResource(R.string.settings_crash_reports_title),
                summary = stringResource(R.string.settings_crash_reports_summary),
                checked = crashReportsEnabled,
                onCheckedChange = { crashVm.setEnabled(it) },
                modifier = Modifier.testTag(CRASH_REPORTS_SWITCH_TEST_TAG),
            )
            HorizontalDivider()
        }
    }
    item {
        SettingsLinkRow(
            title = stringResource(R.string.settings_app_logs),
            summary = stringResource(R.string.settings_app_logs_summary),
            onClick = { navController.navigate(Screen.AppLogs.route) },
        )
        HorizontalDivider()
    }
    item {
        SettingsLinkRow(
            title = stringResource(R.string.settings_app_info),
            summary = stringResource(R.string.settings_app_info_summary),
            onClick = { navController.navigate(Screen.AppInfo.route) },
        )
        HorizontalDivider()
    }
}
