package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import de.adv990de.obd2.R

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
 */
@StringRes
fun slotPlaceholderRes(
    pidId: String?,
    supportedPidIds: Set<String>?,
    isConnected: Boolean,
): Int = when {
    pidId.isNullOrBlank() -> R.string.dashboard_slot_empty
    isConnected && supportedPidIds != null && pidId !in supportedPidIds ->
        R.string.dashboard_slot_unsupported
    else -> R.string.dashboard_slot_empty
}
