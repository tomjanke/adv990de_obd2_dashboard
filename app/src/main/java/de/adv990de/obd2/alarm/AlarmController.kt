package de.adv990de.obd2.alarm

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
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
 *
 * **Beiwerk darf die Live-Anzeige nie beenden:** die Aufrufe kommen aus dem Ereignis-Sammler des ViewModels, eine Ausnahme von hier wäre ein Absturz während der Fahrt oder ein
 * toter Sammler (eingefrorenes Dashboard). Deshalb fängt der Controller jede [Exception] ab — nur die [CancellationException] nicht, sie gehört dem Aufrufer:
 * - **Benachrichtigung:** jeder einzelne `show`/`clear` für sich. Ein Fehler überspringt die übrigen Aufrufe und Log-Zeilen desselben [publish] nicht. **Kein Rückgängigmachen:** `shown`
 *   und `alarms` stehen schon fest, eine misslungene Benachrichtigung ist verloren (kein Wiederholen, sonst wäre jede Probe ein neuer Versuch), das Band zeigt den Alarm weiter.
 * - **Auswertung:** jeder Aufruf des Trackers. Wirft er mitten in einem Einstiegspunkt, läuft [publish] trotzdem auf dem Stand, den der Tracker danach hat (er kann nach einer
 *   Ausnahme halb fortgeschrieben sein; die nächste Probe holt auf, und `shown` wird nur aus einem vollständig gelesenen Stand fortgeschrieben). Lässt sich der Stand gar nicht lesen
 *   (`active()`/`visible()` werfen), entfällt [publish]: `shown` und `alarms` bleiben, wie sie waren, und der nächste Aufruf holt nach.
 * - **Ein Log-Eintrag je Quelle und Sitzung** (WARN „Alarm notification failed: …“ bzw. „Alarm evaluation failed: …“): ein dauerhaft werfender Notifier würde das App-Log sonst
 *   im Sekundentakt füllen (ein Zyklus je Sekunde). Die Sperre dafür ist je Quelle ein Flag, das [onSessionEnded] am Ende zurücksetzt.
 */
class AlarmController internal constructor(
    private val notifier: AlarmNotifier,
    private val log: AlarmLog,
    private val tracker: AlarmTracker,
) {
    @Inject
    constructor(notifier: AlarmNotifier, log: AlarmLog) : this(notifier, log, AlarmTracker())

    private val lock = Mutex()

    /**
     * Der zuletzt gemeldete [AlarmTracker.active]-Stand, je Art. `@Volatile`, weil [release] ihn ohne die Sperre liest (beim Abbau des ViewModels); der Verweis wird immer
     * als Ganzes ersetzt.
     */
    @Volatile
    private var shown: Map<AlarmKind, Alarm> = emptyMap()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())

    /** Die anzuzeigenden Alarme: nicht quittiert, Warnungen zuerst, dann nach Art ([AlarmTracker.visible]). */
    val alarms: StateFlow<List<Alarm>> = _alarms

    // Ob in dieser Sitzung schon ein Fehler der Quelle ins Log ging (nur unter [lock] gelesen und geschrieben).
    private var notificationFailureLogged = false
    private var evaluationFailureLogged = false

    suspend fun updateSettings(settings: AlarmSettings) = transact { evaluating { tracker.updateSettings(settings) } }

    /** Ein OBD-Zyklus (nicht das GPS-Zwischenupdate: das wiederholt die alten Werte und ließe die Dauer-Regeln allein mit der Uhr weiterlaufen). */
    suspend fun onCycle(nowMs: Long, values: List<SensorValue>) = transact { evaluating { tracker.onCycle(nowMs, values) } }

    /** Ein Auslesen der Fehlercodes: [isBaseline] beim Verbinden, `null` = gescheitert. */
    suspend fun onFaultCodes(codes: List<DtcEntry>?, isBaseline: Boolean) = transact { evaluating { tracker.onFaultRead(codes, isBaseline) } }

    /**
     * Keine Werte mehr: Spannung und Kühlmittel erlöschen samt Benachrichtigung, der Fehler-Alarm bleibt (eine ungesehene Meldung soll nicht mit der Zündung verschwinden).
     * Das Ende der Sitzung setzt auch die Sperren für die Log-Einträge der Fehler zurück — nach den Aufrufen dieses Endes, die noch zur alten Sitzung gehören.
     */
    suspend fun onSessionEnded() = transact(endsSession = true) { evaluating { tracker.onSessionEnded() } }

    /**
     * Quittieren (Tipp auf das Band): die Benachrichtigung der Art verschwindet, das Band auch. Ein Zahlen-Alarm besteht dabei weiter (nur verborgen) — seine Benachrichtigung
     * räumt erst dieser Aufruf ab, `publish()` sieht ihn nicht erlöschen. Ein Fehler-Alarm **endet** mit dem Quittieren: den räumt `publish()` über den Weg „Art erloschen“
     * ab (ein `clear`, nicht zwei) und schreibt dabei die INFO-Zeile „Alarm cleared“ — das Log schließt jedes „raised“ mit einem „cleared“ ab, der Alarm ist ja zu Ende.
     * Eine Zeile „acknowledged“ gibt es nicht, ein Quittieren ohne Alarm tut nichts.
     */
    suspend fun acknowledge(kind: AlarmKind) = transact {
        val stillActive = evaluating {
            tracker.acknowledge(kind)
            tracker.active().any { it.kind == kind }
        } ?: false
        if (stillActive) notifying { notifier.clear(kind) }
    }

    /**
     * Das ViewModel endet (Aufgabe weggewischt): ohne Sitzung gibt es keinen Spannungs- und Kühlmittelalarm mehr, ihre Benachrichtigungen sollen nicht im Schatten stehen
     * bleiben, obwohl die Fahrt vorbei ist. Der Fehler-Alarm bleibt bewusst stehen (er überlebt das Ende der Sitzung bis zum Quittieren).
     *
     * Nicht suspendierend und **ohne die Sperre**: der `viewModelScope` ist beim Aufruf von `onCleared` schon abgebrochen, es läuft nichts mehr nebenher. Ein Fehler des Notifiers wird
     * verschluckt (ohne Suspendierung gibt es kein Log, und beim Abbau gibt es keinen Aufrufer, der etwas damit anfangen könnte); die [CancellationException] auch hier nicht.
     * Der Zustand bleibt unberührt.
     */
    fun release() {
        shown.keys.filter { it != AlarmKind.FAULT_CODE }.forEach { kind -> catching { notifier.clear(kind) } }
    }

    /** Die Fehler eines Aufrufs, je Quelle der erste; [publish] und [report] reichen sie durch. */
    private class Failures {
        var evaluation: Exception? = null
        var notification: Exception? = null

        /** Eine Auswertung des Trackers; scheitert sie, merkt sich [evaluation] den Fehler und das Ergebnis ist `null`. */
        fun <T> evaluating(block: () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (evaluation == null) evaluation = e
            null
        }

        /** Ein Aufruf des Notifiers; ein Fehler wird gemerkt und der nächste Aufruf läuft weiter. */
        fun notifying(block: () -> Unit) {
            catching(block)?.let { if (notification == null) notification = it }
        }
    }

    /** Ein Einstiegspunkt: unter der Sperre erst [block] (Tracker, ggf. Notifier), dann [publish], dann die Einträge der Fehler ([report]). */
    private suspend fun transact(endsSession: Boolean = false, block: Failures.() -> Unit) = lock.withLock {
        val failures = Failures()
        failures.block()
        publish(failures)
        report(failures)
        if (endsSession) {
            notificationFailureLogged = false
            evaluationFailureLogged = false
        }
    }

    /**
     * Muss unter [lock] laufen. Den Stand des Trackers vollständig lesen (sonst nichts ändern), dann Zustand und Benachrichtigungen, dann das Log (die einzige Suspendierungsstelle).
     * Die Einträge der Fehler dieses Aufrufs schreibt [report] danach.
     */
    private suspend fun publish(failures: Failures) {
        val (now, visible) = failures.evaluating { tracker.active().associateBy { it.kind } to tracker.visible() } ?: return
        val before = shown
        val ended = before.keys.filter { it !in now }
        val raised = now.values.filter { it.raisesOver(before[it.kind]) }
        shown = now
        _alarms.value = visible
        ended.forEach { failures.notifying { notifier.clear(it) } }
        raised.forEach { failures.notifying { notifier.show(it) } }
        ended.forEach { log.log(LogLevel.INFO, "Alarm cleared: ${it.name}") }
        raised.forEach { log.log(LogLevel.WARN, "Alarm raised: ${it.describe()}") }
    }

    /** Je Quelle höchstens eine WARN-Zeile je Sitzung (das Flag wird vor dem Schreiben gesetzt: ein abgebrochenes Schreiben soll keine zweite Zeile nach sich ziehen). */
    private suspend fun report(failures: Failures) {
        failures.evaluation?.let {
            if (!evaluationFailureLogged) {
                evaluationFailureLogged = true
                log.log(LogLevel.WARN, "Alarm evaluation failed: ${it.logText()}")
            }
        }
        failures.notification?.let {
            if (!notificationFailureLogged) {
                notificationFailureLogged = true
                log.log(LogLevel.WARN, "Alarm notification failed: ${it.logText()}")
            }
        }
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

/** Führt [block] aus und liefert dessen Ausnahme statt sie zu werfen — außer der [CancellationException], die dem Aufrufer gehört. */
private fun catching(block: () -> Unit): Exception? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e
}

/** „Klasse: Meldung“ für das Log; ohne Meldung nur die Klasse, eine anonyme Klasse mit ihrem vollen Namen. */
private fun Exception.logText(): String =
    listOfNotNull(javaClass.simpleName.ifEmpty { javaClass.name }, message).joinToString(": ")
