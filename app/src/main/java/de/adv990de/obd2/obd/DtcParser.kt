package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.DtcEntry

class DtcParser {

    /**
     * Parses a raw mode 03 (confirmed) or mode 07 (pending) DTC response.
     * ELM327 format: "43 BB BB BB..." where BB BB = one DTC each.
     * The ELM327 firmware omits the OBD count byte from its formatted output.
     */
    fun parse(raw: String, isPending: Boolean): List<DtcEntry> {
        val tokens = raw.trim().uppercase().split("\\s+".toRegex())
        if (tokens.size < 2) return emptyList()

        // Validate mode byte: "43" for confirmed DTCs (mode 03), "47" for pending (mode 07)
        val expectedPrefix = if (isPending) "47" else "43"
        if (tokens[0] != expectedPrefix) return emptyList()

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
}
