package de.adv990de.obd2.obd.model

sealed class ObdException(message: String) : Exception(message) {
    class InitializationFailed(msg: String) : ObdException(msg)
    class NoData(pid: String) : ObdException("NO DATA for PID $pid")
    class UnknownResponse(raw: String) : ObdException("Unknown response: $raw")
    /** Der Adapter erreicht den Fahrzeugbus nicht ("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", ...). [detail] haelt den Adaptertext fuer das Log. */
    class BusInit(detail: String? = null) :
        ObdException("BUS INIT error – vehicle bus not reachable" + (detail?.let { " ($it)" } ?: ""))
    class Timeout(command: String) : ObdException("Read timeout for command $command")
    /**
     * Die Verbindung ist weg oder wird abgebrochen. [reason] nennt, WARUM die App sie beendet, wenn sie selbst es war (heute: eingefrorene Werte,
     * `FrozenValuesGuard`) — der Text steht im App-Log; fehlt er, hat der Link versagt (Lesefehler, Watchdog, Busfehler).
     */
    class ConnectionLost(val reason: String? = null) : ObdException("Connection to adapter lost")
}
