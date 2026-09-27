package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.components.StatusDotState
import de.adv990de.obd2.ui.theme.StatusColors

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

/**
 * Feste Ampel-Farben (Rot/Gelb/Gruen) ausschliesslich fuer den Bluetooth-Verbindungsstatus-Dot im
 * Dashboard — auf Nutzerwunsch. Das ist eine bewusste Abweichung von [StatusDot]s sonstiger,
 * theme-getönter Non-Alarm-Farbgebung fuer `Ok` (s. CLAUDE.md "OK ist kein Alarm-Signal"): Fuer
 * genau diesen einen Dot soll Verbunden/Gruen, Verbindet.../Gelb, Fehler/Rot gelten. Nicht fuer
 * andere `StatusDot`-Verwendungen wiederverwenden — die behalten die bestehende Semantik (s.
 * [StatusDot]s eigenes `baseColor`, das nur greift, wenn kein `color`-Override uebergeben wird).
 *
 * `Critical`/`Warning` reichen die bereits vorhandenen [StatusColors]-Werte unveraendert durch —
 * Rot war schon vorher StatusColors.Critical, keine Dopplung noetig. Das Gruen fuer `Ok` ist
 * [StatusColors.TrafficLightGreen] — dieselbe benannte Farbe nutzt der Oel-Temperatur-Balken fuer
 * "Betriebstemperatur" (`oilBarStripColor()`), die zweite dokumentierte Ampel-Ausnahme.
 */
fun connectionDotColor(state: StatusDotState): Color = when (state) {
    StatusDotState.Ok         -> StatusColors.TrafficLightGreen
    StatusDotState.Connecting -> Color(0xFFEAB308) // Tailwind Yellow 500
    StatusDotState.Critical   -> StatusColors.Critical
    StatusDotState.Warning    -> StatusColors.Warning
}
