package de.adv990de.obd2.obd.model

sealed class ObdException(message: String) : Exception(message) {
    class InitializationFailed(msg: String) : ObdException(msg)
    class NoData(pid: String) : ObdException("NO DATA for PID $pid")
    class UnknownResponse(raw: String) : ObdException("Unknown response: $raw")
    /** Der Adapter erreicht den Fahrzeugbus nicht ("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", ...). [detail] haelt den Adaptertext fuer das Log. */
    class BusInit(detail: String? = null) :
        ObdException("BUS INIT error – vehicle bus not reachable" + (detail?.let { " ($it)" } ?: ""))
    class Timeout(command: String) : ObdException("Read timeout for command $command")
    class ConnectionLost : ObdException("Connection to adapter lost")
}
