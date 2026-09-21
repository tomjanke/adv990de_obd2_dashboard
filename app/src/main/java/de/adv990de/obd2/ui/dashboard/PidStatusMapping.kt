package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.components.TileStatus

/**
 * Maps a live PID reading (in the PID's native unit) to a [TileStatus] for the
 * dashboard tile accent. Thresholds are *presentation* choices — the OBD spec
 * defines no "warning level". Values were picked for a normal street-car
 * envelope; sport / track builds will want different cuts (future: per-slot
 * user-configurable thresholds).
 *
 * Imperial flag is NOT considered here — thresholds are defined in metric
 * units (°C, V, %, rpm). The caller must pass the metric value, not the
 * imperial-converted one.
 *
 * NaN / Infinite → [TileStatus.Ok] (we don't alarm on stale / unread data —
 * the value text already shows "--").
 *
 * Unknown PID ids → [TileStatus.Ok] (silent default; better than over-warning
 * on unfamiliar readings).
 */
fun pidTileStatus(pidId: String, metricValue: Float): TileStatus {
    if (metricValue.isNaN() || metricValue.isInfinite()) return TileStatus.Ok
    return when (pidId) {
        PidRegistry.COOLANT_TEMP.id -> when {
            metricValue > 120f -> TileStatus.Critical
            metricValue > 110f -> TileStatus.Warning
            else               -> TileStatus.Ok
        }
        PidRegistry.ENGINE_OIL_TEMP.id -> when {
            metricValue > 135f -> TileStatus.Critical
            metricValue > 120f -> TileStatus.Warning
            else               -> TileStatus.Ok
        }
        PidRegistry.CONTROL_MODULE_VOLTAGE.id -> when {
            // Ein antwortendes Steuergeraet hat immer Spannung: 0 V bedeutet "keine Antwort"
            // (Adapter liefert NO DATA, Platzhalter-Wert), nicht "Batterie leer". Ohne diese
            // Zeile faerbt sich die Kachel rot, sobald der PID nicht unterstuetzt wird.
            metricValue <= 0f   -> TileStatus.Ok
            metricValue < 11.0f -> TileStatus.Critical   // engine likely can't start
            metricValue < 11.8f -> TileStatus.Warning    // battery weak
            metricValue > 15.0f -> TileStatus.Warning    // overcharging / regulator fault
            else                -> TileStatus.Ok
        }
        PidRegistry.RPM.id -> when {
            metricValue > 7500f -> TileStatus.Critical
            metricValue > 6500f -> TileStatus.Warning
            else                -> TileStatus.Ok
        }
        PidRegistry.ENGINE_LOAD.id -> when {
            metricValue > 90f -> TileStatus.Warning  // sustained high load (no duration model)
            else              -> TileStatus.Ok
        }
        PidRegistry.FUEL_TANK_LEVEL.id -> when {
            metricValue < 10f -> TileStatus.Critical
            metricValue < 20f -> TileStatus.Warning
            else              -> TileStatus.Ok
        }
        PidRegistry.CATALYST_TEMP_B1S1.id -> when {
            metricValue > 850f -> TileStatus.Critical
            metricValue > 750f -> TileStatus.Warning
            else               -> TileStatus.Ok
        }
        else -> TileStatus.Ok
    }
}
