package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry

/**
 * Bestimmt, was eine Kachel anzeigt, solange kein Messwert vorliegt.
 *
 * Bis 1.9.1 stand dort immer der Hinweis auf die Belegung per Long-Press — auch dann, wenn der
 * Slot laengst belegt war und schlicht das Fahrzeug den PID nicht liefert. Gerade bei Motorraedern
 * ist das der Normalfall: Geschwindigkeit (010D) kommt dort oft vom ABS-Steuergeraet und liegt
 * gar nicht auf dem OBD2-Bus.
 *
 * @param pidId der dem Slot zugewiesene PID, leer wenn nicht belegt
 * @param supportedPidIds vom Steuergeraet gemeldete PID-Menge; null solange die Discovery laeuft
 * @param isConnected ohne Verbindung laesst sich ueber Unterstuetzung nichts sagen
 * @param gpsSpeedEnabled ist der GPS-Schalter an, ist die Geschwindigkeit nicht "nicht
 *        unterstuetzt", sondern wartet auf einen Fix — eine Aussage ueber das Fahrzeug waere hier
 *        schlicht falsch
 * @param gpsPermissionMissing bei eingeschaltetem GPS fehlt die Standortberechtigung (entzogen, B10):
 *        "wartet auf ein GPS-Signal" wuerde ewig warten, deshalb sagt die Kachel, was fehlt
 */
@StringRes
fun slotPlaceholderRes(
    pidId: String?,
    supportedPidIds: Set<String>?,
    isConnected: Boolean,
    gpsSpeedEnabled: Boolean = false,
    gpsPermissionMissing: Boolean = false,
): Int = when {
    pidId.isNullOrBlank() -> R.string.dashboard_slot_empty
    // Die Gelaende-Kachel ist kein PID des Fahrzeugs: "nicht vom Fahrzeug" waere falsch, ihr fehlt nur GPS (Schalter, Berechtigung oder noch kein Fix).
    pidId == DerivedPids.TERRAIN_ID && !gpsSpeedEnabled -> R.string.dashboard_slot_terrain_gps_off
    pidId == DerivedPids.TERRAIN_ID && gpsPermissionMissing -> R.string.dashboard_slot_gps_permission
    pidId == DerivedPids.TERRAIN_ID -> R.string.dashboard_slot_waiting_gps
    gpsSpeedEnabled && pidId == PidRegistry.SPEED.id && gpsPermissionMissing -> R.string.dashboard_slot_gps_permission
    gpsSpeedEnabled && pidId == PidRegistry.SPEED.id -> R.string.dashboard_slot_waiting_gps
    isConnected && supportedPidIds != null && pidId !in supportedPidIds ->
        R.string.dashboard_slot_unsupported
    else -> R.string.dashboard_slot_empty
}
