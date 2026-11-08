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
import de.adv990de.obd2.ui.navigation.openDataTransfer

/*
 * Die drei unteren Gruppen der Einstellungsseite, aus `SettingsScreen` herausgeloest (die Datei war 558 Zeilen lang). Reine Verschiebung:
 * Sie sind Erweiterungen von `LazyListScope` und bekommen von `SettingsScreen` genau das, was sie vorher aus dessen Umfeld lasen.
 */


internal fun LazyListScope.recordingSection(
    logOnlyDtc: Boolean,
    recordingVm: RecordingSettingsViewModel,
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
}


internal fun LazyListScope.vehicleSection(
    navController: NavController,
    fuel: FuelSettingsUi,
    onFuelRangeChange: (Int) -> Unit,
    onFuelCapacityChange: (Int) -> Unit,
    onFuelReserveChange: (Int) -> Unit,
) {
    // ═══ Fahrzeug ════════════════════════════════════════════════
    item { SettingsGroupHeader(stringResource(R.string.settings_group_vehicle)) }
    // Reichweite bei vollem Tank: Grundlage der virtuellen Tankanzeige (ohne Wert bleibt die Anzeige ausgeblendet); mit Tankinhalt aus dem Logbuch berechnet.
    item {
        FuelRangeSettingRow(rangeFullKm = fuel.rangeFullKm, onRangeChange = onFuelRangeChange, learned = fuel.learned)
        HorizontalDivider()
    }
    // Tankinhalt: macht aus dem Verbrauch des Logbuchs die Reichweite (nur der Nutzer kennt ihn, die App fragt kein Fahrzeug).
    item {
        FuelCapacitySettingRow(capacityDl = fuel.tankCapacityDl, onCapacityChange = onFuelCapacityChange)
        HorizontalDivider()
    }
    // Reserve der Tankanzeige: eine Marke im Balken, unter der die Zahl rot wird (kein Alarm).
    item {
        FuelReserveSettingRow(reserveKm = fuel.reserveKm, onReserveChange = onFuelReserveChange)
        HorizontalDivider()
    }
    // Wartungsintervalle des Logbuchs (Phase 3): ohne Vorgabe, der Nutzer nennt die Werte.
    item {
        SettingsLinkRow(
            title = stringResource(R.string.settings_maintenance_title),
            summary = stringResource(R.string.settings_maintenance_summary),
            onClick = { navController.navigate(Screen.MaintenanceIntervals.route) },
            modifier = Modifier.testTag(MAINTENANCE_LINK_TEST_TAG),
        )
        HorizontalDivider()
    }
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
            title = stringResource(R.string.settings_data_transfer),
            summary = stringResource(R.string.settings_data_transfer_summary),
            onClick = { navController.openDataTransfer(Screen.DataTransfer.routeFor()) },
        )
        HorizontalDivider()
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
