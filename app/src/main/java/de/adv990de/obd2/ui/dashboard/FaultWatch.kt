package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Die Fehlerwache: liest die Fehlercodes waehrend der Sitzung nach und meldet jedes erfolgreiche Auslesen als [SessionEvent.FaultCodes]
 * (`isBaseline = false`). Das erste Nachlesen kommt nach [INTERVAL_MS], danach im selben Abstand; **aendert sich der Stand gegenueber dem
 * vorigen** (auch gegenueber der Basis vom Verbinden, s. [rememberBaseline]), folgt das naechste nach [RECHECK_MS] — ein Code, der gerade
 * auftaucht oder verschwindet, soll nicht eine volle Minute lang unbestaetigt stehen. Gelesen wird weich ([ObdRepository.readDtcsOrSkip]):
 * ein Zeitlimit oder NO DATA ergibt `null`, das hier nichts meldet und nichts aendert — kein Auslesen ist keine Aussage.
 *
 * Der Schalter „Fehler unterwegs melden“ (`alarmSettings.faultsEnabled`) wird bei **jedem** Durchlauf gelesen, nicht einmal je Sitzung: ein
 * Ein- oder Ausschalten wirkt beim naechsten Durchlauf. Der Fluss gehoert zur Sitzung: er endet mit ihr (Verbindungsverlust beendet das
 * `merge` in `ReadingsPipeline.events`; eine Sitzung, deren Quelle zu ist, nicht mehr — dann [isSessionRunning] falsch) und liest dann nichts mehr.
 *
 * Jedes erfolgreiche Auslesen geht, wie das beim Verbinden, in die Fehlerhistorie ([recordHistory], best effort). Jeder unerwartete
 * Fehler des Nachlesens wird nur ins App-Log geschrieben ([log]): die Wache ist eine Zusatzauskunft und darf die Live-Anzeige nie beenden.
 *
 * Herausgeloest aus `ReadingsPipeline` (die Datei war auf 490 Zeilen gewachsen); die Pipeline reicht ihre eigenen Faehigkeiten durch, damit hier nichts
 * doppelt steht: [requestReconnect] ist ihr `connectionLostSignal`, [log] ihr `logBestEffort`. Auch das **Auslesen beim Verbinden** ([readOnConnect]) steht jetzt hier — es ist
 * dieselbe Fehlerbehandlung wie beim Nachlesen und liefert die Basis.
 */
internal class FaultWatch(
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences,
    private val dtcHistoryRepository: DtcHistoryRepository,
    private val log: suspend (LogLevel, String) -> Unit,
    private val requestReconnect: () -> Unit,
    private val isSessionRunning: () -> Boolean,
) {

    /**
     * Der Stand des letzten erfolgreichen Auslesens (Code, ob er nur aussteht) — die Basis fuer die Entscheidung, ob nach 10 s statt nach 60 s nachgelesen
     * wird. `null`, solange nichts erfolgreich gelesen wurde (neue Sitzung, Auslesen beim Verbinden ohne Antwort): dann gibt es nichts zu vergleichen.
     * Geschrieben vom Auslesen beim Verbinden ([rememberBaseline]) und von [events].
     */
    @Volatile
    private var lastSet: Set<Pair<String, Boolean>>? = null

    /** Ob die einmalige Diagnose nach einem fehlgeschlagenen Auslesen schon gelaufen ist (einmal je Lebensdauer der Wache, s. [diagnoseFailedRead]); `reset()` setzt sie nicht zurueck. */
    private val diagnosed = AtomicBoolean(false)

    /** Eine neue Sitzung beginnt ohne Vergleichsstand. */
    fun reset() {
        lastSet = null
    }

    /** Das Auslesen beim Verbinden: dieser Stand gilt als bekannt (`null` = ohne Antwort, dann gibt es nichts zu vergleichen). */
    fun rememberBaseline(codes: List<DtcEntry>?) {
        lastSet = codes?.toFaultSet()
    }

    /**
     * Das Ergebnis des Auslesens beim Verbinden: [Answer] (die Codes; leer = keine; `null` = keine positive Antwort) oder [LinkLost] — der Link ist beim Lesen gerissen, die
     * Sitzung endet, der Reconnect-Ausloeser ist schon abgesetzt.
     */
    sealed interface ConnectRead {
        data class Answer(val codes: List<DtcEntry>?) : ConnectRead
        data object LinkLost : ConnectRead
    }

    /**
     * Liest die Fehlercodes beim Verbinden: protokollieren, in die Fehlerhistorie eintragen und als Basis der Wache merken. Kein Banner in der Oberflaeche — sichtbar sind sie im
     * App-Log. [wantsReconnect] sagt, ob ein gerissener Link einen Reconnect will (nein, wenn der Nutzer getrennt hat).
     */
    suspend fun readOnConnect(wantsReconnect: () -> Boolean): ConnectRead {
        val dtcList = try { obdRepository.readDtcs() } catch (e: Exception) {
            if (e is CancellationException) throw e
            // readDtcs() trennt den Adapter selbst, wenn die Antwort ausbleibt (Zeitueberschreitung: ein spaeter Rest haette den naechsten Befehl verfaelscht) oder der Link abreisst.
            // Ist das hier geschehen, steht die Verbindung nicht mehr — und weil nur ConnectionLost einen Reconnect ausloest, ginge sie sonst still verloren (Anzeige "getrennt",
            // kein neuer Versuch; jeder Slot-Wechsel startet diese Sitzung und damit das Auslesen neu). requestReconnect() ist nicht-suspendierend, aus demselben Grund wie beim
            // Nachlesen: der Wechsel von isConnectedFlow bricht diese Coroutine am naechsten Suspendierungspunkt ab.
            if ((e is ObdException.Timeout || e is ObdException.ConnectionLost) && !obdRepository.isConnectedFlow.value) {
                reportLinkLost(wantsReconnect, "Fault-code read")
                return ConnectRead.LinkLost
            }
            // Keine positive Antwort (NO DATA, Busfehler, Unverstaendliches — A9): kein Verbindungsverlust, die Sitzung laeuft weiter, aber die Oberflaeche behauptet keine
            // "Entwarnung" (nichts wird in der Historie vermerkt) und das App-Log haelt fest, WAS der Adapter geantwortet hat — genau das Roh-Material, das fuer A9 Teil 2 gebraucht wird.
            log(LogLevel.WARN, "Fault-code read failed: ${e.javaClass.simpleName}: ${e.message}")
            // Dazu einmal die Diagnose (s. diagnoseFailedRead); riss dabei der Link, endet die Sitzung wie beim Auslesen selbst.
            diagnoseFailedRead(wantsReconnect)?.let { return it }
            null
        }
        if (dtcList?.isNotEmpty() == true) {
            log(LogLevel.WARN, "Active DTCs on connect: ${dtcList.joinToString { if (it.isPending) "${it.code} (pending)" else it.code }}")
        }
        // Lokale Fehlerhistorie: jede Fahrt fuellt sie passiv. Nur nach einem erfolgreichen Auslesen (null = Lesefehler: nichts aufzeichnen).
        if (dtcList != null) recordHistory(dtcList)
        // Die Basis: dieser Stand gilt als bekannt, ein spaeter hinzukommender Code ist neu.
        rememberBaseline(dtcList)
        return ConnectRead.Answer(dtcList)
    }

    /** Der Link ist beim Lesen gerissen: ein Reconnect, wenn der Nutzer nicht selbst getrennt hat; [what] benennt den Lesevorgang fuer das App-Log. */
    private suspend fun reportLinkLost(wantsReconnect: () -> Boolean, what: String) {
        if (wantsReconnect()) {
            requestReconnect()
            log(LogLevel.WARN, "$what lost the adapter link, reconnecting")
        } else {
            log(LogLevel.INFO, "$what ended by the user's disconnect, not reconnecting")
        }
    }

    /**
     * Die einmalige Diagnose nach einem Auslesen von Modus 03 ohne Antwort (Backlog 2, 2026-10-26): [ObdRepository.diagnoseFaultRead] liefert die rohen Antworten des Adapters und
     * des Steuergeraets (Adapter-Kennung, Modus 01, 03, 07, 09, mit langem Zeitlimit und mit Kopfzeilen — rein lesend, die geaenderten Adapter-Einstellungen werden zurueckgestellt) als eine Zeile, die hier ins App-Log geht (INFO). **Einmal je Lebensdauer dieser Wache** (die der Pipeline des
     * Dashboards, also einmal je App-Start): sie soll Material fuer die Auswertung liefern, nicht jedes Verbinden um ein paar Befehle verlaengern; der Versuch zaehlt auch, wenn er scheitert.
     * Ein unerwarteter Fehler steht nur im Log (WARN) — die Diagnose ist Zusatzauskunft und beendet nie die Sitzung. Riss dabei der Link, ist das Ergebnis [ConnectRead.LinkLost]
     * (Reconnect wie beim Auslesen); sonst `null`.
     */
    private suspend fun diagnoseFailedRead(wantsReconnect: () -> Boolean): ConnectRead.LinkLost? {
        if (!diagnosed.compareAndSet(false, true)) return null
        val text = try { obdRepository.diagnoseFaultRead() } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ObdException.ConnectionLost && !obdRepository.isConnectedFlow.value) {
                reportLinkLost(wantsReconnect, "Fault-code diagnostics")
                return ConnectRead.LinkLost
            }
            log(LogLevel.WARN, "Fault-code diagnostics failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (text != null) log(LogLevel.INFO, "Fault-code diagnostics: $text")
        return null
    }

    /**
     * Traegt ein erfolgreiches Auslesen in die lokale Fehlerhistorie ein. `record()` ist selbst „best effort“ — die Absicherung hier deckt das Unerwartete ab, denn eine Ausnahme
     * an dieser Stelle wuerde die ganze Sitzung (beim Verbinden) bzw. die Wache kosten.
     */
    private suspend fun recordHistory(codes: List<DtcEntry>) {
        try { dtcHistoryRepository.record(codes) } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    fun events(): Flow<SessionEvent> = flow {
        // Stand der Nutzer-Trennungen beim Start der Sitzung — wie in `ReadingsPipeline.runSession`: ein Lesen, das scheitert, WEIL der Nutzer trennte,
        // ist kein Verbindungsverlust und loest keinen Reconnect aus.
        val userDisconnectsAtStart = obdRepository.userDisconnectCount.value
        var wait = INTERVAL_MS
        while (true) {
            delay(wait)
            wait = INTERVAL_MS
            if (!isSessionRunning()) return@flow
            if (!appPreferences.alarmSettings.first().faultsEnabled) continue
            val codes = try {
                obdRepository.readDtcsOrSkip()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ObdException.ConnectionLost) {
                // Wie beim Auslesen beim Verbinden: readDtcsOrSkip() hat getrennt, isConnectedFlow ist schon false, und der Wechsel bricht diese Coroutine am
                // naechsten Suspendierungspunkt ab — deshalb zuerst der Reconnect-Ausloeser (nicht-suspendierend, s. Doku an `connectionLostSignal`), das Log danach.
                if (!obdRepository.isConnectedFlow.value) {
                    if (obdRepository.userDisconnectCount.value == userDisconnectsAtStart) {
                        requestReconnect()
                        log(LogLevel.WARN, "Periodic fault-code read lost the adapter link, reconnecting")
                    } else {
                        log(LogLevel.INFO, "Periodic fault-code read ended by the user's disconnect, not reconnecting")
                    }
                    return@flow
                }
                // Die Verbindung steht noch (ersetzt: ein anderer Leser trennte die alte). Faellt die Sitzung dadurch nicht ohnehin zusammen — das
                // Zwischen-"getrennt" eines StateFlow kann verschluckt werden —, liest die Wache in der naechsten Minute ueber die neue Verbindung weiter;
                // ein Ende liesse den Rest der Sitzung ohne Fehlerwache. Weder Reconnect noch Log: es ist kein Verlust.
                continue
            } catch (e: Exception) {
                log(LogLevel.WARN, "Periodic fault-code read failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            } ?: continue
            recordHistory(codes)
            emit(SessionEvent.FaultCodes(codes, isBaseline = false))
            val now = codes.toFaultSet()
            val before = lastSet
            if (before != null && now != before) wait = RECHECK_MS
            lastSet = now
        }
    }

    internal companion object {
        /** Abstand der Fehlerwache: das erste Nachlesen kommt so lange nach dem Sitzungsbeginn, jedes weitere im selben Abstand (zwei Befehle, rund 0,3-0,5 s je Minute — geschaetzt). */
        const val INTERVAL_MS = 60_000L

        /** Nachpruefung nach einer Aenderung des Stands gegenueber dem vorigen Auslesen — anstelle des vollen Abstands. */
        const val RECHECK_MS = 10_000L
    }
}

/** Der Stand eines Auslesens fuer den Vergleich: Code und ob er nur aussteht — ein Wechsel von ausstehend zu bestaetigt ist eine Aenderung. */
private fun List<DtcEntry>.toFaultSet(): Set<Pair<String, Boolean>> = map { it.code to it.isPending }.toSet()
