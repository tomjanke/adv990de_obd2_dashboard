package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.ui.logbook.MaintenanceIntervalDialog
import de.adv990de.obd2.ui.logbook.formatOdometer
import de.adv990de.obd2.ui.logbook.labelRes
import de.adv990de.obd2.ui.theme.PreviewTheme

/** Test tag der Liste (zum Scrollen in Tests). */
internal const val MAINTENANCE_LIST_TEST_TAG = "settings_maintenance_list"

/** Test tag einer Zeile der Seite (je Wartungsart eine). */
internal fun maintenanceIntervalRowTestTag(type: LogType) = "settings_maintenance_row_${type.name}"

/**
 * Die Unterseite "Wartungsintervalle" (Einstellungen, Gruppe "Fahrzeug"): je Wartungsart des Logbuchs eine Zeile mit dem Intervall in km oder "Nicht festgelegt" — **ohne Vorgabe**, der Nutzer
 * nennt die Werte. Ein Tipp oeffnet denselben Dialog wie die Uebersicht im Logbuch. Der Inhalt ([MaintenanceIntervalsList]) ist zustandslos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaintenanceIntervalsScreen(navController: NavController, vm: MaintenanceIntervalsViewModel = hiltViewModel()) {
    val intervals by vm.intervals.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_maintenance_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.heightIn(min = SettingsRowMinHeight)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        MaintenanceIntervalsList(intervals, onChange = vm::setInterval, modifier = Modifier.padding(padding))
    }
}

/** Der Inhalt der Seite: ein Satz, was die Intervalle tun, und die sechs Zeilen. */
@Composable
internal fun MaintenanceIntervalsList(intervals: Map<LogType, Int>, onChange: (LogType, Int) -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    // Der Dialog gehoert zur Seite: der Name der Art ueberlebt eine Drehung.
    var dialogFor by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag(MAINTENANCE_LIST_TEST_TAG)) {
        item(key = "intro") {
            Text(
                stringResource(R.string.settings_maintenance_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
        }
        items(MAINTENANCE_TYPES, key = { it.name }) { type ->
            val km = intervals[type]
            ListItem(
                headlineContent = { Text(stringResource(type.labelRes())) },
                supportingContent = {
                    Text(if (km != null) stringResource(R.string.logbook_interval_every, formatOdometer(km, locale)) else stringResource(R.string.settings_maintenance_not_set))
                },
                modifier = Modifier
                    .heightIn(min = SettingsRowMinHeight)
                    .testTag(maintenanceIntervalRowTestTag(type))
                    .clickable(role = Role.Button) { dialogFor = type.name },
            )
            HorizontalDivider()
        }
    }
    dialogFor?.let { name ->
        val type = LogType.entries.firstOrNull { it.name == name }
        if (type == null) {
            dialogFor = null
        } else {
            MaintenanceIntervalDialog(
                type = type,
                initialKm = intervals[type],
                onSave = { km ->
                    onChange(type, km)
                    dialogFor = null
                },
                onDismiss = { dialogFor = null },
            )
        }
    }
}

// region — Previews

@Composable
private fun MaintenanceIntervalsPreviewContent() {
    MaintenanceIntervalsList(intervals = mapOf(LogType.CHAIN_SMALL to 500, LogType.SERVICE_LARGE to 11_000), onChange = { _, _ -> })
}

@Preview(name = "Maintenance intervals (light)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PreviewMaintenanceIntervalsLight() {
    PreviewTheme(dark = false) { MaintenanceIntervalsPreviewContent() }
}

@Preview(name = "Maintenance intervals (dark)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PreviewMaintenanceIntervalsDark() {
    PreviewTheme(dark = true) { MaintenanceIntervalsPreviewContent() }
}

// endregion
