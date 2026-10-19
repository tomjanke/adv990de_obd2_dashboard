package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.EngineGate
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue

/**
 * Die geglättete Spannung und ihr Zustand über die Zyklen einer Verbindung (Lade-/Entlade-Balken, Entwurf `docs/superpowers/archive/specs/2026-10-19-voltage-gauge-design.md`).
 * Herausgelöst aus `DashboardViewModel` nach dem Muster von `GearTracker` und `OilBarTracker`; die reine Rechnung steht in `VoltageTrend.kt`.
 *
 * - **Zwei Stände, nach dem Motor:** ob der Motor läuft, entscheidet dasselbe [EngineGate] wie bei den Alarmen (≥ 500 rpm, die letzte Drehzahl gilt 12 s).
 *   - **Motor läuft:** „lädt“ und „entlädt“ sind Aussagen über die Lichtmaschine ([VoltageTrend.engineRunning] = true).
 *   - **Motor aus (vor dem Start, Nutzerwunsch 2026-10-19):** der Balken zeigt schon die Spannung, sobald es drei Proben gibt — ohne Einschwingzeit, mit `engineRunning = false`. Gerechnet wird nichts anderes:
 *     dieselbe Glättung, dieselben Grenzen, dieselbe Hysterese. Nur die Anzeige ändert sich; Alarme, Min/Max je Fahrt und die Aufzeichnung der Fahrt sind unberührt (sie haben ihre eigenen Regeln).
 *   - **Jeder Wechsel leert das Fenster** (Start und Abstellen): die Werte des einen Stands gehören nicht in das Mittel des anderen — Zündung-an-Werte (≈ 12 V) nicht in den laufenden Balken, die 13,6 V des
 *     laufenden Motors nicht in den Stand nach dem Abstellen. Nach dem Start bleibt der Balken damit bewusst leer, bis die Einschwingzeit vorbei ist und drei Proben da sind (rund 18 s).
 * - **Einschwingzeit (nur bei laufendem Motor):** erst [EngineGate.VOLTAGE_SETTLE_MS] (15 s) nach dem Start zählen Proben — der Einbruch des Anlassers (bis 7,8 V gesehen) würde das Mittel 10 s lang verfälschen.
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
    private var wasRunning = false
    private val samples = ArrayDeque<Sample>()
    private var lastTimestamp: Long? = null
    private var state: ChargeState? = null
    private var current: VoltageTrend? = null

    /** Der Trend nach diesem Zyklus, oder null (Einschwingzeit nach dem Start, zu wenige Proben). Ein Ereignis, das kein OBD-Zyklus ist, liefert den letzten Stand unverändert. */
    fun update(values: List<SensorValue>, isObdCycle: Boolean): VoltageTrend? {
        if (!isObdCycle) return current
        val nowMs = now()
        // `null`, wenn der Zyklus keine Drehzahl hatte: das Tor hält die letzte noch 12 s (ein einzelnes Zeitlimit setzt den Lauf nicht zurück).
        engine.update(nowMs, values.firstOrNull { it.pid.id == PidRegistry.RPM.id }?.value)
        val running = engine.isRunning()
        // Start und Abstellen leeren das Fenster: die Proben des einen Stands gehören nicht in das Mittel des anderen (Zündung an ≈ 12 V, Anlasser, laufend 13,6 V).
        if (running != wasRunning) {
            clearWindow()
            wasRunning = running
        }
        values.firstOrNull { it.pid.id == PidRegistry.CONTROL_MODULE_VOLTAGE.id }?.let { sample ->
            if (sample.timestamp != lastTimestamp) {
                lastTimestamp = sample.timestamp
                // 0 V heißt "keine Antwort" (Platzhalter), nicht "Batterie leer". Bei laufendem Motor zählt eine Probe erst nach der Einschwingzeit, bei stehendem Motor sofort.
                val settled = !running || engine.runningForMs(nowMs) >= EngineGate.VOLTAGE_SETTLE_MS
                if (sample.value.isFinite() && sample.value > 0f && settled) {
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
        return VoltageTrend(mean, next, engineRunning = running).also { current = it }
    }

    /** Eine neue Verbindung beginnt leer (Idle, Verlust, Fehler, „veraltet“) — wie Gang und Sparklines. */
    fun reset() {
        engine.reset()
        wasRunning = false
        lastTimestamp = null
        clearWindow()
    }

    private fun clearWindow() {
        samples.clear()
        state = null
        current = null
    }
}
