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
 *   - **Motor aus (vor dem Start, Nutzerwunsch 2026-10-19):** der Balken zeigt schon die Spannung, mit `engineRunning = false`. Gerechnet wird nichts anderes:
 *     dieselbe Glättung, dieselben Grenzen, dieselbe Hysterese. Nur die Anzeige ändert sich; Alarme, Min/Max je Fahrt und die Aufzeichnung der Fahrt sind unberührt (sie haben ihre eigenen Regeln).
 *   - **Jeder Wechsel beginnt das Fenster neu** (Start und Abstellen): die Werte des einen Stands gehören nicht in das Mittel des anderen — Zündung-an-Werte (≈ 12 V) nicht in den laufenden Balken, die 13,6 V des
 *     laufenden Motors nicht in den Stand nach dem Abstellen.
 * - **Keine Lücke beim Wechsel (Nutzerwunsch 2026-10-19):** die Probe des Zyklus, der den Wechsel meldet, zählt sofort, und der Balken zeigt ab der ersten Probe etwas ([fresh]); bis das Fenster drei Proben hat, ist der
 *   Wert das Mittel der Proben seit dem Wechsel. Vorher blieb der Balken nach dem Start rund 18 s leer (15 s Einschwingzeit, dann drei Proben) und nach dem Abstellen, bis drei neue Proben da waren. **Preis:** nach dem Start
 *   steht zuerst der echte, noch niedrige Wert (die Lichtmaschine läuft hoch, ein Rest des Anlasser-Einbruchs kann sichtbar sein), und die ersten Werte bleiben bis zu 10 s im Mittel — der Balken klettert über einige Sekunden.
 *   Die Einschwingzeit [EngineGate.VOLTAGE_SETTLE_MS] gilt nur noch für Alarme und Min/Max, nicht mehr für diese Anzeige.
 * - **Nach dem ersten Verbinden** (kein Wechsel) bleibt es bei drei Proben, bevor der Balken erscheint; erreicht ein Fenster drei Proben, gilt die Regel auch danach wieder (leert sich das Fenster, weil die Spannung nicht mehr
 *   antwortet, verschwindet der Balken). Ein Verbinden bei laufendem Motor zählt als Wechsel: der Balken steht ab der ersten Probe.
 * - **Glättung:** Mittel der Proben der letzten [TREND_WINDOW_MS], frühestens ab [TREND_MIN_SAMPLES] (außer direkt nach einem Wechsel, s. o.). In den Daten springt die Spannung in 5,8 % der Zyklen um ≥ 0,4 V nach unten (Regler);
 *   ein Einzelwert hätte den Balken zucken lassen.
 * - **Nur echte Zyklen:** ein GPS-Zwischenupdate (`isObdCycle = false`) wiederholt die alten OBD-Werte — es verändert weder das Fenster noch das Motor-Tor (sonst hielte es einen
 *   abgestellten Motor am Leben), und es zählt keine Probe doppelt. Zusätzlich zählt jeder Zeitstempel einmal (`!=`, nicht `>`: springt die Wanduhr zurück, gehen neue Proben nicht verloren).
 *
 * [now] ist die monotone Uhr des Aufrufers (im ViewModel `SystemClock.elapsedRealtime()`): Fenster und Motor-Tor rechnen Dauern, die nicht springen dürfen.
 */
internal class VoltageTrendTracker(private val now: () -> Long) {

    private class Sample(val atMs: Long, val volts: Float)

    private val engine = EngineGate()
    private var wasRunning = false

    /** Das Fenster wurde durch einen Wechsel neu begonnen und hat noch keine [TREND_MIN_SAMPLES] Proben erreicht: bis dahin zeigt schon die erste Probe den Balken. */
    private var fresh = false
    private val samples = ArrayDeque<Sample>()
    private var lastTimestamp: Long? = null
    private var state: ChargeState? = null
    private var current: VoltageTrend? = null

    /** Der Trend nach diesem Zyklus, oder null (noch keine Probe seit einem Wechsel; nach dem ersten Verbinden zu wenige Proben). Ein Ereignis, das kein OBD-Zyklus ist, liefert den letzten Stand unverändert. */
    fun update(values: List<SensorValue>, isObdCycle: Boolean): VoltageTrend? {
        if (!isObdCycle) return current
        val nowMs = now()
        // `null`, wenn der Zyklus keine Drehzahl hatte: das Tor hält die letzte noch 12 s (ein einzelnes Zeitlimit setzt den Lauf nicht zurück).
        engine.update(nowMs, values.firstOrNull { it.pid.id == PidRegistry.RPM.id }?.value)
        val running = engine.isRunning()
        // Start und Abstellen beginnen das Fenster neu: die Proben des einen Stands gehören nicht in das Mittel des anderen (Zündung an ≈ 12 V, Anlasser, laufend 13,6 V).
        // Die Probe dieses Zyklus kommt gleich danach hinein und der Balken zeigt sie sofort (`fresh`): kein leerer Balken zwischen den Ständen.
        if (running != wasRunning) {
            clearWindow()
            wasRunning = running
            fresh = true
        }
        values.firstOrNull { it.pid.id == PidRegistry.CONTROL_MODULE_VOLTAGE.id }?.let { sample ->
            if (sample.timestamp != lastTimestamp) {
                lastTimestamp = sample.timestamp
                // 0 V heißt "keine Antwort" (Platzhalter), nicht "Batterie leer".
                if (sample.value.isFinite() && sample.value > 0f) {
                    samples.addLast(Sample(nowMs, sample.value))
                }
            }
        }
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > TREND_WINDOW_MS) samples.removeFirst()

        if (samples.size >= TREND_MIN_SAMPLES) fresh = false
        val needed = if (fresh) 1 else TREND_MIN_SAMPLES
        if (samples.size < needed) {
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
        fresh = false
        lastTimestamp = null
        clearWindow()
    }

    private fun clearWindow() {
        samples.clear()
        state = null
        current = null
    }
}
