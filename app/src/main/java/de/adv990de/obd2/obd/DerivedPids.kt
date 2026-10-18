package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.PidDefinition

/**
 * Werte, die die App selbst berechnet — es gibt keinen OBD-Befehl dazu. Sie stehen bewusst NICHT in der [PidRegistry]: Was dort steht,
 * fragt die App beim Steuergeraet ab, bietet die Kachelauswahl an und gleicht die PID-Erkennung (Discovery) ab.
 *
 * Heute der Gang (die Gang-Box des Dashboards) und die Farbe des Oel-Balkens, beide mit `ValueSource.DERIVED`: sie stehen als Zeile in der
 * Aufzeichnung. **Ids und Namen stehen als Text in Datenbank und CSV — nie umbenennen.** Die Zahlen der Werte beschreiben `gearRecordValue`
 * und `oilBarRecordValue` (ui/dashboard).
 */
object DerivedPids {

    const val GEAR_ID = "GEAR"
    const val OIL_BAR_ID = "OIL_BAR"

    /** Die Slot-ID der Gelaende-Kachel (Hoehe, Steigung, Peilung). **Persistierter Zustand (`slot_pids`) — nie umbenennen.** */
    const val TERRAIN_ID = "TERRAIN"

    /** Der angezeigte Gang: 1-6, 0 = "N", -1 = nicht bestimmbar. Die Formel liest nie Bytes (`NaN`): der Wert kommt nicht vom Fahrzeug. */
    val GEAR = PidDefinition(
        id = GEAR_ID,
        name = "Gear", labelRes = R.string.pid_gear,
        unit = "",
        minValue = -1f,
        maxValue = 6f,
        formula = { Float.NaN },
    )

    /**
     * Die angezeigte Farbe des Oel-Balkens: 0 = rot, 1 = gelb, 2 = gruen, -1 = grau (noch kein Kuehlmittelwert). Wie beim Gang liest die Formel nie
     * Bytes (`NaN`) — der Wert ist die Anzeige, nicht eine Messung.
     */
    val OIL_BAR = PidDefinition(
        id = OIL_BAR_ID,
        name = "Oil bar", labelRes = R.string.pid_oil_bar,
        unit = "",
        minValue = -1f,
        maxValue = 2f,
        formula = { Float.NaN },
    )

    /**
     * Die Gelaende-Kachel als **Eintrag der Slot-Auswahl**: kein Messwert, sondern eine Kachel, die drei Werte des Telefons (Hoehe, Steigung, Peilung) zeigt
     * (`DashboardUiState.terrain`, Rechnung in `de.adv990de.obd2.terrain`). Steht bewusst **nicht** in [all] (wird nie aufgezeichnet — der CSV-Export benennt
     * damit Zeilen) und nicht in der [PidRegistry] (wird nie abgefragt). Die Formel liest nie Bytes (`NaN`).
     */
    val TERRAIN = PidDefinition(
        id = TERRAIN_ID,
        name = "Terrain", labelRes = R.string.pid_terrain,
        unit = "",
        minValue = 0f,
        maxValue = 0f,
        formula = { Float.NaN },
    )

    /** Alle abgeleiteten, aufgezeichneten Werte nach Id — der CSV-Export nennt damit auch sie beim Namen. */
    val all: Map<String, PidDefinition> = listOf(GEAR, OIL_BAR).associateBy { it.id }
}
