package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue

/**
 * Der Zustand aller Alarme, rein und ohne Android: bekommt je OBD-Zyklus die Messwerte (`onCycle`), je Auslesen die Fehlercodes (T2) und die Einstellungen, und sagt, welche
 * Alarme gerade bestehen ([active]). Die Anzeige und die Benachrichtigung entscheidet der `AlarmController`.
 */
class AlarmTracker {
    private var settings = AlarmSettings()
    private val engine = EngineGate()
    private var voltage = voltageAlarm(settings)
    private var coolant = coolantAlarm(settings)

    /**
     * Zeitpunkt der letzten echten Probe je Größe (nicht des Zyklus). Bleibt ein Wert länger als [SustainedRule.GAP_MS] aus, obwohl der Motor läuft,
     * erlischt der Alarm: sonst hielte die Regel den letzten Stand (z. B. 11,9 V), bis der Motor steht — die Regel selbst sieht keine Probe mehr, die ihre Lücke prüfen könnte.
     */
    private var lastVoltageAtMs: Long? = null
    private var lastCoolantAtMs: Long? = null

    fun updateSettings(new: AlarmSettings) {
        val old = settings
        settings = new
        if (new.voltageEnabled != old.voltageEnabled || new.voltageNoticeDeciV != old.voltageNoticeDeciV ||
            new.voltageWarningDeciV != old.voltageWarningDeciV
        ) {
            voltage = voltageAlarm(new)
            lastVoltageAtMs = null
        }
        if (new.coolantEnabled != old.coolantEnabled || new.coolantNoticeC != old.coolantNoticeC ||
            new.coolantWarningC != old.coolantWarningC
        ) {
            coolant = coolantAlarm(new)
            lastCoolantAtMs = null
        }
    }

    /** Ein OBD-Zyklus (nicht das GPS-Zwischenupdate): die Spannung steht nur in jedem 4. Zyklus drin, ein fehlender Wert ist keine Probe. */
    fun onCycle(nowMs: Long, values: List<SensorValue>) {
        engine.update(nowMs, values.valueOf(PidRegistry.RPM.id))
        if (settings.voltageEnabled) {
            val volts = values.valueOf(PidRegistry.CONTROL_MODULE_VOLTAGE.id)
            if (engine.runningForMs(nowMs) < EngineGate.VOLTAGE_SETTLE_MS) {
                voltage.reset()
                lastVoltageAtMs = null
            } else if (volts != null) {
                voltage.sample(nowMs, volts)
                lastVoltageAtMs = nowMs
            } else if (isStale(lastVoltageAtMs, nowMs)) {
                voltage.reset()
                lastVoltageAtMs = null
            }
        }
        if (settings.coolantEnabled) {
            val celsius = values.valueOf(PidRegistry.COOLANT_TEMP.id)
            if (!engine.isRunning()) {
                coolant.reset()
                lastCoolantAtMs = null
            } else if (celsius != null) {
                coolant.sample(nowMs, celsius)
                lastCoolantAtMs = nowMs
            } else if (isStale(lastCoolantAtMs, nowMs)) {
                coolant.reset()
                lastCoolantAtMs = null
            }
        }
    }

    /** Ende der Sitzung (keine Werte mehr): Spannung und Kühlmittel erlöschen. */
    fun onSessionEnded() {
        engine.reset()
        voltage.reset()
        coolant.reset()
        lastVoltageAtMs = null
        lastCoolantAtMs = null
    }

    /** Kein neuer Wert in diesem Zyklus: ist die letzte Probe länger als [SustainedRule.GAP_MS] her, gilt der Wert als verloren (`>`, exakt 30 s zählt noch als frisch). */
    private fun isStale(lastAtMs: Long?, nowMs: Long) = lastAtMs != null && nowMs - lastAtMs > SustainedRule.GAP_MS

    fun active(): List<Alarm> = buildList {
        if (settings.coolantEnabled) coolant.severity()?.let { add(Alarm(AlarmKind.COOLANT, it, coolant.lastValue)) }
        if (settings.voltageEnabled) voltage.severity()?.let { add(Alarm(AlarmKind.VOLTAGE, it, voltage.lastValue)) }
    }

    private fun List<SensorValue>.valueOf(id: String): Float? = firstOrNull { it.pid.id == id }?.value?.takeIf { it.isFinite() }
}
