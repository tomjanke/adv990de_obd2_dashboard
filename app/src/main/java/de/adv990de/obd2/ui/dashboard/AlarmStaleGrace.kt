package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.AlarmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die Schonfrist der Zahlen-Alarme nach [SessionEvent.Stale]: sie enden nicht sofort, sondern erst nach [GRACE_MS] ohne Werte. Ein Zyklus, in dem zwei PIDs ins Lese-Zeitlimit laufen
 * (rund 10,5 s), loest "veraltet" aus ([OBD_STALE_AFTER_MS] = 10 s); ein sofortiges Ende loeschte dabei Alarm, Quittierung und Einschwingzeit und liesse denselben Alarm nach seiner
 * Dauer erneut klingeln. Kommt vor dem Ablauf ein echter OBD-Zyklus, ist nichts passiert ([cancel]); bleiben die Werte weg (Zuendung aus), endet der Alarm wie bisher
 * ([AlarmController.onSessionEnded]) — nur [GRACE_MS] spaeter. Waehrend der Frist zeigt das Alarmband keine Zahl mehr (`alarmBannerShowsValue`: nur in der Live-Phase) — eine bis zu 30 s alte
 * Zahl neben leeren Kacheln waere falsch —, nennt aber weiter die Art; Kacheln und das Band „Keine aktuellen Werte“ gelten sofort.
 *
 * Gehoert dem `DashboardViewModel` ([scope] = sein `viewModelScope`): mit ihm endet auch ein laufender Timer.
 */
internal class AlarmStaleGrace(
    private val scope: CoroutineScope,
    private val controller: AlarmController,
    private val graceMs: Long = GRACE_MS,
) {
    private var job: Job? = null

    /**
     * Beginnt die Schonfrist. Laeuft schon eine, bleibt ihre Frist: die Werte fehlen seit dem ERSTEN "veraltet" (ein zweites kommt nur ueber einen Neustart der Sitzung, etwa einen
     * Slot-Wechsel), das Ende soll dadurch nicht weiter nach hinten rutschen.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(graceMs)
            // Ab hier nicht mehr abbrechbar: ein `cancel()`/`start()` mitten im Schreiben der Log-Zeile „Alarm cleared“ verloere sie und das Zuruecksetzen der Fehler-Merker.
            withContext(NonCancellable) { controller.onSessionEnded() }
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
