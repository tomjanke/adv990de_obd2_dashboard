package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.StatusDot
import de.adv990de.obd2.ui.theme.PreviewTheme

/**
 * Das Bluetooth-Symbol mit dem Verbindungs-Punkt rechts neben der Drehzahlleiste (`RpmBar(trailingContent)`).
 *
 * Verbunden: ein reines Symbol ohne Polster, der Punkt sitzt direkt daneben. Nicht verbunden: das Symbol ist ein Knopf zu den Einstellungen
 * ([onOpenSettings]; die Navigation bleibt beim Aufrufer), der Punkt rueckt ueber einen negativen Abstand an das Glyph heran. Der Punkt und seine Farbe
 * kommen aus [dashboardStatusDotState] und [connectionDotColor]; ohne Zustand (nie verbunden, nichts fehlgeschlagen) gibt es keinen Punkt.
 */
@Composable
internal fun ConnectionIndicator(
    isConnected: Boolean,
    isReconnecting: Boolean,
    hasError: Boolean,
    onOpenSettings: () -> Unit,
) {
    val dotState = dashboardStatusDotState(
        isConnected = isConnected,
        isReconnecting = isReconnecting,
        hasError = hasError,
    )
    if (isConnected) {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            // 0dp: Dot soll so nah wie moeglich am Bluetooth-Icon sitzen.
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (dotState != null) {
                StatusDot(state = dotState, color = connectionDotColor(dotState))
            }
            Icon(
                painterResource(R.drawable.ic_bluetooth),
                contentDescription = stringResource(R.string.dashboard_bt_connected_desc),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    } else {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Negativer Abstand gleicht IconButtons eigenes ~12dp-Innenpolster
            // um sein 24dp-Icon aus (Default-Touch-Ziel 48dp) — sonst saehe der
            // Dot hier deutlich weiter vom Icon-Glyphen entfernt aus als bei der
            // verbundenen Variante oben, die ein reines Icon ohne Polster nutzt.
            horizontalArrangement = Arrangement.spacedBy((-12).dp),
        ) {
            if (dotState != null) {
                StatusDot(state = dotState, color = connectionDotColor(dotState))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth),
                    contentDescription = stringResource(
                        if (isReconnecting) R.string.dashboard_bt_connecting_desc
                        else R.string.dashboard_bt_disconnected_desc
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// region — Previews

@Composable
private fun ConnectionIndicatorStates() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
        ConnectionIndicator(isConnected = true, isReconnecting = false, hasError = false, onOpenSettings = {})
        ConnectionIndicator(isConnected = false, isReconnecting = true, hasError = false, onOpenSettings = {})
        ConnectionIndicator(isConnected = false, isReconnecting = false, hasError = true, onOpenSettings = {})
        ConnectionIndicator(isConnected = false, isReconnecting = false, hasError = false, onOpenSettings = {})
    }
}

@Preview(name = "ConnectionIndicator (light)", showBackground = true)
@Composable
private fun PreviewConnectionIndicatorLight() {
    PreviewTheme(dark = false) { ConnectionIndicatorStates() }
}

@Preview(name = "ConnectionIndicator (dark)", showBackground = true)
@Composable
private fun PreviewConnectionIndicatorDark() {
    PreviewTheme(dark = true) { ConnectionIndicatorStates() }
}

// endregion
