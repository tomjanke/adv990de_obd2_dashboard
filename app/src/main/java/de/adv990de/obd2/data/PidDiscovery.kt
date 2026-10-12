package de.adv990de.obd2.data

import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.WHITESPACE
import de.adv990de.obd2.obd.model.ObdException

/*
 * Die Erkennung der vom Steuergeraet unterstuetzten PIDs, aus `ObdRepository` herausgeloest (die Datei war 490 Zeilen lang). Reine
 * Verschiebung — `ObdRepository.connectLocked` ruft [discoverSupportedPids] beim Verbinden.
 */

/**
 * Die vier Bitmasken-Bytes aus der Antwort auf eine Bereichsabfrage (`0100`/`0120`/`0140`), oder null.
 *
 * Erwartet wird `41 <Bereich> B1 B2 B3 B4` — aber nicht zwingend am Anfang: Der erste OBD-Befehl nach ATSP0 loest die
 * Protokollsuche aus, der ELM327 stellt dann "SEARCHING..." (bei ISO/KWP auch "BUS INIT: OK") vor die Antwort.
 * Bis 1.24.5 musste "41" das allererste Token sein — mit dem Banner ging der ganze Bereich 01-20 verloren (B8).
 * Gesucht wird deshalb der erste Eintrag `41 <Bereich>`. Dass das zweite Token der abgefragte Bereich ist, schliesst
 * zugleich eine verspaetet eintreffende Antwort auf einen ANDEREN Bereich aus (deren Bits gehoeren zu anderen PIDs).
 * Zu kurze Antworten ergeben null statt geratener Bytes.
 */
internal fun rangeDataBytes(raw: String, base: Int): List<String>? {
    val tokens = raw.trim().uppercase().split(WHITESPACE)
    val rangeToken = "%02X".format(base)
    val header = tokens.indices.firstOrNull { i ->
        tokens[i] == "41" && tokens.getOrNull(i + 1) == rangeToken
    } ?: return null
    val data = tokens.drop(header + 2).take(4)
    return data.takeIf { it.size == 4 }
}

/**
 * Queries OBD2 PID support ranges (0100/0120/0140) and returns the subset of
 * [PidRegistry.all] keys that the ECU reports as supported.
 *
 * Returns null when discovery fails entirely or yields no known PIDs — the caller
 * treats null as "show all PIDs" to avoid presenting an empty config screen.
 *
 * Bitmap encoding (SAE J1979): each 4-byte response encodes 32 PIDs as a
 * big-endian mask. Bit 31 (MSB of byte 0) → PID base+1; bit 0 (LSB of byte 3)
 * → PID base+32 (the continuation flag for the next range — not a real sensor
 * PID and therefore not present in PidRegistry.all).
 *
 * Note: initialize() sends ATS1 (spaces ON): every parser splits at whitespace. (Until 1.24.5 it sent ATS0 and
 * the comment here claimed that the split "handles both forms" — it does not; B9.)
 */
internal suspend fun discoverSupportedPids(controller: ElmController): Set<String>? {
    val ranges = listOf("0100" to 0x00, "0120" to 0x20, "0140" to 0x40)
    val discovered = mutableSetOf<String>()
    for ((command, base) in ranges) {
        val raw = try {
            controller.sendCommand(command)
        } catch (_: ObdException.NoData)          { continue }
          catch (_: ObdException.Timeout)         { continue }
          catch (_: ObdException.UnknownResponse) { continue }
          catch (_: ObdException.BusInit)         { continue }
        val dataBytes = rangeDataBytes(raw, base) ?: continue
        val bitmap = try {
            dataBytes.fold(0L) { acc, t -> (acc shl 8) or t.toLong(16) }
        } catch (_: NumberFormatException) { continue }
        for (i in 31 downTo 0) {
            if ((bitmap shr i) and 1L == 1L) {
                val pidNum = base + (31 - i) + 1
                val pidId = "01%02X".format(pidNum)
                if (pidId in PidRegistry.all) discovered.add(pidId)
            }
        }
    }
    return discovered.takeIf { it.isNotEmpty() }
}
