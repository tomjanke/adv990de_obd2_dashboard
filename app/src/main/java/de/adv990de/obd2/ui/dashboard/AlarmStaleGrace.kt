package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.AlarmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Die Schonfrist der Zahlen-Alarme nach [SessionEvent.Stale]: sie enden nicht sofort, sondern erst nach [GRACE_MS] ohne Werte. Ein Zyklus, in dem zwei PIDs ins Lese-Zeitlimit laufen
 * (rund 10,5 s), loest "veraltet" aus ([OBD_STALE_AFTER_MS] = 10 s); ein sofortiges Ende loeschte dabei Alarm, Quittierung und Einschwingzeit und liesse denselben Alarm nach seiner
 * Dauer erneut klingeln. Kommt vor dem Ablauf ein echter OBD-Zyklus, ist nichts passiert ([cancel]); bleiben die Werte weg (Zuendung aus), endet der Alarm wie bisher
 * ([AlarmController.onSessionEnded]) — nur [GRACE_MS] spaeter. Die Anzeige selbst ist davon unberuehrt: Kacheln und das Band „Keine aktuellen Werte“ gelten sofort.
 *
 * Gehoert dem `DashboardViewModel` ([scope] = sein `viewModelScope`): mit ihm endet auch ein laufender Timer.
 */
internal class AlarmStaleGrace(
    private val scope: CoroutineScope,
    private val controller: AlarmController,
    private val graceMs: Long = GRACE_MS,
) {
    private var job: Job? = null

    /** Beginnt die Schonfrist (eine laufende wird neu gestartet). */
    fun start() {
        job?.cancel()
        job = scope.launch {
            delay(graceMs)
            controller.onSessionEnded()
        }
    }

    /** Die Werte sind zurueck oder die Sitzung endet auf anderem Weg: keine Schonfrist mehr. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    internal companion object {
        /** Zusammen mit den 10 s bis "veraltet" 30 s ohne Werte — die Luecke, ab der die Regeln selbst zuruecksetzen (`SustainedRule.GAP_MS`). */
        const val GRACE_MS = 20_000L
    }
}
