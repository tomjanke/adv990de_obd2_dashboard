package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.ui.theme.PreviewTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRow(
    device: ScannedDevice,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    enabled: Boolean = true,
    isLastConnected: Boolean = false,
    isConnected: Boolean = false
) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.type.name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLastConnected && !isConnected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.settings_last_connected_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (isConnected) {
                    Button(onClick = onDisconnect, enabled = enabled) { Text(stringResource(R.string.settings_disconnect)) }
                } else {
                    Button(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.settings_connect)) }
                }
            }
        }
    )
}

// region — Previews
//
// SettingsScreen selbst braucht `hiltViewModel()`; die Geraetezeile ist zustandslos.

private val PREVIEW_DEVICE = ScannedDevice(device = null, type = BtType.CLASSIC, name = "OBDII")

@Composable
private fun DeviceRowsPreviewContent() {
    Column {
        DeviceRow(PREVIEW_DEVICE, onClick = {})
        DeviceRow(PREVIEW_DEVICE.copy(name = "OBDLink MX+"), onClick = {}, isLastConnected = true)
        DeviceRow(PREVIEW_DEVICE.copy(name = "Veepeak"), onClick = {}, isConnected = true)
    }
}

@Preview(name = "DeviceRow · idle, last, connected (light)", showBackground = true)
@Composable
private fun PreviewDeviceRowsLight() {
    PreviewTheme(dark = false) { DeviceRowsPreviewContent() }
}

@Preview(name = "DeviceRow · idle, last, connected (dark)", showBackground = true)
@Composable
private fun PreviewDeviceRowsDark() {
    PreviewTheme(dark = true) { DeviceRowsPreviewContent() }
}

// endregion
