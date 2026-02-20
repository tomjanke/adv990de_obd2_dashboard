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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObdRepository @Inject constructor(
    private val connectionFactory: ConnectionFactory,
    private val pidParser: PidParser,
    private val dtcParser: DtcParser
) {

    @Volatile private var connection: ObdConnection? = null
    @Volatile private var elm: ElmController? = null
    @Volatile private var lastDevice: ScannedDevice? = null
    private val connectMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    suspend fun connect(device: ScannedDevice) = connectMutex.withLock {
        // Close any existing connection before replacing it
        connection?.close()
        _isConnected.value = false
        connection = null
        elm = null

        val conn = connectionFactory.create(device)
        try {
            val controller = conn.connect()
            controller.initialize()
            connection = conn
            elm = controller
            _isConnected.value = true
            lastDevice = device
        } catch (e: Exception) {
            conn.close()   // prevent leak if connect() or initialize() throws
            throw e
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        _isConnected.value = false
        connection?.close()
        connection = null
        elm = null
    }

    /**
     * Reconnects to the last successfully connected device.
     * Throws [IllegalStateException] if no device has ever connected in this session.
     */
    suspend fun reconnect() {
        val dev = lastDevice
            ?: throw IllegalStateException("No previous device to reconnect to")
        connect(dev)
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
                } catch (_: ObdException.UnknownResponse) {
                    // skip this PID this cycle (transient parse error)
                } catch (_: ObdException.Timeout) {
                    // skip this PID this cycle — adapter may be slow
                } catch (_: ObdException.BusInit) {
                    break  // bus re-initializing; skip rest of this poll cycle
                } catch (e: ObdException.ConnectionLost) {
                    withContext(NonCancellable) { disconnect() }
                    throw e  // fatal — propagate to ViewModel
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

    /**
     * Reads freeze frame data (mode 02) for all known PIDs.
     * Mode 02 uses the same PID bytes as mode 01; responses carry "42" prefix.
     * Returns a map of PID name → formatted value, or empty map if no freeze frame is stored.
     */
    suspend fun readFreezeFrame(): Map<String, String> {
        val controller = elm ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pid in PidRegistry.all.values) {
            val pidByte = pid.id.takeLast(2)
            val raw = try { controller.sendCommand("02$pidByte 00") } catch (_: ObdException) { null } ?: continue
            val tokens = raw.trim().uppercase().split("\\s+".toRegex())
            // Mode 02 response format: "42 <PID> [data bytes...]"
            if (tokens.size < 3 || tokens[0] != "42" || !tokens[1].equals(pidByte, ignoreCase = true)) continue
            val dataBytes = try {
                tokens.drop(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: NumberFormatException) { null } ?: continue
            val value = try {
                pid.formula(dataBytes)
            } catch (_: ArrayIndexOutOfBoundsException) { null } ?: continue
            result[pid.name] = "${value.toInt()} ${pid.unit}"
        }
        return result
    }

    suspend fun readVin(): String? {
        val controller = elm ?: return null
        return controller.sendCommand("0902")
    }
}
