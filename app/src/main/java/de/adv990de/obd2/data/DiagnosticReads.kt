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
