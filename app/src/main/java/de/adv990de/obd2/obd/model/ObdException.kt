package de.adv990de.obd2.obd.model

sealed class ObdException(message: String) : Exception(message) {
    class InitializationFailed(msg: String) : ObdException(msg)
    class NoData(pid: String) : ObdException("NO DATA for PID $pid")
    class UnknownResponse(raw: String) : ObdException("Unknown response: $raw")
    class BusInit : ObdException("BUS INIT error – vehicle bus not reachable")
    class Timeout(command: String) : ObdException("Read timeout for command $command")
    class ConnectionLost : ObdException("Connection to adapter lost")
}
