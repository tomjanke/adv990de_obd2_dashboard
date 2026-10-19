package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.EngineGate
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue

/**
 * Die geglättete Spannung und ihr Zustand über die Zyklen einer Verbindung (Lade-/Entlade-Balken, Entwurf `docs/superpowers/archive/specs/2026-10-19-voltage-gauge-design.md`).
 * Herausgelöst aus `DashboardViewModel` nach dem Muster von `GearTracker` und `OilBarTracker`; die reine Rechnung steht in `VoltageTrend.kt`.
 *
 * - **Nur bei laufendem Motor:** „lädt“ und „entlädt“ sind Aussagen über die Lichtmaschine. Ob der Motor läuft, entscheidet dasselbe [EngineGate] wie bei den Alarmen
 *   (≥ 500 rpm, die letzte Drehzahl gilt 12 s); ohne Motor ist das Fenster leer und es gibt keinen Trend.
 * - **Einschwingzeit:** erst [EngineGate.VOLTAGE_SETTLE_MS] (15 s) nach dem Start zählen Proben — der Einbruch des Anlassers (bis 7,8 V gesehen) würde das Mittel 10 s lang verfälschen.
 * - **Glättung:** Mittel der Proben der letzten [TREND_WINDOW_MS], frühestens ab [TREND_MIN_SAMPLES]. In den Daten springt die Spannung in 5,8 % der Zyklen um ≥ 0,4 V nach unten (Regler);
 *   ein Einzelwert hätte den Balken zucken lassen.
 * - **Nur echte Zyklen:** ein GPS-Zwischenupdate (`isObdCycle = false`) wiederholt die alten OBD-Werte — es verändert weder das Fenster noch das Motor-Tor (sonst hielte es einen
 *   abgestellten Motor am Leben), und es zählt keine Probe doppelt. Zusätzlich zählt jeder Zeitstempel einmal (`!=`, nicht `>`: springt die Wanduhr zurück, gehen neue Proben nicht verloren).
 *
 * [now] ist die monotone Uhr des Aufrufers (im ViewModel `SystemClock.elapsedRealtime()`): Fenster und Einschwingzeit rechnen Dauern, die nicht springen dürfen.
 */
internal class VoltageTrendTracker(private val now: () -> Long) {

    private class Sample(val atMs: Long, val volts: Float)

    private val engine = EngineGate()
    private val samples = ArrayDeque<Sample>()
    private var lastTimestamp: Long? = null
    private var state: ChargeState? = null
    private var current: VoltageTrend? = null

    /** Der Trend nach diesem Zyklus, oder null (kein Motor, Einschwingzeit, zu wenige Proben). Ein Ereignis, das kein OBD-Zyklus ist, liefert den letzten Stand unverändert. */
    fun update(values: List<SensorValue>, isObdCycle: Boolean): VoltageTrend? {
        if (!isObdCycle) return current
        val nowMs = now()
        // `null`, wenn der Zyklus keine Drehzahl hatte: das Tor hält die letzte noch 12 s (ein einzelnes Zeitlimit setzt den Lauf nicht zurück).
        engine.update(nowMs, values.firstOrNull { it.pid.id == PidRegistry.RPM.id }?.value)
        if (!engine.isRunning()) {
            clearWindow()
            return null
        }
        values.firstOrNull { it.pid.id == PidRegistry.CONTROL_MODULE_VOLTAGE.id }?.let { sample ->
            if (sample.timestamp != lastTimestamp) {
                lastTimestamp = sample.timestamp
                // 0 V heißt "keine Antwort" (Platzhalter), nicht "Batterie leer".
                if (sample.value.isFinite() && sample.value > 0f && engine.runningForMs(nowMs) >= EngineGate.VOLTAGE_SETTLE_MS) {
                    samples.addLast(Sample(nowMs, sample.value))
                }
            }
        }
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > TREND_WINDOW_MS) samples.removeFirst()

        if (samples.size < TREND_MIN_SAMPLES) {
            state = null
            current = null
            return null
        }
        // In Double gemittelt und erst dann auf Float gerundet: elf gleiche Werte ergeben so genau diesen Wert, nicht ein Float-Rauschen an einer Grenze.
        val mean = (samples.sumOf { it.volts.toDouble() } / samples.size).toFloat()
        val next = chargeState(mean, previous = state)
        state = next
        return VoltageTrend(mean, next).also { current = it }
    }

    /** Eine neue Verbindung beginnt leer (Idle, Verlust, Fehler, „veraltet“) — wie Gang und Sparklines. */
    fun reset() {
        engine.reset()
        lastTimestamp = null
        clearWindow()
    }

    private fun clearWindow() {
        samples.clear()
        state = null
        current = null
    }
}
