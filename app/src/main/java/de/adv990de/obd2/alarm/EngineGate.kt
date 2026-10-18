package de.adv990de.obd2.alarm

/**
 * Läuft der Motor? Ohne laufenden Motor wäre jede Zündung-an-Phase ein Spannungsalarm (Median 11,9 V) und das Kühlmittel steigt nach dem Abstellen im Stand. Der Motor gilt als laufend ab
 * [RUNNING_MIN_RPM] (wie die Ganganzeige); die letzte Drehzahl wird [RPM_HOLD_MS] gehalten, danach gilt er als aus (kein Wert = kein Beleg).
 */
internal class EngineGate {
    private var lastRpm: Float? = null
    private var lastRpmAtMs = 0L
    private var runningSinceMs: Long? = null

    fun update(nowMs: Long, rpm: Float?) {
        if (rpm != null && rpm.isFinite()) {
            lastRpm = rpm
            lastRpmAtMs = nowMs
        }
        val held = lastRpm
        val running = held != null && held >= RUNNING_MIN_RPM && nowMs - lastRpmAtMs <= RPM_HOLD_MS
        runningSinceMs = if (running) runningSinceMs ?: nowMs else null
    }

    fun isRunning(): Boolean = runningSinceMs != null

    fun runningForMs(nowMs: Long): Long = runningSinceMs?.let { nowMs - it } ?: 0L

    fun reset() {
        lastRpm = null
        lastRpmAtMs = 0L
        runningSinceMs = null
    }

    internal companion object {
        const val RUNNING_MIN_RPM = 500f
        const val RPM_HOLD_MS = 5_000L
        /** Einschwingzeit der Spannung nach dem Motorstart: die Auswertung blendete 10 s Startphase aus, 15 s lassen Reserve. */
        const val VOLTAGE_SETTLE_MS = 15_000L
    }
}
