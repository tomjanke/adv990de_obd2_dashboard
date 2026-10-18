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

    fun updateSettings(new: AlarmSettings) {
        val old = settings
        settings = new
        if (new.voltageEnabled != old.voltageEnabled || new.voltageNoticeDeciV != old.voltageNoticeDeciV ||
            new.voltageWarningDeciV != old.voltageWarningDeciV
        ) voltage = voltageAlarm(new)
        if (new.coolantEnabled != old.coolantEnabled || new.coolantNoticeC != old.coolantNoticeC ||
            new.coolantWarningC != old.coolantWarningC
        ) coolant = coolantAlarm(new)
    }

    /** Ein OBD-Zyklus (nicht das GPS-Zwischenupdate): die Spannung steht nur in jedem 4. Zyklus drin, ein fehlender Wert ist keine Probe. */
    fun onCycle(nowMs: Long, values: List<SensorValue>) {
        engine.update(nowMs, values.valueOf(PidRegistry.RPM.id))
        if (settings.voltageEnabled) {
            val volts = values.valueOf(PidRegistry.CONTROL_MODULE_VOLTAGE.id)
            if (engine.runningForMs(nowMs) < EngineGate.VOLTAGE_SETTLE_MS) voltage.reset()
            else if (volts != null) voltage.sample(nowMs, volts)
        }
        if (settings.coolantEnabled) {
            val celsius = values.valueOf(PidRegistry.COOLANT_TEMP.id)
            if (!engine.isRunning()) coolant.reset()
            else if (celsius != null) coolant.sample(nowMs, celsius)
        }
    }

    /** Ende der Sitzung (keine Werte mehr): Spannung und Kühlmittel erlöschen. */
    fun onSessionEnded() {
        engine.reset()
        voltage.reset()
        coolant.reset()
    }

    fun active(): List<Alarm> = buildList {
        if (settings.coolantEnabled) coolant.severity()?.let { add(Alarm(AlarmKind.COOLANT, it, coolant.lastValue)) }
        if (settings.voltageEnabled) voltage.severity()?.let { add(Alarm(AlarmKind.VOLTAGE, it, voltage.lastValue)) }
    }

    private fun List<SensorValue>.valueOf(id: String): Float? = firstOrNull { it.pid.id == id }?.value?.takeIf { it.isFinite() }
}
