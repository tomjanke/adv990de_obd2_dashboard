package de.adv990de.obd2.data

import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.VinDecoder
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Die Befehle der einmaligen Diagnose nach einem fehlgeschlagenen Auslesen von Modus 03, in dieser Reihenfolge ([DiagnosticReads.diagnoseFaultRead]). **Zweite Runde** (2026-10-26):
 * die erste (`ATDP`, `ATDPN`, `0101`, `0100`, `03`, `07`, `0A`) zeigte am Motorrad, dass der Adapter auf `ATDP`/`ATDPN` nur „OK“ sagt, das Steuergeraet `0100` beantwortet, aber
 * `0101`, `07` und `0A` mit NO DATA und `03` mit einem leeren Text — Adapter oder Steuergeraet sind nicht voll standardkonform. Diese Runde fragt:
 *  - den **Adapter** (`ATI` Version, `AT@1` Beschreibung, `ATRV` Spannung am Adapter, `ATIGN` Zuendungspegel);
 *  - eine **Kontrolle** mit normalem Zeitlimit (`0100`, `0105` Kuehlmittel, `0101`, `03`, `0900` unterstuetzte Infotypen von Modus 09);
 *  - dieselben Modi mit **langem Zeitlimit** (`ATSTFF`, rund 1 s statt 0,2 s: antwortet das Steuergeraet nur langsam?);
 *  - mit **eingeschalteten Kopfzeilen** (`ATH1`: die rohen Frames zeigen das Protokoll und ob auf `03` ueberhaupt etwas kommt).
 * **Nur Lesen** — die Liste enthaelt nie Modus 04 oder einen anderen Schreibzugriff (Test `DiagnosticReadsTest`); `ATSTFF` und `ATH1` aendern nur Einstellungen des Adapters und werden
 * ueber [FAULT_READ_DIAGNOSTIC_RESTORES] in der Liste selbst und bei jedem vorzeitigen Ende zurueckgesetzt.
 */
internal val FAULT_READ_DIAGNOSTIC_COMMANDS = listOf(
    "ATI", "AT@1", "ATRV", "ATIGN",
    "0100", "0105", "0101", "03", "0900",
    "ATSTFF", "0101", "03", "07", "ATST32",
    "ATH1", "0100", "03", "ATH0",
)

/**
 * Die Einstellungen des Adapters, die die Diagnose aendert (Schluessel), und der Befehl, der sie auf den Stand nach der Initialisierung zurueckstellt (Wert): Standardzeitlimit
 * (`ATST32` = 50 x 4 ms) und Kopfzeilen aus (`ATH0`; die Initialisierung setzt kein `ATH1`, mit Kopfzeilen kaeme vor jede Antwort eine Kennung und kein Parser koennte sie lesen).
 */
internal val FAULT_READ_DIAGNOSTIC_RESTORES = mapOf("ATSTFF" to "ATST32", "ATH1" to "ATH0")

/** Laenger als das wird eine einzelne Antwort in der Diagnose gekuerzt. */
internal const val DIAGNOSTIC_ANSWER_MAX_CHARS = 200

/**
 * Die einmaligen Lesevorgaenge ueber eine bestehende Verbindung: Fehlercodes (Modus 03/07), Freeze-Frame (Modus 02) und FIN (Modus 09).
 * Herausgeloest aus [ObdRepository], das die Verbindung und die laufende Abfrage haelt und diese Lesepfade nur noch durchreicht.
 *
 * Jeder Lesepfad bekommt den [ElmController] mit, ueber den er liest, und meldet einen Fehler, der die Verbindung beendet, mit
 * genau diesem Controller an [onLinkBroken]: das Repository trennt nur, wenn er noch die aktuelle Verbindung ist (PRO-11). Welcher
 * Fehler trennt und welcher nicht, steht als Zeitlimit-Richtlinie an [ObdRepository] (PRO-13).
 */
internal class DiagnosticReads(
    private val pidParser: PidParser,
    private val dtcParser: DtcParser,
    private val onLinkBroken: suspend (ElmController) -> Unit,
) {

    suspend fun readDtcs(controller: ElmController): List<DtcEntry> =
        try {
            readBothModes(controller)
        } catch (e: ObdException.Timeout) {
            // A timeout leaves the serial stream in an unknown state: the stale response may
            // arrive during the next sendCommand and corrupt it. Disconnect now so the stream
            // is fully reset before the next caller sends anything.
            withContext(NonCancellable) { onLinkBroken(controller) }
            throw e
        } catch (e: ObdException.ConnectionLost) {
            // Wie livePids() und readFreezeFrame(): ein verlorener Link wird nie als "verbunden" stehen
            // gelassen — sonst haelt isConnectedFlow einen toten Adapter fuer lebendig.
            withContext(NonCancellable) { onLinkBroken(controller) }
            throw e
        }

    /**
     * Dieselben Befehle wie [readDtcs], aber WEICH — fuer das Nachlesen mitten in der Fahrt (Fehlerwache der `ReadingsPipeline`): ein
     * Zeitlimit ueberspringt das Auslesen (`null`) und **trennt nicht**. Das ist die vierte Form der Zeitlimit-Richtlinie an
     * [ObdRepository]; [readDtcs] trennt bei jedem Zeitlimit, und das waere unterwegs ein zu hoher Preis fuer ein Nachlesen, das nur eine
     * Zusatzauskunft ist. Dass der ELM327 keine Antwort-Kennung kennt, bleibt: die verspaetete Antwort verwirft der naechste Befehl als
     * Altbyte (`ElmController.discardStaleInput`), sofern sie bis dahin eingetroffen ist; trifft sie erst danach ein, kann die Antwort des naechsten
     * PID verschoben sein (ein uebersprungener PID, den der Watchdog zaehlt). Die Befehle sind durch den Mutex des Controllers einzeln serialisiert —
     * ein Auslesen verschraenkt sich hoechstens zwischen zwei PID-Befehlen der Live-Abfrage.
     *
     * `null` heisst "keine Aussage", nie "keine Codes" (das waere eine leere Liste): Zeitlimit, NO DATA und Busfehler bei Modus 03,
     * Unverstaendliches, jeder andere Fehler des Protokolls. Nur eine verlorene Verbindung ([ObdException.ConnectionLost]) trennt wie ueberall
     * ([onLinkBroken]) und wird weitergereicht; die Abbruch-Ausnahme einer Coroutine auch (sie ist keine [ObdException]).
     */
    suspend fun readDtcsOrSkip(controller: ElmController): List<DtcEntry>? =
        try {
            readBothModes(controller)
        } catch (e: ObdException.ConnectionLost) {
            withContext(NonCancellable) { onLinkBroken(controller) }
            throw e
        } catch (_: ObdException) {
            null
        }

    /**
     * Der gemeinsame Kern von [readDtcs] und [readDtcsOrSkip]: Modus 03, dann Modus 07 mit der bekannten Toleranz fuer NO DATA, eine Zeile
     * je Code. Wirft jede [ObdException] des Controllers und des Parsers; was daraus wird (trennen, ueberspringen), entscheidet der Aufrufer.
     */
    private suspend fun readBothModes(controller: ElmController): List<DtcEntry> {
        // Modus 03 (bestaetigt) ist die massgebliche Frage: bleibt sie ohne positive Antwort, wirft der Parser
        // (NO DATA, Busfehler, Unverstaendliches — A9) und die Seite sagt es, statt "keine Fehlercodes" zu behaupten.
        val confirmed = dtcParser.parse(controller.sendCommand("03"), isPending = false)
        // Modus 07 (ausstehend) ist optional: Viele Steuergeraete schweigen darauf, wenn nichts ansteht. Diese Stille
        // ist keine Aussage "nichts ausstehend", aber auch kein Grund, die bestaetigten Codes zu verwerfen. Ein
        // Busfehler oder Muell im selben Zug bleibt ein Fehler.
        val pending = try {
            dtcParser.parse(controller.sendCommand("07"), isPending = true)
        } catch (_: ObdException.NoData) {
            emptyList()
        }
        // Ein Code kann in beiden Antworten stehen (bestaetigt UND noch ausstehend) oder von zwei
        // Steuergeraeten doppelt gemeldet werden. Eine Zeile je Code: die DTC-Seite nutzt den Code
        // als LazyColumn-Key, ein Duplikat laesst sie abstuerzen. Bestaetigt steht zuerst und gewinnt.
        return (confirmed + pending).distinctBy { it.code }
    }

    /**
     * Die einmalige Diagnose nach einem Auslesen von Modus 03 ohne Antwort (Backlog 2, 2026-10-26; zweite Runde nach dem ersten Befund am Motorrad): der Adapter und das Steuergeraet
     * bekommen die Befehle aus [FAULT_READ_DIAGNOSTIC_COMMANDS] gestellt und die **rohen Antworten** kommen als eine Zeile zurueck (`ATI='…' AT@1='…' 0100='…' …`) — fuer das App-Log.
     * Eine leere Antwort steht als zwei Anfuehrungszeichen da, eine Antwort ueber [DIAGNOSTIC_ANSWER_MAX_CHARS] Zeichen wird gekuerzt (`...`), damit nichts das Log flutet.
     *
     * **Rein lesend:** Befehle an den Adapter (Version, Beschreibung, Spannung, Zuendungspegel) und Lesemodi des Fahrzeugs — 01 (unterstuetzte PIDs, Status der Ueberwachungen, Kuehlmittel),
     * 03 und 07 (Codes), 09 (unterstuetzte Infotypen). Nie Modus 04 (Fehlerspeicher loeschen) oder ein Schreibzugriff — das Fahrzeug bleibt strikt lesend.
     *
     * **Adapter-Einstellungen werden zurueckgestellt:** `ATSTFF` (langes Zeitlimit) und `ATH1` (Kopfzeilen an) veraendern den Adapter fuer alle folgenden Befehle, auch die der Sitzung.
     * Jeder Wechsel wird vor dem Senden als ausstehend gemerkt und mit dem Rueckstellbefehl aus [FAULT_READ_DIAGNOSTIC_RESTORES] aufgeloest — in der Liste selbst, sonst bei **jedem** Ende
     * (Zeitlimit, Fehler, Abbruch, dann unter `NonCancellable`: ein Slot-Wechsel bricht die Sitzung ab, die Verbindung bleibt). Nur eine verlorene Verbindung stellt nichts zurueck (die
     * Verbindung ist tot, der Neuaufbau initialisiert den Adapter). **Gelingt das Rueckstellen nicht**, steht der Adapter in einem unbekannten Zustand: dann wird getrennt
     * ([onLinkBroken]) und `ConnectionLost` geworfen, der Neuaufbau initialisiert den Adapter neu.
     *
     * **Weich, die vierte Form der Zeitlimit-Richtlinie** (s. KDoc an [ObdRepository]): ein Zeitlimit beendet die Diagnose (`<timeout>`, die uebrigen Befehle entfallen) und trennt
     * **nicht** — es ist nur eine Zusatzauskunft nach einem Fehler, und die verspaetete Antwort verwirft der naechste Befehl als Altbyte. Ein anderer Protokollfehler
     * ([ObdException]) steht als `<Klassenname>` beim Befehl, die Diagnose laeuft weiter. Nur eine verlorene Verbindung trennt ([onLinkBroken]) und wird weitergereicht.
     */
    suspend fun diagnoseFaultRead(controller: ElmController): String {
        val parts = mutableListOf<String>()
        val toRestore = linkedSetOf<String>()
        var linkLost = false
        try {
            for (command in FAULT_READ_DIAGNOSTIC_COMMANDS) {
                // Vor dem Senden gemerkt: scheitert der Befehl mit einem Zeitlimit, kann der Adapter ihn trotzdem schon befolgt haben.
                FAULT_READ_DIAGNOSTIC_RESTORES[command]?.let { toRestore += it }
                val answer = try {
                    "'${controller.sendCommand(command).limitedForLog()}'"
                } catch (_: ObdException.Timeout) {
                    parts += "$command=<timeout>"
                    break
                } catch (e: ObdException.ConnectionLost) {
                    linkLost = true
                    withContext(NonCancellable) { onLinkBroken(controller) }
                    throw e
                } catch (e: ObdException) {
                    "<${e.javaClass.simpleName}>"
                }
                parts += "$command=$answer"
                toRestore -= command
            }
        } finally {
            if (!linkLost && toRestore.isNotEmpty()) withContext(NonCancellable) { restoreAdapter(controller, toRestore, parts) }
        }
        return parts.joinToString(" ")
    }

    /**
     * Stellt die Adapter-Einstellungen zurueck, die die Diagnose geaendert hat ([FAULT_READ_DIAGNOSTIC_RESTORES]); jede Antwort kommt in [parts]. Scheitert ein Rueckstellbefehl —
     * auch mit einem Zeitlimit —, ist der Zustand des Adapters unbekannt: trennen und `ConnectionLost` werfen.
     */
    private suspend fun restoreAdapter(controller: ElmController, pending: Set<String>, parts: MutableList<String>) {
        for (command in pending) {
            try {
                parts += "$command='${controller.sendCommand(command).limitedForLog()}'"
            } catch (e: ObdException) {
                onLinkBroken(controller)
                throw e as? ObdException.ConnectionLost ?: ObdException.ConnectionLost("diagnostics could not restore the adapter settings ($command: ${e.javaClass.simpleName})")
            }
        }
    }

    /** Eine Zeile, hoechstens [DIAGNOSTIC_ANSWER_MAX_CHARS] Zeichen (sonst gekuerzt und mit `...` markiert). */
    private fun String.limitedForLog(): String {
        val oneLine = replace('\r', ' ').replace('\n', ' ')
        return if (oneLine.length > DIAGNOSTIC_ANSWER_MAX_CHARS) oneLine.take(DIAGNOSTIC_ANSWER_MAX_CHARS) + "..." else oneLine
    }

    /**
     * Reads freeze frame data (mode 02) for all known PIDs.
     * Mode 02 uses the same PID bytes as mode 01; responses carry "42" prefix.
     * Returns a map of PID id → formatted value, or empty map if no freeze frame is stored.
     * Keyed by id (not the English PidDefinition.name) so the UI can resolve the localized
     * label via PidRegistry, the same pattern used for sparklines and slot assignment.
     */
    suspend fun readFreezeFrame(controller: ElmController): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pid in PidRegistry.all.values) {
            val pidByte = pid.id.takeLast(2)
            // ELM327 mode-02 command: space-separated "02 <PID> <FF>" for widest clone
            // compatibility — many clones reject packed form "02<PID><FF>" without spaces.
            // NO DATA / UnknownResponse / BusInit ueberspringen den PID (der Adapter HAT geantwortet): ein einmaliger Diagnose-Lesevorgang
            // eskaliert voruebergehende Busfehler nicht zu ConnectionLost. Ein ZEITLIMIT bricht dagegen ab (s. unten).
            val raw = try {
                controller.sendCommand("02 ${pidByte} 00")
            } catch (_: ObdException.NoData)          { null }
              catch (_: ObdException.UnknownResponse) { null }
              catch (_: ObdException.BusInit)         { null }
              catch (e: ObdException.Timeout)         {
                  // Zeitlimit-Richtlinie (PRO-13), wie in readDtcs(): ein Zeitlimit laesst den Datenstrom in unbekanntem Zustand, die
                  // verspaetete Antwort kaeme beim naechsten Befehl an. Frueher lief die Schleife mit allen ~40 PIDs weiter (bei einem
                  // stillen Adapter 40 x 5 s) und meldete am Ende "keine Daten", obwohl der Adapter nie geantwortet hatte.
                  withContext(NonCancellable) { onLinkBroken(controller) }
                  throw e
              }
              catch (e: ObdException.ConnectionLost)  {
                  withContext(NonCancellable) { onLinkBroken(controller) }
                  throw e
              } ?: continue
            val value = pidParser.parseFreezeFrame(pid, raw) ?: continue
            // Die Einheit gehoert NICHT in den Formatstring: "%" (Last, Gaspedal, Trims, Tank) waere sonst ein
            // unvollstaendiger Platzhalter und wirft UnknownFormatConversionException.
            result[pid.id] = String.format(Locale.getDefault(), "%.1f", value) + " ${pid.unit}"
        }
        return result
    }

    /**
     * Die Fahrgestellnummer (17 Zeichen) oder `null`, wenn das Fahrzeug keine liefert (viele
     * Motorraeder kennen Modus 09 nicht) oder die Antwort keine FIN ist — nie der rohe Adaptertext.
     */
    suspend fun readVin(controller: ElmController): String? = VinDecoder.decode(controller.sendCommand("0902"))
}
