package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource

/**
 * Der angezeigte Gang als Zahl der Aufzeichnung (Nutzerwunsch 2026-10-14): **1-6 = Gang, 0 = "N", -1 = nicht bestimmbar ("-")**.
 * Es ist genau das, was die Gang-Box nach dem Zyklus zeigt (mit Hysterese, Anzeige-Halten und GPS-Gegenprobe), nicht eine zweite
 * Berechnung — die Auswertung sieht, was der Fahrer sah. "N" gewinnt wie in `gearBoxContent` vor einem Gang.
 *
 * Die Zahlen stehen als Wert in Datenbank und CSV: **nie aendern.** "-1" ist eine Zeile wie jede andere (kein Auslassen): so ist
 * "die App kannte den Gang nicht" von "es gab keinen Zyklus" zu unterscheiden.
 */
internal const val GEAR_RECORD_NEUTRAL = 0f
internal const val GEAR_RECORD_UNKNOWN = -1f

internal fun gearRecordValue(reading: GearReading): Float = when {
    reading.isNeutral -> GEAR_RECORD_NEUTRAL
    reading.gear != null -> reading.gear.toFloat()
    else -> GEAR_RECORD_UNKNOWN
}

/** Die Aufzeichnungszeile zu einem Zyklus: [timestamp] ist der des Zyklus, die Herkunft [ValueSource.DERIVED] (berechnet, nicht gemessen). */
internal fun gearSensorValue(reading: GearReading, timestamp: Long): SensorValue =
    SensorValue(DerivedPids.GEAR, gearRecordValue(reading), timestamp, ValueSource.DERIVED)
