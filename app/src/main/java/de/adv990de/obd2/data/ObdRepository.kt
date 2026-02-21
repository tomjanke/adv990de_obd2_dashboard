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
    private val connectMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * The last device that successfully connected in this process lifetime.
     * Null on cold launch until the first successful connect() call.
     * Not cleared by disconnect() — the reconnect path relies on this value being retained.
     */
    private val _lastConnectedDevice = MutableStateFlow<ScannedDevice?>(null)
    val lastConnectedDevice: StateFlow<ScannedDevice?> = _lastConnectedDevice.asStateFlow()

    suspend fun connect(device: ScannedDevice) = connectMutex.withLock {
        connectLocked(device)
    }

    suspend fun disconnect() = connectMutex.withLock {
        _isConnected.value = false
        connection?.close()
        connection = null
        elm = null
        // _lastConnectedDevice is intentionally NOT cleared: the reconnect path in
        // DashboardViewModel calls reconnect() after a connection drop and relies on this
        // value being retained. Clearing it here would silently break warm-launch auto-reconnect.
    }

    /**
     * Reconnects to the last successfully connected device.
     * Acquires [connectMutex] to protect the lastDevice read-then-connect sequence against
     * a concurrent [connect] call that could close the newly established connection.
     * Throws [IllegalStateException] if no device has ever connected in this session.
     */
    suspend fun reconnect() = connectMutex.withLock {
        val dev = _lastConnectedDevice.value
            ?: throw IllegalStateException("No previous device to reconnect to")
        connectLocked(dev)
    }

    /** Must only be called while [connectMutex] is held. */
    private suspend fun connectLocked(device: ScannedDevice) {
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
            _lastConnectedDevice.value = device
        } catch (e: Exception) {
            conn.close()   // prevent leak if connect() or initialize() throws
            throw e
        }
    }

    /**
     * Continuously polls the given PIDs and emits the latest values.
     * Skips PIDs that return NO DATA without stopping the stream.
     */
    fun livePids(
        pids: List<PidDefinition> = PidRegistry.defaults,
        pollIntervalMs: Long = 500L
    ): Flow<List<SensorValue>> = flow {
        var consecutiveBusInitErrors = 0
        while (true) {
            // elm is @Volatile; capture into a local so the reference stays alive even if
            // a concurrent disconnect() nulls elm after the null check.
            val controller = elm ?: break
            val values = mutableListOf<SensorValue>()
            var busInitOccurred = false
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
                    busInitOccurred = true
                    break  // bus re-initializing; skip rest of this poll cycle
                } catch (e: ObdException.ConnectionLost) {
                    // disconnect() acquires connectMutex. If reconnect() is currently holding
                    // that mutex (i.e., is mid-BT-connect), this NonCancellable block will
                    // suspend until reconnect() releases the lock. Self-resolving, not a true
                    // deadlock, but the NonCancellable section may run longer than expected.
                    withContext(NonCancellable) { disconnect() }
                    throw e  // fatal — propagate to ViewModel
                }
            }
            if (busInitOccurred) {
                consecutiveBusInitErrors++
                // After 5 consecutive BusInit errors the bus is stuck — escalate to ConnectionLost
                // so the ViewModel triggers auto-reconnect instead of polling silently forever.
                if (consecutiveBusInitErrors >= 5) {
                    withContext(NonCancellable) { disconnect() }
                    throw ObdException.ConnectionLost()
                }
            } else {
                consecutiveBusInitErrors = 0
            }
            // Silent drop: when all PIDs return NoData/Timeout in a cycle, nothing is emitted.
            // The collector sees no update for this cycle; the UI retains the last values.
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
            // ELM327 mode-02 command: space-separated "02 <PID> <FF>" for widest clone
            // compatibility — many clones reject packed form "02<PID><FF>" without spaces.
            // BUS INIT / NO DATA responses are not thrown here; sendCommand returns the raw
            // string which parseFreezeFrame will reject (returning null). This is intentional:
            // a one-shot diagnostic read does not escalate transient bus errors to ConnectionLost.
            val raw = try { controller.sendCommand("02 ${pidByte} 00") } catch (_: ObdException) { null } ?: continue
            val value = pidParser.parseFreezeFrame(pid, raw) ?: continue
            result[pid.name] = "%.1f ${pid.unit}".format(value)
        }
        return result
    }

    suspend fun readVin(): String? {
        val controller = elm ?: return null
        return controller.sendCommand("0902")
    }
}
