package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.EngineGate
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Mindestzahl identischer Zyklen, bevor die Werte als eingefroren gelten. In den 31 gesunden Sitzungen (Stand 2026-10-28, Fahrten 45 bis 82 ohne die
 * eingefrorenen) standen Drehzahl, Kuehlmittel und Spannung bei laufendem Motor hoechstens **8 Zyklen (10,2 s)** am Stueck auf demselben Wert; die
 * eingefrorenen Fahrten blieben 8 bis 15 Minuten stehen. 12 Zyklen liegen vier Zyklen ueber dem gesunden Hoechstwert. Bis 1.44.6 waren es 20 (die Erkennung
 * dauerte rund 30 s); mit dem Takt der Fahrten (1,1 bis 1,6 s) und der Zeitgrenze des Watchdogs (Standard 20 s) entscheidet jetzt meist die Zeit: rund 20 s statt 30 s.
 * Die Zeit kommt als zweite Grenze aus der Einstellung des Watchdogs dazu (s. [FrozenValuesDetector]).
 */
internal const val FROZEN_MIN_CYCLES = 12

/** Was [FrozenValuesDetector.update] ueber einen Zyklus sagt. */
internal enum class FrozenState {
    /** Keine Drehzahl im Zyklus: Es laesst sich nichts sagen, der Lauf bleibt, wie er war. */
    IGNORED,

    /** Der Motor laeuft nicht (unter 500 rpm): Zuendung an wiederholt ihre Werte beliebig lange — nie eingefroren; der Lauf endet. */
    ENGINE_OFF,

    /** Mindestens ein Wert hat sich gegenueber dem letzten Zyklus bewegt: Der Strom lebt. Dieser Zyklus ist der erste eines neuen Laufs. */
    CHANGED,

    /** Alle Werte wie zuvor, aber noch nicht lange genug (oder der erste Zyklus des Laufs). */
    UNCHANGED,

    /** Alle Werte seit mindestens [FROZEN_MIN_CYCLES] Zyklen und der eingestellten Zeit unveraendert, bei laufendem Motor. */
    FROZEN,
}

/**
 * Erkennt, dass das Fahrzeug **weiter antwortet, aber nichts mehr aendert** (Fahrten 52 und 60, 2026-10-20): Drehzahl, Kuehlmittel und Spannung standen
 * minutenlang auf demselben Wert, waehrend die GPS-Geschwindigkeit von 0 bis 109 km/h lief. Jede Antwort hatte einen neuen Zeitstempel, die Abfrage hing nicht —
 * deshalb meldete weder das Band „Keine aktuellen Werte“ (es braucht 10 s ganz ohne Zyklus) noch der Watchdog (er greift nur bei Zyklen ohne jeden Messwert) etwas.
 *
 * Ein Zyklus ist **unveraendert**, wenn jeder Wert, den er enthaelt, gleich dem letzten Wert derselben PID ist. Fehlt eine PID in einem Zyklus (die Spannung wird
 * nur alle paar Zyklen gelesen), bricht das den Lauf nicht; ein Wert, der keine Zahl ist, zaehlt weder als Aenderung noch setzt er den Vergleichswert zurueck.
 * **Eingefroren** ist der Strom, wenn er seit [minCycles] Zyklen **und** seit [minDuration] unveraendert ist und der Motor laeuft (ab
 * [EngineGate.RUNNING_MIN_RPM], wie ueberall in der App). Bei stehendem Motor ist nichts eingefroren: Zuendung an wiederholt Drehzahl 0 und eine fast
 * konstante Spannung beliebig lange.
 *
 * Reine Logik ohne Android; die Zeit kommt als [TimeSource] (Tests: `TestTimeSource`).
 */
internal class FrozenValuesDetector(
    private val timeSource: TimeSource,
    private val minDuration: Duration,
    private val minCycles: Int = FROZEN_MIN_CYCLES,
) {
    private val lastValues = HashMap<String, Float>()
    private var runStart: TimeMark? = null

    /** Zyklen des laufenden Laufs gleicher Werte, der erste (der mit der Aenderung bzw. der allererste) eingeschlossen. */
    var cycles = 0
        private set

    /** Wie lange der Lauf schon dauert: Zeit seit seinem ersten Zyklus. */
    val unchangedFor: Duration get() = runStart?.elapsedNow() ?: Duration.ZERO

    /** Der letzte endliche Wert je PID (Schluessel: PID-ID) — das, womit der laufende Lauf den naechsten Zyklus vergleicht. Eine Kopie. */
    fun valuesSnapshot(): Map<String, Float> = HashMap(lastValues)

    /**
     * Setzt einen Lauf aus einer frueheren Sitzung fort (s. [FrozenValuesGuard]): [values] sind die Werte, die dort eingefroren waren. Der Lauf zaehlt als schon so lang wie
     * beide Grenzen — der erste unveraenderte Zyklus ist damit sofort [FrozenState.FROZEN], ein veraenderter [FrozenState.CHANGED], ein stehender Motor
     * [FrozenState.ENGINE_OFF], ein Zyklus ohne Drehzahl [FrozenState.IGNORED]. Ersetzt einen bisherigen Lauf.
     */
    fun resumeRun(values: Map<String, Float>) {
        lastValues.clear()
        lastValues.putAll(values)
        runStart = timeSource.markNow() - minDuration
        cycles = minCycles - 1
    }

    fun update(values: List<SensorValue>): FrozenState {
        val rpm = values.firstOrNull { it.pid.id == PidRegistry.RPM.id }?.value
        if (rpm == null || !rpm.isFinite()) return FrozenState.IGNORED
        if (rpm < EngineGate.RUNNING_MIN_RPM) {
            lastValues.clear()
            runStart = null
            cycles = 0
            return FrozenState.ENGINE_OFF
        }
        var changed = false
        for (v in values) {
            if (!v.value.isFinite()) continue
            val before = lastValues.put(v.pid.id, v.value)
            if (before != null && before != v.value) changed = true
        }
        val start = runStart
        if (changed || start == null) {
            runStart = timeSource.markNow()
            cycles = 1
            return if (changed) FrozenState.CHANGED else FrozenState.UNCHANGED
        }
        cycles++
        return if (cycles >= minCycles && start.elapsedNow() >= minDuration) FrozenState.FROZEN else FrozenState.UNCHANGED
    }
}

/** Was die Pipeline mit einem OBD-Zyklus tun soll ([FrozenValuesGuard.onCycle]). */
internal sealed interface FrozenAction {
    /** Die Werte weiterreichen: anzeigen, aufzeichnen. */
    data object Pass : FrozenAction

    /** Die Werte NICHT weiterreichen: schon einmal neu verbunden, und es ist wieder dasselbe. Ohne Zyklen zeigt die Oberflaeche nach 10 s „Keine aktuellen Werte“. */
    data object Hold : FrozenAction

    /** Neu verbinden: Der Adapter wird neu initialisiert, vielleicht heilt das. [cycles] und [unchangedFor] gehoeren ins Log. */
    data class Reconnect(val cycles: Int, val unchangedFor: Duration) : FrozenAction
}

/**
 * Die Entscheidung bei eingefrorenen Werten, ueber Sitzungen hinweg: **einmal je Fall neu verbinden** — ob ein neuer Adapter-Start hilft, ist offen (nicht am Fahrzeug
 * ausprobiert; liegt es am Steuergeraet, hilft es nicht; **bis heute half es in keinem der vier aufgezeichneten Faelle**, auch das Trennen und Verbinden von Hand nicht).
 * Ist es nach dem Neuverbinden wieder dasselbe, bleibt die Verbindung stehen und die Werte werden zurueckgehalten ([FrozenAction.Hold]): Eine Schleife aus Verbinden,
 * 30 s Warten und Verbinden legte je Durchgang eine neue Fahrt mit nutzlosen Zeilen an.
 *
 * **Ein offener Fall geht in die naechste Sitzung ueber** (Fahrt vom 2026-10-28): der Fall merkt sich die Werte, die eingefroren waren, und die neue Sitzung setzt den Lauf damit
 * fort ([FrozenValuesDetector.resumeRun]). Schon der erste Zyklus nach dem Neuverbinden — gleich ob die App es tat oder der Nutzer von Hand — wird damit verglichen: dieselben Werte
 * heissen sofort [FrozenAction.Hold], ein bewegter Wert beendet den Fall. Vorher begann jede Sitzung bei null, und die ersten 20 Zyklen (rund 30 s) liefen mit den alten Werten
 * ohne Band durch — nach dem Neuverbinden von Hand sah das aus wie „wieder aktuelle Werte“.
 *
 * Ein Fall endet, wenn sich ein Wert wieder bewegt ([FrozenState.CHANGED]) oder der Motor ausgeht ([FrozenState.ENGINE_OFF]); danach darf ein spaeteres Einfrieren wieder
 * einmal neu verbinden. Der Detektor gehoert der Sitzung ([beginSession]), die Werte des Falls ueberleben das Neuverbinden.
 */
internal class FrozenValuesGuard {
    private var detector = FrozenValuesDetector(TimeSource.Monotonic, Duration.ZERO)

    /**
     * Die Werte des eingefrorenen Zyklus, fuer den schon neu verbunden wurde, solange sich seither kein Wert bewegt hat und der Motor nicht ausging — `null`, wenn kein Fall offen
     * ist. Gesetzt in dem Augenblick, in dem [FrozenAction.Reconnect] verlangt wird.
     */
    private var openEpisode: Map<String, Float>? = null

    /**
     * Die Werte werden gerade zurueckgehalten: seit dem ersten [FrozenAction.Hold] dieser Sitzung — oder von Beginn einer Sitzung an, die einen offenen Fall fortsetzt —, bis sich ein
     * Wert bewegt oder der Motor ausgeht. In diesem Zustand bleibt auch ein Zyklus ohne Drehzahl zurueckgehalten: er kann nicht beweisen, dass der Strom wieder lebt.
     */
    var holding = false
        private set

    /**
     * Beginnt eine Sitzung: Zaehlung und Vergleichswerte von vorn, [minDuration] aus der Einstellung des Watchdogs. **Ist ein Fall offen, setzt die Sitzung ihn fort:** der Vergleich
     * beginnt mit den Werten des Falls, und die Sitzung beginnt in der Sperre. Sonst beginnt sie nie in der Sperre: ihre ersten Zyklen laufen durch, bis die Grenze erreicht ist.
     */
    fun beginSession(timeSource: TimeSource, minDuration: Duration) {
        detector = FrozenValuesDetector(timeSource, minDuration)
        val episode = openEpisode
        if (episode != null) detector.resumeRun(episode)
        holding = episode != null
    }

    fun onCycle(values: List<SensorValue>): FrozenAction = when (detector.update(values)) {
        FrozenState.CHANGED, FrozenState.ENGINE_OFF -> {
            openEpisode = null
            holding = false
            FrozenAction.Pass
        }
        // Ein Zyklus ohne Drehzahl kann nicht beweisen, dass der Strom wieder lebt: in der Sperre bleibt er zurueckgehalten.
        FrozenState.IGNORED -> if (holding) FrozenAction.Hold else FrozenAction.Pass
        FrozenState.UNCHANGED -> FrozenAction.Pass
        FrozenState.FROZEN ->
            if (openEpisode != null) {
                holding = true
                FrozenAction.Hold
            } else {
                openEpisode = detector.valuesSnapshot()
                FrozenAction.Reconnect(detector.cycles, detector.unchangedFor)
            }
    }
}
