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
            // "STOPPED" is returned by some clone adapters when the bus gets stuck — treat
            // it as BusInit so the escalation counter in livePids increments correctly.
            trimmed.contains("STOPPED") -> throw ObdException.BusInit()
            trimmed.isBlank() -> throw ObdException.UnknownResponse(raw)
        }

        val tokens = trimmed.split("\\s+".toRegex())

        // Response starts with "41" (mode 01 response) + PID byte, then data bytes
        val dataStart = 2
        if (tokens.size <= dataStart) throw ObdException.UnknownResponse(raw)
        // Reject mode-02/09/etc. responses that slipped through (e.g. "42 0C …" received when
        // mode-01 was requested). parseFreezeFrame validates its own "42" header the same way.
        if (tokens[0] != "41") throw ObdException.UnknownResponse(raw)

        // Validate the echoed PID byte matches what we requested (e.g. "010C" -> expect "0C")
        val expectedPid = pid.id.takeLast(2)
        val responsePid = tokens[1]  // safe: size guard above ensures tokens.size >= 3
        if (!responsePid.equals(expectedPid, ignoreCase = true)) {
            throw ObdException.UnknownResponse(raw)
        }

        val dataBytes = try {
            tokens.drop(dataStart).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            throw ObdException.UnknownResponse(raw)
        }

        val value = try {
            pid.formula(dataBytes)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw ObdException.UnknownResponse(raw)
        }
        return SensorValue(pid = pid, value = value)
    }

    /**
     * Parses a raw ELM327 mode-02 (freeze frame) response for a given PID.
     * Expected format: "42 XX [data bytes...]"
     * Returns null if the response is missing, malformed, or the formula throws.
     *
     * Adapter error strings like "NO DATA", "BUS INIT..." and "STOPPED" do not start with "42"
     * so they fail the header check and return null. This is intentional: readFreezeFrame is a
     * one-shot diagnostic read that does not escalate transient bus errors to ConnectionLost.
     */
    fun parseFreezeFrame(pid: PidDefinition, raw: String): Float? {
        val tokens = raw.trim().uppercase().split("\\s+".toRegex())
        val pidByte = pid.id.takeLast(2)
        if (tokens.size < 3 || tokens[0] != "42" || !tokens[1].equals(pidByte, ignoreCase = true)) return null
        val dataBytes = try {
            tokens.drop(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: NumberFormatException) { return null }
        return try { pid.formula(dataBytes) } catch (_: ArrayIndexOutOfBoundsException) { null }
    }
}
