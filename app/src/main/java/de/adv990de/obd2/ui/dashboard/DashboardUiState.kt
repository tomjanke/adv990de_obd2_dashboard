package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText

data class DashboardUiState(
    val readings: List<SensorValue> = emptyList(),
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    // References RECONNECT_ATTEMPT_COUNT so the default stays in sync if it ever changes.
    val reconnectTotal: Int = RECONNECT_ATTEMPT_COUNT,
    // UiText: static messages use StringResource (localizable), dynamic messages (e.g. exception
    // messages from the BT stack) use DynamicString.
    val error: UiText? = null,
    val sparklines: Map<String, List<Float>> = emptyMap(),
    val rpmValue: Float = 0f,
    val engineTempC: Float? = null,
    val speedKmh: Float? = null,
    val gear: Int? = null,
    /** Steht und Motor laeuft (s. [isNeutral]) — schliesst [gear] aus: gesetzt ist hoechstens eines. */
    val isNeutral: Boolean = false,
    val oilBarColor: OilBarColor? = null,
    /**
     * Die Verbindung steht, aber das Steuergeraet liefert seit [OBD_STALE_AFTER_MS] keine Messwerte
     * mehr (z. B. Zuendung aus): Kacheln und Drehzahlbalken zeigen dann "--" statt alter Zahlen
     * ([SessionEvent.Stale]). Mit der Verbindung ist [isConnected] false und dieses Feld auch.
     */
    val isStale: Boolean = false,
    /**
     * Der Link zum Adapter steht ([isConnected]), aber es kam noch kein Messwert: die Sitzung liest FIN und
     * Fehlercodes und wartet auf den ersten Zyklus. Bis 1.24.3 galt der Link erst mit dem ersten Wert als
     * "verbunden" — das Dashboard zeigte 2-10 s lang "Vorschau · nicht verbunden" (B24).
     */
    val awaitingFirstValues: Boolean = false,
) {
    companion object {
        fun disconnected(error: UiText? = null) = DashboardUiState(isConnected = false, error = error)
    }
}
