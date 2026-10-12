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
            (confirmed + pending).distinctBy { it.code }
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
