package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.PidDefinition

/**
 * Werte, die die App selbst berechnet — es gibt keinen OBD-Befehl dazu. Sie stehen bewusst NICHT in der [PidRegistry]: Was dort steht,
 * fragt die App beim Steuergeraet ab, bietet die Kachelauswahl an und gleicht die PID-Erkennung (Discovery) ab.
 *
 * Heute nur der Gang (die Gang-Box des Dashboards, `ValueSource.DERIVED`): er steht als Zeile in der Aufzeichnung. **Id und Name
 * stehen als Text in Datenbank und CSV — nie umbenennen.** Die Zahlen des Werts beschreibt `gearRecordValue` (ui/dashboard).
 */
object DerivedPids {

    const val GEAR_ID = "GEAR"

    /** Der angezeigte Gang: 1-6, 0 = "N", -1 = nicht bestimmbar. Die Formel liest nie Bytes (`NaN`): der Wert kommt nicht vom Fahrzeug. */
    val GEAR = PidDefinition(
        id = GEAR_ID,
        name = "Gear", labelRes = R.string.pid_gear,
        unit = "",
        minValue = -1f,
        maxValue = 6f,
        formula = { Float.NaN },
    )

    /** Alle abgeleiteten Werte nach Id — der CSV-Export nennt damit auch sie beim Namen. */
    val all: Map<String, PidDefinition> = mapOf(GEAR.id to GEAR)
}
