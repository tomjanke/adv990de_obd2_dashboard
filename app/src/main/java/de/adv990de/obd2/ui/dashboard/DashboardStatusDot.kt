package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.ui.components.StatusDotState

/**
 * Maps the dashboard's connection state to a [StatusDotState] for the RPM bar's trailing
 * indicator. `null` means no dot is rendered (the fresh-install "never even tried to connect"
 * case — no device configured yet, nothing has failed).
 *
 * `isConnected` wins over `isReconnecting`, which wins over [hasError], even though
 * [DashboardUiState] should never carry more than one of these true at once (see
 * `DashboardViewModel.onConnectionEvent`) — the precedence is defined here anyway so this
 * function stays correct even if that invariant is ever relaxed. The `isReconnecting`-over-
 * [hasError] ordering matters for real: `DashboardViewModel.startReconnecting()` sets
 * `isReconnecting = true` without clearing a stale `error` from an earlier failure, so a fresh
 * attempt in progress must still show as "connecting", not "failed".
 *
 * [hasError] is `DashboardUiState.error != null` — set on [ConnectionEvent.Failed] (no device
 * configured, Bluetooth disabled, or all reconnect attempts exhausted). That field is never
 * otherwise shown to the user, so this dot is the only visual signal for that state.
 */
fun dashboardStatusDotState(isConnected: Boolean, isReconnecting: Boolean, hasError: Boolean): StatusDotState? = when {
    isConnected -> StatusDotState.Ok
    isReconnecting -> StatusDotState.Connecting
    hasError -> StatusDotState.Critical
    else -> null
}
