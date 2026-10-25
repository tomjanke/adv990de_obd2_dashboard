package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition

/**
 * Die PIDs der Slot-Auswahl, nach Namen sortiert: die vom Steuergeraet gemeldeten ([supported]), ohne Verbindung oder ohne Liste die ganze Registry — dazu immer die
 * Gelaende-Kachel ([DerivedPids.TERRAIN]): sie ist kein Wert des Fahrzeugs, sondern des Telefons, und haengt nicht an der PID-Liste des Steuergeraets.
 *
 * Die Geschwindigkeit bleibt waehlbar, solange das GPS ([gpsEnabled]) sie liefern kann — sonst verschwaende sie bei genau den Fahrzeugen aus dem Picker, fuer die das Feature
 * gebaut wurde.
 */
internal fun availablePidsFor(supported: Set<String>?, gpsEnabled: Boolean): List<PidDefinition> {
    val all = PidRegistry.all.values.sortedBy { it.name }
    val vehicle = if (supported.isNullOrEmpty()) {
        all
    } else {
        all.filter { it.id in supported || (gpsEnabled && it.id == PidRegistry.SPEED.id) }
    }
    return vehicle + DerivedPids.TERRAIN
}
