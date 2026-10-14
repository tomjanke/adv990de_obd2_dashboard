package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition

/**
 * Die tatsaechlich abzufragenden PIDs: die belegten Slots, gefiltert gegen die vom Steuergeraet
 * gemeldete Menge ([supported], `null` oder leer = keine Discovery, nichts filtern), dazu immer Drehzahl (RPM-Balken,
 * Ganganzeige) und Kuehlmitteltemperatur (Oel-Temperatur-Balken) — bei dynamischer Schwelle ([dynamicThresholdEnabled])
 * zusaetzlich die Oeltemperatur.
 *
 * **Die Geschwindigkeit gehoert zu jeder Fahrt:** meldet das Steuergeraet sie ([supported] enthaelt `010D`), wird sie auch ohne
 * Kachel abgefragt, damit sie in der Aufzeichnung steht. Meldet es sie nicht (bei Motorraedern oft: die Geschwindigkeit liegt
 * im ABS-Steuergeraet, nicht auf dem OBD2-Bus), ergaenzt das GPS sie, sofern eingeschaltet (`mergeGpsSpeed`). Ohne Discovery-Ergebnis
 * wird sie nicht von sich aus abgefragt: ein Fahrzeug, das sie nicht beantwortet, kostete sonst jeden Zyklus einen vergeblichen Befehl
 * (bei einem Zeitlimit 5 s) — dann gilt wie bisher nur die Kachel.
 *
 * Die Kuehlmitteltemperatur ist seit dem Oel-Temperatur-Balken unbedingt, genau wie RPM: Hing
 * sie nur an der Slot-Belegung (oder der standardmaessig ausgeschalteten dynamischen Schwelle),
 * bekam jeder Nutzer, der die Kuehlmittel-Kachel gegen einen anderen Wert getauscht hatte, nie
 * einen Messwert — der Balken blieb trotz Verbindung dauerhaft grau. Doppelt abgefragt wird sie
 * trotzdem nie: `distinctBy` unten entfernt den Eintrag, wenn sie zugleich ein Slot ist.
 *
 * Unbekannte Slot-IDs (im DataStore nach einem Downgrade) fallen weg; die Liste ist wegen RPM und Kuehlmittel nie leer. Einen Rueckfall auf
 * die Standard-PIDs gab es hier einmal (`ifEmpty`) — er konnte seit der Pflicht-Abfrage von RPM und Kuehlmittel nie mehr greifen und ist entfernt.
 */
internal fun selectPids(
    slotIds: List<String>,
    supported: Set<String>?,
    dynamicThresholdEnabled: Boolean,
): List<PidDefinition> {
    val filteredSlotIds = if (supported.isNullOrEmpty()) slotIds else slotIds.filter { it in supported }
    return buildList {
        addAll(filteredSlotIds)
        add(PidRegistry.RPM.id)
        add(PidRegistry.COOLANT_TEMP.id)
        if (!supported.isNullOrEmpty() && PidRegistry.SPEED.id in supported) {
            add(PidRegistry.SPEED.id)
        }
        if (dynamicThresholdEnabled) {
            add(PidRegistry.ENGINE_OIL_TEMP.id)
        }
    }
        .mapNotNull { PidRegistry.all[it] }
        .distinctBy { it.id }
}
