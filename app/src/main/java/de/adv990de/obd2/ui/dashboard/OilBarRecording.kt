package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource

/**
 * Die Farbe des Oel-Balkens als Zahl der Aufzeichnung (Nutzerwunsch 2026-10-14): **0 = rot, 1 = gelb, 2 = gruen, -1 = grau** (der Balken hat noch
 * keine Farbe, weil noch kein Kuehlmittelwert eingetroffen ist). Es ist genau die Farbe, die der Balken nach dem Zyklus zeigt (Gruen-Hysterese,
 * Entprellen, Anzeige-Halten bei fehlendem Wert), nicht eine zweite Berechnung — die Auswertung sieht, was der Fahrer sah, auch wenn sich die
 * Schwellen der App spaeter aendern.
 *
 * Die Zahlen stehen als Wert in Datenbank und CSV: **nie aendern.** "-1" ist eine Zeile wie jede andere (kein Auslassen): so ist "der Balken war
 * grau" von "es gab keinen Zyklus" zu unterscheiden. Das `when` ist ohne `else`: eine neue Farbe zwingt zu einer eigenen Zahl.
 */
internal const val OIL_BAR_RECORD_RED = 0f
internal const val OIL_BAR_RECORD_YELLOW = 1f
internal const val OIL_BAR_RECORD_GREEN = 2f
internal const val OIL_BAR_RECORD_GREY = -1f

internal fun oilBarRecordValue(color: OilBarColor?): Float = when (color) {
    OilBarColor.Red -> OIL_BAR_RECORD_RED
    OilBarColor.Yellow -> OIL_BAR_RECORD_YELLOW
    OilBarColor.Green -> OIL_BAR_RECORD_GREEN
    null -> OIL_BAR_RECORD_GREY
}

/** Die Aufzeichnungszeile zu einem Zyklus: [timestamp] ist der des Zyklus, die Herkunft [ValueSource.DERIVED] (berechnet, nicht gemessen). */
internal fun oilBarSensorValue(color: OilBarColor?, timestamp: Long): SensorValue =
    SensorValue(DerivedPids.OIL_BAR, oilBarRecordValue(color), timestamp, ValueSource.DERIVED)
