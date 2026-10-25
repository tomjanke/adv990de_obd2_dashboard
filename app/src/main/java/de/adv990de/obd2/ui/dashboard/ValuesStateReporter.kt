package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.model.SensorValue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Meldet den Zustand der Fahrzeugwerte einer Sitzung ([ValuesState]): bei einem **Wechsel** (nie bei demselben Zustand) eine Log-Zeile ([log], englisch wie die uebrigen) und eine Zeile
 * `VALUES_STATE` in die Fahrt ([record], das ist `ReadingsPipeline.recordDerived`). Herausgeloest aus der `ReadingsPipeline`; der Zustand selbst ist der [ValuesStateTracker].
 */
internal class ValuesStateReporter(
    private val log: suspend (LogLevel, String) -> Unit,
    private val record: suspend (List<SensorValue>) -> Unit,
) {
    private var tracker = ValuesStateTracker(TimeSource.Monotonic)

    /** Der laufende Zustand der Sitzung. */
    val state: ValuesState get() = tracker.state

    /** Wie lange der laufende Zustand schon dauert. */
    fun lasted(): Duration = tracker.lasted()

    /**
     * Eine neue Sitzung beginnt mit "Werte da"; ein Wechsel wird dabei nicht gemeldet. Der Tracker wird neu gebaut, damit er die Uhr [timeSource] nutzt, die Tests vorher setzen
     * (`ReadingsPipeline.frozenTimeSource`).
     */
    fun begin(timeSource: TimeSource) {
        tracker = ValuesStateTracker(timeSource)
    }

    /**
     * Wechselt den Zustand der Fahrzeugwerte; bei einem **Wechsel** (nie bei demselben Zustand) eine Log-Zeile und eine Zeile `VALUES_STATE` in die Fahrt. Liefert, ob es ein Wechsel war.
     * [held] ist der Zyklus, der die Sperre ausloeste (er steht im Log: so ist der eingefrorene Wert belegt).
     */
    suspend fun enter(to: ValuesState, timestamp: Long, held: List<SensorValue> = emptyList()): Boolean {
        val change = tracker.enter(to) ?: return false
        val seconds = change.previousFor.inWholeSeconds
        when (to) {
            ValuesState.FROZEN -> log(
                LogLevel.WARN,
                "Values still frozen after the reconnect: holding them back (not shown, not recorded; GPS speed keeps being recorded). Held cycle: ${held.joinToString(" ") { "${it.pid.id}=${valueText(it.value)}" }}",
            )
            ValuesState.SILENT -> log(LogLevel.INFO, "No fresh values for ${OBD_STALE_AFTER_MS / 1000} s (stale)")
            ValuesState.OK -> log(
                LogLevel.INFO,
                if (change.from == ValuesState.FROZEN) "Values are moving again after $seconds s: showing and recording resume" else "Fresh values again after $seconds s",
            )
        }
        record(listOf(valuesStateSensorValue(to, timestamp)))
        return true
    }

    /** Ganze Zahlen ohne ".0" ("1612", nicht "1612.0"), sonst wie [Float.toString] ("13.7"). */
    private fun valueText(value: Float): String =
        if (value.isFinite() && value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}
