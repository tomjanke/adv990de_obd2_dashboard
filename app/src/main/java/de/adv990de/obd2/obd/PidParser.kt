package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue

class PidParser {

    /**
     * Parses a raw ELM327 response string for a given PID.
     * Expected format: "41 XX [data bytes...]"
     */
    fun parse(pid: PidDefinition, raw: String): SensorValue {
        val trimmed = raw.trim().uppercase()

        when {
            trimmed.contains("NO DATA") -> throw ObdException.NoData(pid.id)
            trimmed.contains("BUS INIT") -> throw ObdException.BusInit()
            trimmed.isBlank() -> throw ObdException.UnknownResponse(raw)
        }

        val tokens = trimmed.split("\\s+".toRegex())

        // Response starts with "41" (mode 01 response) + PID byte, then data bytes
        val dataStart = 2
        if (tokens.size <= dataStart) throw ObdException.UnknownResponse(raw)

        // Validate the echoed PID byte matches what we requested (e.g. "010C" -> expect "0C")
        val expectedPid = pid.id.takeLast(2)
        val responsePid = tokens.getOrNull(1) ?: throw ObdException.UnknownResponse(raw)
        if (!responsePid.equals(expectedPid, ignoreCase = true)) {
            throw ObdException.UnknownResponse(raw)
        }

        val dataBytes = try {
            tokens.drop(dataStart).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            throw ObdException.UnknownResponse(raw)
        }

        val value = pid.formula(dataBytes)
        return SensorValue(pid = pid, value = value)
    }
}
