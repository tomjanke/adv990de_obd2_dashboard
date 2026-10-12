package de.adv990de.obd2.ui.loggingconfig

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel

/** Test-only selector for this screen's [LazyColumn], so instrumentation tests can
 *  `performScrollToNode` to PID rows below the initially-composed viewport (confirmed empirically
 *  2026-09-29: only ~5 of 19 rows compose before the first frame settles on a Pixel 8 Pro). */
internal const val LOGGING_CONFIG_LIST_TEST_TAG = "logging_config_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggingConfigScreen(
    navController: NavController,
    vm: LoggingConfigViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel()
) {
    val loggedPidIds by vm.loggedPidIds.collectAsStateWithLifecycle()
    val latestReadings by vm.latestReadings.collectAsStateWithLifecycle()
    val useImperial by displayVm.useImperial.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logging_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back_desc)
                        )
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).testTag(LOGGING_CONFIG_LIST_TEST_TAG)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Button(
                        onClick = { vm.selectAll() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                    ) {
                        Text(stringResource(R.string.logging_config_select_all))
                    }
                    OutlinedButton(
                        onClick = { vm.deselectAll() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    ) {
                        Text(stringResource(R.string.logging_config_deselect_all))
                    }
                }
                HorizontalDivider()
            }
            items(vm.allPids, key = { it.id }) { pid ->
                val reading = latestReadings.firstOrNull { it.pid.id == pid.id }
                val valuePart = formatLoggingRowValue(reading?.value ?: Float.NaN, pid.unit, useImperial)
                val label = "${stringResource(pid.labelRes)}: $valuePart"
                val logged = pid.id in loggedPidIds
                ListItem(
                    // toggleable statt clickable (UID-13): TalkBack liest die Zeile als Kontrollkaestchen mit Zustand
                    // ("ausgewaehlt"/"nicht ausgewaehlt"); ein nacktes clickable meldete nur "Doppeltippen zum Aktivieren".
                    modifier = Modifier.toggleable(
                        value = logged,
                        role = Role.Checkbox,
                        onValueChange = { vm.togglePid(pid.id) },
                    ),
                    headlineContent = { Text(label) },
                    leadingContent = {
                        // onCheckedChange=null: the row-level click handler owns the toggle.
                        Checkbox(
                            checked = logged,
                            onCheckedChange = null
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Formats a single logging-row's value part ("<value> (<unit>)"), independent of the PID label
 * (which needs a Composable [stringResource] and stays in the caller). NaN/Infinite → "--" with
 * the raw (unaltered) unit, matching [de.adv990de.obd2.ui.dashboard.SensorCard]'s precedent: otherwise
 * the value is run through [convertUnit] and formatted with one decimal for volts, none otherwise.
 */
internal fun formatLoggingRowValue(value: Float, unit: String, useImperial: Boolean): String {
    if (value.isNaN() || value.isInfinite()) {
        return "-- ($unit)"
    }
    val (convertedValue, displayUnit) = convertUnit(value, unit, useImperial)
    val formatted = formatSensorNumber(convertedValue, unit)
    return "$formatted ($displayUnit)"
}
