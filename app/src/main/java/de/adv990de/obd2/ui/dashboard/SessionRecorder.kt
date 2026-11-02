package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException

/**
 * Die Aufzeichnung EINER Sitzung: Fahrt anlegen, Zyklen schreiben, Fahrt beenden. Herausgeloest aus
 * [ReadingsPipeline.events]; die Pipeline entscheidet, OB aufgezeichnet wird (`logOnlyDtcSessions`), dieser Baustein, WIE.
 *
 * **Geschrieben wird alles, was die Pipeline uebergibt:** die abgefragten Werte (Kacheln, Drehzahl, Kuehlmittel, die Geschwindigkeit
 * des Fahrzeugs) und die ergaenzte GPS-Geschwindigkeit. Einen weiteren Filter gibt es nicht — bis v1.27.3 verwarfen ein Schalter
 * („nur vom Fahrzeug gemeldete Werte“) und eine Abwahl-Liste Werte, und der Schalter nahm Fahrzeugen ohne Geschwindigkeit die GPS-Geschwindigkeit.
 *
 * Aufzeichnen ist Beiwerk der Live-Anzeige: Ein Datenbankfehler (Platte voll, Datenbank gesperrt) darf sie nicht beenden. Er wird
 * einmal je Sitzung gemeldet — ein Fehler mit 2 Hz wuerde sonst das ebenfalls betroffene App-Log fluten. Nur die Abbruch-Ausnahme
 * des Aufrufers geht immer durch. Ohne angelegte Fahrt ([start] nie gerufen oder gescheitert) sind [record] und [end] wirkungslos.
 *
 * [log] schreibt ins App-Log, auch wenn die Sitzung gerade abgebrochen wird (die Pipeline reicht ihr `logBestEffort`).
 */
internal class SessionRecorder(
    private val sessionRepository: SessionRepository,
    private val crashReporter: CrashReporter,
    private val log: suspend (LogLevel, String) -> Unit,
) {
    // @Volatile: [record] kommt auch vom ViewModel (Gang und Oel-Balken, `ReadingsPipeline.recordDerived`), [end] vom Ende der Sitzung — verschiedene Coroutinen.
    @Volatile private var sessionId = NO_SESSION
    private var failureReported = false

    /**
     * Legt die Fahrt an, mit den Fehlercodes, die das Fahrzeug beim Verbinden gemeldet hat ([dtcs]: `null` = nicht erfasst, leer = keine).
     * Scheitert das, laeuft die Anzeige ohne Aufzeichnung weiter.
     */
    suspend fun start(vin: String?, dtcs: List<DtcEntry>? = null) {
        sessionId = try {
            sessionRepository.startSession(vin, dtcs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reportFailure(e)
            NO_SESSION
        }
    }

    /**
     * Schreibt einen zusammengefuehrten Zyklus (alle uebergebenen Werte) oder berechnete Werte zu einem Zyklus (Gang und Oel-Balken).
     * Nach [end] ist die Fahrt zu: ein Wert, der erst jetzt ankommt, wird verworfen statt an eine beendete Fahrt gehaengt.
     */
    suspend fun record(values: List<SensorValue>) {
        if (sessionId == NO_SESSION) return // keine Fahrt: nichts aufzuzeichnen
        try {
            sessionRepository.writeReadings(sessionId, values)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reportFailure(e)
        }
    }

    /**
     * Beendet die Fahrt, falls es eine gibt (einmal; danach ist sie zu). Ein Datenbankfehler verhindert das Aufraeumen der Sitzung nicht, wird aber
     * wie beim Schreiben gemeldet (einmal je Sitzung): eine Fahrt ohne Ende zaehlt sonst als „laeuft noch“ und waere im Verlauf nie zu erklaeren.
     */
    suspend fun end() {
        val id = sessionId
        if (id == NO_SESSION) return
        sessionId = NO_SESSION
        try {
            sessionRepository.endSession(id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reportFailure(e)
        }
    }

    private suspend fun reportFailure(e: Exception) {
        if (failureReported) return
        failureReported = true
        crashReporter.recordException(e)
        log(LogLevel.WARN, "Recording failed, the live display continues: ${e.message}")
    }

    private companion object {
        const val NO_SESSION = -1L
    }
}
