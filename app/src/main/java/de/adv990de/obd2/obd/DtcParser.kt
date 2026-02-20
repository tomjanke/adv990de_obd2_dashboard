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
            val high = dtcBytes[i].toIntOrNull(16)?.takeIf { it in 0..255 } ?: break
            val low = dtcBytes[i + 1].toIntOrNull(16)?.takeIf { it in 0..255 } ?: break

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
        "P0100" -> "Mass or Volume Air Flow Circuit Malfunction"
        "P0101" -> "Mass or Volume Air Flow Circuit Range/Performance"
        "P0102" -> "Mass or Volume Air Flow Circuit Low Input"
        "P0103" -> "Mass or Volume Air Flow Circuit High Input"
        "P0110" -> "Intake Air Temperature Circuit Malfunction"
        "P0115" -> "Engine Coolant Temperature Circuit Malfunction"
        "P0120" -> "Throttle/Pedal Position Sensor A Circuit Malfunction"
        "P0130" -> "O2 Sensor Circuit Malfunction (Bank 1, Sensor 1)"
        "P0171" -> "System Too Lean (Bank 1)"
        "P0172" -> "System Too Rich (Bank 1)"
        "P0175" -> "System Too Rich (Bank 2)"
        "P0200" -> "Injector Circuit Malfunction"
        "P0300" -> "Random/Multiple Cylinder Misfire Detected"
        "P0301" -> "Cylinder 1 Misfire Detected"
        "P0302" -> "Cylinder 2 Misfire Detected"
        "P0303" -> "Cylinder 3 Misfire Detected"
        "P0304" -> "Cylinder 4 Misfire Detected"
        "P0305" -> "Cylinder 5 Misfire Detected"
        "P0306" -> "Cylinder 6 Misfire Detected"
        "P0325" -> "Knock Sensor 1 Circuit Malfunction (Bank 1)"
        "P0335" -> "Crankshaft Position Sensor A Circuit Malfunction"
        "P0340" -> "Camshaft Position Sensor A Circuit Malfunction (Bank 1)"
        "P0400" -> "Exhaust Gas Recirculation Flow Malfunction"
        "P0401" -> "Exhaust Gas Recirculation Flow Insufficient"
        "P0420" -> "Catalyst System Efficiency Below Threshold (Bank 1)"
        "P0421" -> "Warm Up Catalyst Efficiency Below Threshold (Bank 1)"
        "P0430" -> "Catalyst System Efficiency Below Threshold (Bank 2)"
        "P0440" -> "Evaporative Emission Control System Malfunction"
        "P0442" -> "Evaporative Emission Control System Leak Detected (Small)"
        "P0455" -> "Evaporative Emission Control System Leak Detected (Gross)"
        "P0500" -> "Vehicle Speed Sensor Malfunction"
        "P0505" -> "Idle Control System Malfunction"
        "P0600" -> "Serial Communication Link Malfunction"
        "P0700" -> "Transmission Control System Malfunction"
        "P0711" -> "Transmission Fluid Temperature Sensor Circuit Range/Performance"
        "P0730" -> "Incorrect Gear Ratio"
        "P0740" -> "Torque Converter Clutch Circuit Malfunction"
        else -> "Unknown DTC"
    }
}
