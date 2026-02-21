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
import de.adv990de.obd2.ui.components.BottomNav
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()
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
            TextButton(
                onClick = { navController.navigate(Screen.DashboardConfig.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_configure_dashboard)) }
            HorizontalDivider()
            TextButton(
                onClick = { navController.navigate(Screen.VehicleInfo.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_vehicle_info)) }
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
