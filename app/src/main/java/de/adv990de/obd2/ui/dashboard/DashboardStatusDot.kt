package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.ui.components.StatusDotState

/**
 * Maps the dashboard's connection state to a [StatusDotState] for the RPM bar's trailing
 * indicator. `null` means no dot is rendered (the fully disconnected/failed case looks the same
 * as before this existed — a plain icon button, no dot).
 *
 * `isConnected` wins over `isReconnecting` even though [DashboardUiState] should never carry
 * both true at once (see `DashboardViewModel.onConnectionEvent`) — the precedence is defined
 * here anyway so this function stays correct even if that invariant is ever relaxed.
 */
fun dashboardStatusDotState(isConnected: Boolean, isReconnecting: Boolean): StatusDotState? = when {
    isConnected -> StatusDotState.Ok
    isReconnecting -> StatusDotState.Connecting
    else -> null
}
