package de.adv990de.obd2.data

import de.adv990de.obd2.alarm.EngineGate
import de.adv990de.obd2.obd.PidRegistry

/**
 * Min und Max je Fahrt (Idee F11, Auftrag des Nutzers 2026-10-19): Kuehlmittel und Spannung im Detail-Dialog einer Fahrt. Die Rechnung steht hier ohne Android-Bezug,
 * damit sie direkt testbar ist; `SessionRepository.sessionStats` liefert ihr die Zeilen aus der Datenbank.
 */

/** Kleinster und groesster Wert einer Groesse in einer Fahrt, in der Einheit der Aufzeichnung (°C, V). */
data class MinMax(val min: Float, val max: Float)

/**
 * Was eine Fahrt an Kuehlmittel und Spannung aufgezeichnet hat; `null` = es gibt keinen Wert (nicht aufgezeichnet, kein gueltiger Wert oder — bei der Spannung — nie bei laufendem Motor).
 * Die beiden Faelle „nicht aufgezeichnet“ und „nie bei laufendem Motor“ trennt das Ergebnis bewusst nicht: beide heissen „kein Wert“.
 */
data class SessionStats(val coolant: MinMax?, val voltage: MinMax?)

/**
 * Die Abfrage fuer [SessionStatsAccumulator]: nur die drei Groessen, die er braucht, in Aufzeichnungsreihenfolge (`ORDER BY id`, dieselbe Begruendung wie bei `EXPORT_QUERY_SQL`: der Index auf
 * `sessionId` liefert sie ohne Sortierlauf). Die PID-IDs stehen aus der [PidRegistry] drin, nie aus einer Eingabe.
 */
internal val SESSION_STATS_QUERY_SQL: String =
    "SELECT timestamp, pidId, value FROM sensor_readings WHERE sessionId = ? AND pidId IN " +
        "('${PidRegistry.RPM.id}', '${PidRegistry.COOLANT_TEMP.id}', '${PidRegistry.CONTROL_MODULE_VOLTAGE.id}') ORDER BY id ASC"

/**
 * Faltet die Zeilen einer Fahrt — in Aufzeichnungsreihenfolge, eine nach der anderen, mit konstantem Speicher — zu [SessionStats].
 *
 * - **Kuehlmittel:** jeder endliche Wert zaehlt, auch ohne laufenden Motor. Das Minimum ist dann die Starttemperatur, das Maximum die hoechste Temperatur der Fahrt.
 * - **Spannung nur bei laufendem Motor, ab 15 s nach dem Start:** dieselbe Regel wie bei den Alarmen und dem Lade-/Entlade-Balken (`EngineGate`: ab 500 rpm, die letzte Drehzahl gilt 12 s,
 *   `EngineGate.VOLTAGE_SETTLE_MS`). Ohne sie waere das Minimum fast immer der Anlasser-Einbruch (bis 7,8 V gesehen) oder die Spannung bei „Zuendung an“ (Median 11,9 V) — die Wirkung von
 *   Heizgriffen und Luefter (11,6 bis 12,8 V) laege nie vorn. 0 V und Werte unter 0 gelten als „keine Antwort“, nicht als leere Batterie.
 * - **Das Tor wird mit jeder Zeile fortgeschrieben,** nicht nur mit der Spannung: Kuehlmittel und Drehzahl stehen in jedem Zyklus, so faellt ein Motor, dessen Drehzahl 12 s fehlt, im selben
 *   Takt auf wie in der Live-Anzeige. Die Zeit ist die der Zeile (Wanduhr der Aufzeichnung); springt die Uhr waehrend der Fahrt, ist das Tor so ungenau wie die Zeitstempel.
 */
internal class SessionStatsAccumulator {

    private val engine = EngineGate()
    private var coolantMin = Float.POSITIVE_INFINITY
    private var coolantMax = Float.NEGATIVE_INFINITY
    private var voltageMin = Float.POSITIVE_INFINITY
    private var voltageMax = Float.NEGATIVE_INFINITY

    fun add(timestamp: Long, pidId: String, value: Float) {
        // Jede Zeile der drei Groessen schreibt das Tor fort; nur die Drehzahl liefert eine neue Drehzahl.
        engine.update(timestamp, if (pidId == PidRegistry.RPM.id) value else null)
        when (pidId) {
            PidRegistry.COOLANT_TEMP.id -> if (value.isFinite()) {
                coolantMin = minOf(coolantMin, value)
                coolantMax = maxOf(coolantMax, value)
            }
            PidRegistry.CONTROL_MODULE_VOLTAGE.id ->
                if (value.isFinite() && value > 0f && engine.isRunning() && engine.runningForMs(timestamp) >= EngineGate.VOLTAGE_SETTLE_MS) {
                    voltageMin = minOf(voltageMin, value)
                    voltageMax = maxOf(voltageMax, value)
                }
        }
    }

    fun result(): SessionStats = SessionStats(
        coolant = coolantMin.takeIf { it <= coolantMax }?.let { MinMax(it, coolantMax) },
        voltage = voltageMin.takeIf { it <= voltageMax }?.let { MinMax(it, voltageMax) },
    )
}
