package de.adv990de.obd2.alarm

/**
 * Läuft der Motor? Ohne laufenden Motor wäre jede Zündung-an-Phase ein Spannungsalarm (Median 11,9 V) und das Kühlmittel steigt nach dem Abstellen im Stand. Der Motor gilt als laufend ab
 * [RUNNING_MIN_RPM] (wie die Ganganzeige); die letzte Drehzahl wird [RPM_HOLD_MS] gehalten, danach gilt er als aus (kein Wert = kein Beleg).
 *
 * Das Abstellen braucht diese Frist nicht eng: ein Absterben meldet 0 rpm (unter [RUNNING_MIN_RPM], das Tor schließt sofort), und eine ausgeschaltete Zündung liefert gar keine Zyklen mehr
 * (nach 10 s „veraltet“, nach weiteren 20 s Schonfrist — `AlarmStaleGrace` — setzt das die Alarme zurück und schließt das Tor über `AlarmTracker.onSessionEnded`; die Sitzung selbst endet damit nicht).
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

        /**
         * Wie lange die letzte Drehzahl ohne neue gilt. **Muss über Zeitlimit + einem langsamen Zyklus liegen:** ein PID, der ins Lese-Zeitlimit des ELM (5 s) läuft, wird übersprungen, der Zyklus
         * kommt trotzdem — rund 0,5 + 5 + 0,6 s nach dem vorigen und **ohne** Drehzahl (eine einzelne langsame Antwort gilt als normal). Mit den früheren 5 s zählte der Motor dann als aus:
         * `AlarmTracker` setzte Kühlmittel und Spannung zurück, der Alarm erlosch samt Benachrichtigung, die Quittierung fiel weg, und gleich darauf klingelte derselbe Alarm erneut
         * (die Spannung mit neuen 15 s Einschwingzeit). Ein einzelnes Ausreißer-Zyklus darf keinen Lauf zurücksetzen.
         * 12 s lassen ein Zeitlimit samt langsamem Zyklus durch; erst zwei Zeitlimits in Folge (rund 12,6 s) gelten als Motor aus. **Preis:** der Fall „Drehzahl fehlt, während andere Werte weiter
         * eintreffen“ (z. B. ein Steuergerät, das die Drehzahl nicht mehr beantwortet) wird erst nach 12 statt 5 s erkannt — das Abstellen selbst nicht (s. Klasse).
         */
        const val RPM_HOLD_MS = 12_000L

        /** Einschwingzeit der Spannung nach dem Motorstart: die Auswertung blendete 10 s Startphase aus, 15 s lassen Reserve. */
        const val VOLTAGE_SETTLE_MS = 15_000L
    }
}
