package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Der Zustand der Fahrzeugwerte einer Sitzung (Spezifikation 2026-10-24): **da**, **eingefroren** (die Werte werden zurueckgehalten, [FrozenAction.Hold]) oder **keine
 * aktuellen Werte** (seit [OBD_STALE_AFTER_MS] kam kein Zyklus, der Adapter schweigt). Jeder Wechsel wird einmal ins App-Log und als Zeile in die Fahrt geschrieben —
 * die Luecke, die zurueckgehaltene Werte in der Aufzeichnung lassen, ist so erklaert.
 */
internal enum class ValuesState { OK, FROZEN, SILENT }

/**
 * Die Zahlen der Aufzeichnung (`VALUES_STATE`): **0 = Werte da, 1 = eingefroren, 2 = keine aktuellen Werte.** Sie stehen als Wert in Datenbank und CSV: **nie aendern**,
 * die Auswertung liest sie. Das `when` ist ohne `else`: ein neuer Zustand zwingt zu einer eigenen Zahl.
 */
internal const val VALUES_STATE_RECORD_OK = 0f
internal const val VALUES_STATE_RECORD_FROZEN = 1f
internal const val VALUES_STATE_RECORD_SILENT = 2f

internal fun valuesStateRecordValue(state: ValuesState): Float = when (state) {
    ValuesState.OK -> VALUES_STATE_RECORD_OK
    ValuesState.FROZEN -> VALUES_STATE_RECORD_FROZEN
    ValuesState.SILENT -> VALUES_STATE_RECORD_SILENT
}

/** Die Aufzeichnungszeile zu einem Wechsel: [timestamp] ist der Zeitpunkt des Wechsels, die Herkunft [ValueSource.DERIVED] (berechnet, nicht gemessen). */
internal fun valuesStateSensorValue(state: ValuesState, timestamp: Long = System.currentTimeMillis()): SensorValue =
    SensorValue(DerivedPids.VALUES_STATE, valuesStateRecordValue(state), timestamp, ValueSource.DERIVED)

/** Ein Wechsel des Zustands: woher, wohin und wie lange der vorige Zustand gedauert hat (fuer die Log-Zeile). */
internal data class ValuesStateChange(val from: ValuesState, val to: ValuesState, val previousFor: Duration)

/**
 * Haelt den Zustand der Fahrzeugwerte einer Sitzung und meldet nur **Wechsel** ([enter] liefert `null`, wenn es der Zustand schon ist) — so entsteht je Wechsel eine
 * Log-Zeile und eine Zeile in der Fahrt, nie eine je Zyklus. Die Zeit kommt als [TimeSource] (Tests: `TestTimeSource`).
 */
internal class ValuesStateTracker(private val timeSource: TimeSource) {
    var state: ValuesState = ValuesState.OK
        private set
    private var since: TimeMark = timeSource.markNow()

    /** Wechselt in [to]; `null`, wenn der Zustand schon [to] ist. */
    fun enter(to: ValuesState): ValuesStateChange? {
        if (to == state) return null
        val change = ValuesStateChange(from = state, to = to, previousFor = since.elapsedNow())
        state = to
        since = timeSource.markNow()
        return change
    }

    /** Eine neue Sitzung beginnt mit "Werte da"; ein Wechsel wird dabei nicht gemeldet. */
    fun reset() {
        state = ValuesState.OK
        since = timeSource.markNow()
    }

    /** Wie lange der laufende Zustand schon dauert. */
    fun lasted(): Duration = since.elapsedNow()
}
