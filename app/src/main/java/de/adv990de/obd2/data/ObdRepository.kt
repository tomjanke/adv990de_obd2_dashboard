package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObdRepository @Inject constructor(
    private val connectionFactory: ConnectionFactory,
    private val pidParser: PidParser,
    private val dtcParser: DtcParser
) {

    private var connection: ObdConnection? = null
    private var elm: ElmController? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    suspend fun connect(device: ScannedDevice) {
        val conn = connectionFactory.create(device)
        val controller = conn.connect()
        controller.initialize()
        connection = conn
        elm = controller
        _isConnected.value = true
    }

    fun disconnect() {
        _isConnected.value = false
        connection?.close()
        connection = null
        elm = null
    }

    val isConnected: Boolean get() = connection?.isConnected == true

    /**
     * Continuously polls the given PIDs and emits the latest values.
     * Skips PIDs that return NO DATA without stopping the stream.
     */
    fun livePids(
        pids: List<PidDefinition> = PidRegistry.defaults,
        pollIntervalMs: Long = 500L
    ): Flow<List<SensorValue>> = flow {
        while (true) {
            val controller = elm ?: break
            val values = mutableListOf<SensorValue>()
            for (pid in pids) {
                try {
                    val raw = controller.sendCommand(pid.id)
                    values.add(pidParser.parse(pid, raw))
                } catch (_: ObdException.NoData) {
                    // skip this PID this cycle
                }
            }
            if (values.isNotEmpty()) emit(values)
            delay(pollIntervalMs)
        }
    }

    suspend fun readDtcs(): List<DtcEntry> {
        val controller = elm ?: return emptyList()
        val confirmed = controller.sendCommand("03")
        val pending = controller.sendCommand("07")
        return dtcParser.parse(confirmed, isPending = false) +
                dtcParser.parse(pending, isPending = true)
    }

    suspend fun readFreezeFrame(): Map<String, String> {
        val controller = elm ?: return emptyMap()
        val raw = controller.sendCommand("0202")
        return mapOf("raw" to raw)
    }

    suspend fun readVin(): String? {
        val controller = elm ?: return null
        return controller.sendCommand("0902")
    }
}
