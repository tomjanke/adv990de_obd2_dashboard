package de.adv990de.obd2.obd.model

/**
 * Woher ein Messwert stammt. Anfangs gab es nur eine Quelle (das Fahrzeug); die GPS-Geschwindigkeit
 * (`mergeGpsSpeed`) traegt aber bewusst dieselbe PID-ID wie die des Fahrzeugs, damit sie in den Geschwindigkeits-Slot faellt —
 * ohne diese Angabe waere in der Aufzeichnung und im CSV-Export nicht mehr zu erkennen, welche Quelle ein Wert hatte.
 * [DERIVED] ist, was die App aus anderen Werten selbst berechnet (der angezeigte Gang, `DerivedPids`) — nichts davon hat ein Sensor
 * oder ein Empfaenger gemessen.
 *
 * Der Name wird als Text in der Datenbank (`sensor_readings.source`) und im CSV gespeichert: **nie umbenennen**.
 */
enum class ValueSource { ECU, GPS, DERIVED }

data class SensorValue(
    val pid: PidDefinition,
    val value: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val source: ValueSource = ValueSource.ECU,
)
