package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException

class DtcParser {

    /**
     * Parses a raw mode 03 (confirmed) or mode 07 (pending) DTC response.
     * ELM327 format: "43 BB BB BB..." where BB BB = one DTC each.
     * The ELM327 firmware omits the OBD count byte from its formatted output.
     *
     * Nur eine POSITIVE Antwort (`43`/`47` am Anfang, auch ohne Codes: "43 00") ist eine Aussage des Steuergeraets.
     * Alles andere wirft statt einer leeren Liste (A9, Teil 1) — eine leere Liste sah fuer die Oberflaeche wie
     * "keine Fehlercodes" aus, und das gruene "Keine Fehlercodes vorhanden" erschien auch bei NO DATA, UNABLE TO
     * CONNECT, CAN ERROR oder "?":
     *  - `NO DATA` -> [ObdException.NoData] (das Steuergeraet hat nicht geantwortet; ob Codes gespeichert sind, ist unbekannt),
     *  - Busfehler des Adapters -> [ObdException.BusInit],
     *  - alles andere (auch eine Antwort auf die andere Frage, Muell, leer) -> [ObdException.UnknownResponse] mit dem Rohtext.
     * Die Meldungen "SEARCHING..." und "BUS INIT: OK" vor einer Antwort werden ignoriert.
     *
     * Noch offen (A9, Teil 2, braucht ein Roh-Log vom Fahrzeug): ein Anzahl-Byte nach `43` auf CAN und Mehrframe-Antworten.
     */
    fun parse(raw: String, isPending: Boolean): List<DtcEntry> {
        val text = BANNER.replace(raw.trim().uppercase(), "").trim()
        if (text.isEmpty()) throw ObdException.UnknownResponse(raw)
        val tokens = text.split(WHITESPACE)

        // Validate mode byte: "43" for confirmed DTCs (mode 03), "47" for pending (mode 07)
        val expectedPrefix = if (isPending) "47" else "43"
        if (tokens[0] != expectedPrefix) throw notAPositiveAnswer(text, raw, if (isPending) "07" else "03")

        // Rest = DTC bytes in pairs (no count byte in ELM327 responses)
        val dtcBytes = tokens.drop(1)
        val dtcs = mutableListOf<DtcEntry>()

        var i = 0
        while (i + 1 < dtcBytes.size) {
            val high = dtcBytes[i].toIntOrNull(16)?.takeIf { it in 0..255 }
            val low = dtcBytes[i + 1].toIntOrNull(16)?.takeIf { it in 0..255 }
            i += 2  // always advance; invalid pairs are skipped via continue below

            if (high == null || low == null) continue
            if (high == 0 && low == 0) continue

            val typeChar = when (high and 0xC0) {
                0x00 -> 'P'
                0x40 -> 'C'
                0x80 -> 'B'
                0xC0 -> 'U'
                else -> 'P'
            }
            val numericPart = String.format("%04X", ((high and 0x3F) shl 8) or low)
            val code = "$typeChar$numericPart"

            dtcs.add(DtcEntry(code = code, description = dtcDescription(code), isPending = isPending))
        }
        return dtcs
    }

    /** Welche Art "keine positive Antwort" ist das? [text] ist die Antwort ohne Meldungen, [raw] der Rohtext fuers Log. */
    private fun notAPositiveAnswer(text: String, raw: String, mode: String): ObdException = when {
        text.startsWith("NO DATA") -> ObdException.NoData(mode)
        BUS_ERRORS.any { text.contains(it) } -> ObdException.BusInit(raw.trim())
        else -> ObdException.UnknownResponse(raw)
    }

    private companion object {
        /** Was der ELM327 VOR eine echte Antwort stellt: Protokollsuche und gelungene Bus-Initialisierung (s. `rangeDataBytes` (PidDiscovery.kt)). */
        val BANNER = Regex("""(SEARCHING\.\.\.|BUS INIT:\s*(\.\.\.)?\s*OK)\s*""")

        /** Busfehler des Adapters; "BUS INIT: ...ERROR" faellt hierunter, "BUS INIT: OK" wurde schon als Meldung entfernt. */
        val BUS_ERRORS = listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "FB ERROR", "BUS INIT")
    }
}
