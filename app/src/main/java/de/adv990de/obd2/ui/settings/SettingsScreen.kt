package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.components.BottomNav
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel(),
    recordingVm: RecordingSettingsViewModel = hiltViewModel(),
    driveVm: DriveStateViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val logOnlyDtc by recordingVm.logOnlyDtc.collectAsState()
    val logOnlyDiscovered by recordingVm.logOnlyDiscovered.collectAsState()
    val hideNavOnDrive by driveVm.hideNavOnDrive.collectAsState()
    val useImperial by displayVm.useImperial.collectAsState()
    val theme by displayVm.theme.collectAsState()
    LaunchedEffect(Unit) { vm.loadDevices() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            state.connectedDevice?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_connected_prefix, it), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.settings_disconnect)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Text(stringResource(R.string.settings_paired_devices), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.isConnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            // weight(1f) lets the device list grow to fill available space while keeping
            // the "Dashboard konfigurieren" button visible below without scrolling.
            LazyColumn(Modifier.weight(1f)) {
                items(state.bondedDevices, key = { it.device.address }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = { vm.connect(device) },
                        enabled = !state.isConnecting,
                        isLastConnected = device.device.address == state.lastDeviceAddress
                    )
                    HorizontalDivider()
                }
                if (state.bondedDevices.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_no_paired_devices),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            HorizontalDivider()

            // ── Recording section ─────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_recording_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_log_only_dtc)) },
                supportingContent = { Text(stringResource(R.string.settings_log_only_dtc_desc)) },
                trailingContent = {
                    Switch(
                        checked = logOnlyDtc,
                        onCheckedChange = { recordingVm.toggleLogOnlyDtc() }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_log_only_discovered)) },
                supportingContent = { Text(stringResource(R.string.settings_log_only_discovered_desc)) },
                trailingContent = {
                    Switch(
                        checked = logOnlyDiscovered,
                        onCheckedChange = { recordingVm.toggleLogOnlyDiscovered() }
                    )
                }
            )
            HorizontalDivider()

            // ── Display section ───────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_display_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_hide_nav_on_drive)) },
                supportingContent = { Text(stringResource(R.string.settings_hide_nav_on_drive_desc)) },
                trailingContent = {
                    Switch(
                        checked = hideNavOnDrive,
                        onCheckedChange = { driveVm.toggleHideNavOnDrive() }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_imperial_units)) },
                supportingContent = { Text(stringResource(R.string.settings_imperial_units_desc)) },
                trailingContent = {
                    Switch(
                        checked = useImperial,
                        onCheckedChange = { displayVm.toggleUseImperial() }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                trailingContent = {
                    val themeOptions = listOf("system", "light", "dark")
                    val themeLabels = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark)
                    )
                    SingleChoiceSegmentedButtonRow {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = theme == option,
                                onClick = { displayVm.setTheme(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                                label = { Text(themeLabels[index]) }
                            )
                        }
                    }
                }
            )
            HorizontalDivider()
            TextButton(
                onClick = { navController.navigate(Screen.DashboardConfig.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_configure_dashboard)) }
            HorizontalDivider()
            TextButton(
                onClick = { navController.navigate(Screen.VehicleInfo.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_vehicle_info)) }
            HorizontalDivider()
            TextButton(
                onClick = { navController.navigate(Screen.LoggingConfig.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_logging_pids)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRow(
    device: ScannedDevice,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLastConnected: Boolean = false
) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.type.name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLastConnected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.settings_last_connected_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Button(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.settings_connect)) }
            }
        }
    )
}
