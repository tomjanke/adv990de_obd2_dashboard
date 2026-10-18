package de.adv990de.obd2.alarm

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject

/**
 * Treibt den [AlarmTracker] und sagt [AlarmNotifier] und [AlarmLog], was sich geändert hat. Das ViewModel besitzt genau einen (`@Inject`, **kein** `@Singleton`: die Alarme leben
 * so lange wie die Sitzung, also wie das ViewModel).
 *
 * **Gemeldet wird nur eine Änderung** — der Notifier alarmiert bei jedem `show` erneut (eine Benachrichtigung mit derselben ID weckt Ton und Vibration noch einmal), ein
 * `show` je Probe wäre ein Dauerton. Deshalb vergleicht [publish] den Stand von [AlarmTracker.active] mit dem, den es zuletzt gemeldet hat (`shown`): `show` nur bei einer
 * neuen Art, einer höheren Stufe oder anderen Codes, nie bei einem neuen Wert auf gleicher Stufe und auch nicht, weil ein quittierter Zahlen-Alarm noch besteht (er ist nur
 * verborgen, `active()` kennt ihn weiter). Fällt die Stufe, ist das kein neuer Alarm — `shown` folgt dem Abfall, damit eine spätere Rückkehr der höheren Stufe wieder alarmiert
 * (dieselbe Regel wie die Quittierung im Tracker).
 *
 * **Nebenläufigkeit:** der Tracker ist nicht thread-sicher, und die Aufrufe kommen aus verschiedenen Coroutinen des ViewModels (Ereignis-Sammler, Einstellungs-Sammler,
 * Quittieren per Klick), dazu ist [AlarmLog.log] eine Suspendierungsstelle mitten in [publish]. Alle Einstiegspunkte laufen deshalb unter einem [Mutex]: ein Aufruf ist fertig,
 * bevor der nächste beginnt, und Benachrichtigungen und Log-Zeilen behalten ihre Reihenfolge. Innerhalb eines Aufrufs stehen Zustand, `alarms` und die Benachrichtigungen
 * **vor** dem ersten Schreiben ins Log (das Log ist die einzige Suspendierungsstelle): bricht ein Aufruf beim Schreiben ab (das ViewModel endet), ist der Zustand
 * stimmig, die Sperre frei, und es gehen höchstens Log-Zeilen verloren.
 */
class AlarmController @Inject constructor(
    private val notifier: AlarmNotifier,
    private val log: AlarmLog,
) {
    private val tracker = AlarmTracker()
    private val lock = Mutex()

    /** Der zuletzt gemeldete [AlarmTracker.active]-Stand, je Art. */
    private var shown: Map<AlarmKind, Alarm> = emptyMap()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())

    /** Die anzuzeigenden Alarme: nicht quittiert, Warnungen zuerst, dann nach Art ([AlarmTracker.visible]). */
    val alarms: StateFlow<List<Alarm>> = _alarms

    suspend fun updateSettings(settings: AlarmSettings) = lock.withLock {
        tracker.updateSettings(settings)
        publish()
    }

    /** Ein OBD-Zyklus (nicht das GPS-Zwischenupdate: das wiederholt die alten Werte und ließe die Dauer-Regeln allein mit der Uhr weiterlaufen). */
    suspend fun onCycle(nowMs: Long, values: List<SensorValue>) = lock.withLock {
        tracker.onCycle(nowMs, values)
        publish()
    }

    /** Ein Auslesen der Fehlercodes: [isBaseline] beim Verbinden, `null` = gescheitert. */
    suspend fun onFaultCodes(codes: List<DtcEntry>?, isBaseline: Boolean) = lock.withLock {
        tracker.onFaultRead(codes, isBaseline)
        publish()
    }

    /** Keine Werte mehr: Spannung und Kühlmittel erlöschen samt Benachrichtigung, der Fehler-Alarm bleibt (eine ungesehene Meldung soll nicht mit der Zündung verschwinden). */
    suspend fun onSessionEnded() = lock.withLock {
        tracker.onSessionEnded()
        publish()
    }

    /**
     * Quittieren (Tipp auf das Band): die Benachrichtigung der Art verschwindet, das Band auch. Ein Zahlen-Alarm besteht dabei weiter (nur verborgen) — seine Benachrichtigung
     * räumt erst dieser Aufruf ab, `publish()` sieht ihn nicht erlöschen. Ein Fehler-Alarm **endet** mit dem Quittieren: den räumt `publish()` über den Weg „Art erloschen“
     * ab (ein `clear`, nicht zwei) und schreibt dabei die INFO-Zeile „Alarm cleared“ — das Log schließt jedes „raised“ mit einem „cleared“ ab, der Alarm ist ja zu Ende.
     * Eine Zeile „acknowledged“ gibt es nicht, ein Quittieren ohne Alarm tut nichts.
     */
    suspend fun acknowledge(kind: AlarmKind) = lock.withLock {
        tracker.acknowledge(kind)
        if (tracker.active().any { it.kind == kind }) notifier.clear(kind)
        publish()
    }

    /** Muss unter [lock] laufen. Zustand und Benachrichtigungen zuerst, dann das Log (die einzige Suspendierungsstelle). */
    private suspend fun publish() {
        val now = tracker.active().associateBy { it.kind }
        val before = shown
        val ended = before.keys.filter { it !in now }
        val raised = now.values.filter { it.raisesOver(before[it.kind]) }
        shown = now
        _alarms.value = tracker.visible()
        ended.forEach { notifier.clear(it) }
        raised.forEach { notifier.show(it) }
        ended.forEach { log.log(LogLevel.INFO, "Alarm cleared: ${it.name}") }
        raised.forEach { log.log(LogLevel.WARN, "Alarm raised: ${it.describe()}") }
    }

    /** Neu, höhere Stufe oder andere Codes — ein neuer Wert allein und eine niedrigere Stufe nicht. */
    private fun Alarm.raisesOver(previous: Alarm?): Boolean =
        previous == null || severity > previous.severity || codes != previous.codes

    /** Art, Stufe und Wert bzw. Codes für das Log — ohne Locale (das Log ist kein Anzeigetext): Spannung mit einer Nachkommastelle, Kühlmittel in ganzen °C. */
    private fun Alarm.describe(): String {
        val detail = when (kind) {
            AlarmKind.VOLTAGE -> value?.let { String.format(Locale.ROOT, "%.1f", it) }
            AlarmKind.COOLANT -> value?.let { String.format(Locale.ROOT, "%.0f", it) }
            AlarmKind.FAULT_CODE -> codes.joinToString(", ")
        }
        return listOfNotNull(kind.name, severity.name, detail).joinToString(" ")
    }
}
