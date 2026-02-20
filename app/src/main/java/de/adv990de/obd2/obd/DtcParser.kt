package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.DtcEntry

class DtcParser {

    /**
     * Parses a raw mode 03 (confirmed) or mode 07 (pending) DTC response.
     * Format: "43 NN BB BB BB..." where NN = count, BB BB = one DTC each.
     */
    fun parse(raw: String, isPending: Boolean): List<DtcEntry> {
        val tokens = raw.trim().uppercase().split("\\s+".toRegex())
        if (tokens.size < 2) return emptyList()

        // tokens[0] = "43"/"47", rest = DTC bytes in pairs (no count byte in ELM327 responses)
        val dtcBytes = tokens.drop(1)
        val dtcs = mutableListOf<DtcEntry>()

        var i = 0
        while (i + 1 < dtcBytes.size) {
            val high = dtcBytes[i].toIntOrNull(16) ?: break
            val low = dtcBytes[i + 1].toIntOrNull(16) ?: break

            if (high == 0 && low == 0) { i += 2; continue }

            val typeChar = when (high and 0xC0) {
                0x00 -> 'P'
                0x40 -> 'C'
                0x80 -> 'B'
                0xC0 -> 'U'
                else -> 'P'
            }
            val numericPart = String.format("%04X", ((high and 0x3F) shl 8) or low)
            val code = "$typeChar$numericPart"

            dtcs.add(DtcEntry(code = code, description = descriptionFor(code), isPending = isPending))
            i += 2
        }
        return dtcs
    }

    /** Returns human-readable description. Extend as needed. */
    private fun descriptionFor(code: String): String = when (code) {
        "P0300" -> "Random/Multiple Cylinder Misfire Detected"
        "P0301" -> "Cylinder 1 Misfire Detected"
        "P0302" -> "Cylinder 2 Misfire Detected"
        "P0420" -> "Catalyst System Efficiency Below Threshold (Bank 1)"
        else -> "Unknown DTC"
    }
}
