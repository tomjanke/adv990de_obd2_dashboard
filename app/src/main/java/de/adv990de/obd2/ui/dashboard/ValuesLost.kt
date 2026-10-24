package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource

/**
 * Warum die Fahrzeugwerte fehlen, solange die Verbindung steht (`DashboardUiState.valuesLost`): **eingefroren** (das Fahrzeug antwortet weiter, aber mit demselben Wert; die App
 * hat schon einmal neu verbunden und haelt die Werte zurueck, [FrozenAction.Hold]) oder **keine aktuellen Werte** (seit [OBD_STALE_AFTER_MS] kam gar kein Zyklus). Ob der Adapter,
 * das Steuergeraet oder der Bus schuld ist, weiss die App nicht — die Texte sagen nur, was sie weiss.
 */
enum class ValuesLostCause { FROZEN, SILENT }

/**
 * Warum die Verbindung neu aufgebaut wird ([SessionEvent.ConnectionLost], `DashboardUiState.reconnectCause`): der Link ist abgerissen oder der Watchdog hat ausgeloest ([LINK]),
 * oder die App hat selbst getrennt, weil die Werte eingefroren waren ([FROZEN_VALUES]). Daraus wird aus "Vorschau · Verbindungsversuch 1/5" "Werte eingefroren · verbinde neu 1/5".
 */
enum class ConnectionLostCause { LINK, FROZEN_VALUES }

/** Der Text des lauten Bandes fuer fehlende Werte ([ValuesLostBanner]): Titel samt Format-Argumenten und eine optionale zweite Zeile. Ohne Compose, direkt JVM-testbar. */
internal data class ValuesBanner(
    @StringRes val titleRes: Int,
    val titleArgs: List<Any> = emptyList(),
    @StringRes val detailRes: Int? = null,
)

/**
 * Welches laute Band das Dashboard zeigt — `null`, wenn keines (dann gelten die schmalen Baender: "Vorschau · nicht verbunden", "Verbunden · warte auf Werte"; sie sind
 * erwartete Zustaende). Drei Faelle:
 * - [DashboardPhase.Stale] mit [ValuesLostCause.FROZEN]: "Werte eingefroren" samt Hinweis, was das fuer die Anzeige heisst;
 * - [DashboardPhase.Stale] sonst: "Keine aktuellen Werte" (auch ohne bekannte Ursache — die vorsichtige Lesart);
 * - [DashboardPhase.Offline] waehrend eines Neuverbindens, das die App wegen eingefrorener Werte ausgeloest hat: "Werte eingefroren · verbinde neu 1/5" (ohne Nummer vor dem
 *   ersten Versuch). Eine liegengebliebene Ursache ohne laufendes Neuverbinden zeigt nichts.
 */
internal fun valuesBanner(
    phase: DashboardPhase,
    valuesLost: ValuesLostCause?,
    reconnectCause: ConnectionLostCause?,
    isReconnecting: Boolean,
    reconnectAttempt: Int,
    reconnectTotal: Int,
): ValuesBanner? = when {
    phase == DashboardPhase.Stale && valuesLost == ValuesLostCause.FROZEN ->
        ValuesBanner(R.string.values_lost_frozen_title, detailRes = R.string.values_lost_frozen_detail)
    phase == DashboardPhase.Stale -> ValuesBanner(R.string.values_lost_silent_title)
    phase == DashboardPhase.Offline && isReconnecting && reconnectCause == ConnectionLostCause.FROZEN_VALUES ->
        if (reconnectAttempt >= 1) {
            ValuesBanner(R.string.values_lost_frozen_reconnecting, titleArgs = listOf(reconnectAttempt, reconnectTotal))
        } else {
            ValuesBanner(R.string.values_lost_frozen_reconnecting_start)
        }
    else -> null
}

/**
 * Die Vorschau-Belegung mit der GPS-Geschwindigkeit an der Stelle der Geschwindigkeits-Kachel — **nur in [DashboardPhase.Stale]** (Entscheidung D3): die Sitzung steht, der Adapter
 * schweigt oder liefert Eingefrorenes, das GPS lebt unabhaengig davon. Ohne Verbindung und bei "warte auf Werte" bleibt die Vorschau mit "--": eine Vorschau mit Zahlen war einmal der
 * Anlass einer Fehlersuche. Ohne brauchbaren GPS-Wert (null, keine Zahl, negativ) oder ohne Geschwindigkeits-Kachel bleibt die Liste, wie sie ist (dieselbe Instanz).
 * Die Quelle steht am Wert ([ValueSource.GPS], Beschriftung "Geschwindigkeit (GPS)"): die Kachel sagt, woher die Zahl kommt.
 */
internal fun List<SensorValue>.withStaleGpsSpeed(phase: DashboardPhase, gpsSpeedKmh: Float?): List<SensorValue> {
    if (phase != DashboardPhase.Stale) return this
    if (gpsSpeedKmh == null || !gpsSpeedKmh.isFinite() || gpsSpeedKmh < 0f) return this
    if (none { it.pid.id == PidRegistry.SPEED.id }) return this
    return map { if (it.pid.id == PidRegistry.SPEED.id) SensorValue(GPS_SPEED, gpsSpeedKmh, source = ValueSource.GPS) else it }
}

/** Ist das die GPS-Geschwindigkeit, die [withStaleGpsSpeed] eingesetzt hat? Nur sie wird in der Vorschau-Belegung als Zahl gezeichnet; jede andere Kachel bleibt "--". */
internal fun SensorValue.isStaleGpsReading(): Boolean = source == ValueSource.GPS
