package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.VinDecoder
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/** Nur relevant fuer direkte/Test-Aufrufer ohne eigenen Wert — die Produktion (ReadingsPipeline)
 *  uebergibt immer explizit den aus AppPreferences gelesenen, user-konfigurierten Wert. Bewusst aus
 *  dem AppPreferences-Default abgeleitet statt unabhaengig gepflegt: die `livePids(any())`-Stubs der
 *  JVM-Tests zeichnen diesen Default mit auf und matchen den Produktionsaufruf nur, solange beide
 *  Werte gleich sind. */
private const val DEFAULT_WATCHDOG_TIMEOUT_MS = AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS * 1_000L

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

    private val _supportedPidIds = MutableStateFlow<Set<String>?>(null)
    val supportedPidIds: StateFlow<Set<String>?> = _supportedPidIds.asStateFlow()

    /**
     * The last device that successfully connected in this process lifetime.
     * Null on cold launch until the first successful connect() call.
     * Not cleared by disconnect() — the reconnect path relies on this value being retained.
     */
    private val _lastConnectedDevice = MutableStateFlow<ScannedDevice?>(null)
    val lastConnectedDevice: StateFlow<ScannedDevice?> = _lastConnectedDevice.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMovingFlow: StateFlow<Boolean> = _isMoving.asStateFlow()

    fun setMoving(moving: Boolean) {
        if (_lastConnectedDevice.value?.type == BtType.SIMULATOR) return
        _isMoving.value = moving
    }

    /** Last complete poll cycle values — updated by DashboardViewModel, cleared on disconnect. */
    private val _latestReadings = MutableStateFlow<List<SensorValue>>(emptyList())
    val latestReadings: StateFlow<List<SensorValue>> = _latestReadings.asStateFlow()

    fun updateLatestReadings(readings: List<SensorValue>) {
        _latestReadings.value = readings
    }

    suspend fun connect(device: ScannedDevice) = connectMutex.withLock {
        connectLocked(device)
    }

    /**
     * Zaehlt die Trennungen, die der NUTZER ausgeloest hat ([disconnectByUser]) — im Gegensatz zu einem
     * verlorenen Link, bei dem das Repository selbst intern [disconnect] ruft. Wer automatisch
     * wiederverbindet oder einen Verbindungsverlust meldet, vergleicht den Zaehlerstand von vorher mit
     * dem von jetzt: Hat er sich geaendert, wollte der Nutzer getrennt sein.
     */
    private val _userDisconnectCount = MutableStateFlow(0)
    val userDisconnectCount: StateFlow<Int> = _userDisconnectCount.asStateFlow()

    /**
     * Trennt auf Wunsch des Nutzers ("Trennen" in der Benachrichtigung oder den Einstellungen, "Beenden").
     * Der Zaehler steigt VOR dem Warten auf den Lock: Laeuft gerade ein Verbindungsversuch (er haelt den
     * Lock, solange er dauert), ist der Wunsch trotzdem sofort sichtbar und die Wiederverbindungs-Schleife
     * kann aufhoeren, statt nach dem Versuch die gerade getrennte Verbindung wieder aufzubauen.
     */
    suspend fun disconnectByUser() {
        _userDisconnectCount.update { it + 1 }
        disconnect()
    }

    suspend fun disconnect() = connectMutex.withLock {
        _isConnected.value = false
        _isMoving.value = false
        _supportedPidIds.value = null
        _latestReadings.value = emptyList()
        connection?.close()
        connection = null
        elm = null
        // _lastConnectedDevice is intentionally NOT cleared: the reconnect path in
        // DashboardViewModel calls reconnect() after a connection drop and relies on this
        // value being retained. Clearing it here would silently break warm-launch auto-reconnect.
    }

    /**
     * Reconnects to the last successfully connected device — unless a connection is up by the time the
     * lock is acquired, in which case it does nothing.
     *
     * The check HAS to happen inside [connectMutex]: a caller that tests [isConnectedFlow] first has no
     * protection against a manual [connect] that finishes while this call is queued behind it, and
     * [connectLocked] starts by closing whatever connection exists — it would tear down the fresh one.
     * (Link loss and the watchdog both call [disconnect] before they report, so "connected" here always
     * means a connection somebody established deliberately.)
     * Throws [IllegalStateException] if no device has ever connected in this session.
     */
    suspend fun reconnect() = connectMutex.withLock {
        if (_isConnected.value) return@withLock
        val dev = _lastConnectedDevice.value
            ?: throw IllegalStateException("No previous device to reconnect to")
        connectLocked(dev)
    }

    /**
     * Connects to [device] unless a connection is already up (decided inside the lock, see [reconnect]).
     * For the AUTOMATIC paths (auto-connect at start, reconnect loop after a cold start): they must never
     * replace what the user connected in the meantime. A deliberate [connect] from the settings screen
     * stays unconditional — the user may well be switching adapters.
     */
    suspend fun connectIfDisconnected(device: ScannedDevice) = connectMutex.withLock {
        if (_isConnected.value) return@withLock
        connectLocked(device)
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
            // Best-effort: a discovery that merely fails or finds nothing must not abort the connection.
            // Two things are NOT "merely failing" and must not be swallowed: a cancelled caller (it would
            // otherwise come back from connect() "connected" although it was cancelled) and a dead link
            // (the connection would be declared up on a socket that is already gone). Both fall into the
            // catch below, which closes the connection.
            _supportedPidIds.value = try {
                discoverSupportedPids(controller)
            } catch (e: Exception) {
                if (e is CancellationException || e is ObdException.ConnectionLost) throw e
                null
            }
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
     * Note: ATS0 (spaces off) is sent during initialize(). Most adapters still
     * include spaces in mode-01 responses; the whitespace split handles both forms.
     */
    private suspend fun discoverSupportedPids(controller: ElmController): Set<String>? {
        val ranges = listOf("0100" to 0x00, "0120" to 0x20, "0140" to 0x40)
        val discovered = mutableSetOf<String>()
        for ((command, base) in ranges) {
            val raw = try {
                controller.sendCommand(command)
            } catch (_: ObdException.NoData)          { continue }
              catch (_: ObdException.Timeout)         { continue }
              catch (_: ObdException.UnknownResponse) { continue }
              catch (_: ObdException.BusInit)         { continue }
            val tokens = raw.trim().uppercase().split("\\s+".toRegex())
            // "41 XX B1 B2 B3 B4" → at least 6 tokens
            if (tokens.size < 6 || tokens[0] != "41") continue
            val bitmap = try {
                tokens.drop(2).take(4).fold(0L) { acc, t -> (acc shl 8) or t.toLong(16) }
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

    /**
     * Continuously polls the given PIDs and emits the latest values.
     * Skips PIDs that return NO DATA without stopping the stream.
     *
     * Two independent escalation paths can force a reconnect, whichever crosses its own
     * threshold first: 5 consecutive BusInit errors (bus stuck), or the watchdog: a cycle without
     * a single reading that hit at least one Timeout or UnknownResponse, at a point where
     * [watchdogTimeoutMs] of real time have passed since the link last showed it works. That is
     * the last cycle with readings, or one the ECU answered entirely with NO DATA (e.g. ignition
     * off): a well-formed answer, so such a cycle restarts the clock rather than counting against
     * it, and NoData alone never trips the watchdog. It catches a silently frozen adapter/BLE
     * stack that keeps timing out without ever hitting BusInit, and an adapter that still answers
     * but only unparseably (e.g. echo back on after it reset itself), which a reconnect cures by
     * re-running initialize(). The check runs once per cycle, so it fires up to one cycle after
     * the threshold. See [isReadingsStale].
     *
     * [timeSource] only exists so tests can inject kotlinx-coroutines-test's virtual clock
     * (`testTimeSource`); production uses the default [TimeSource.Monotonic]. It has to be real
     * time: a single Timeout already blocks for ElmController's 5 s read timeout, so the earlier
     * approach of adding [pollIntervalMs] per empty cycle undercounted a real stall 30-40x.
     */
    fun livePids(
        pids: List<PidDefinition> = PidRegistry.defaults,
        pollIntervalMs: Long = 500L,
        watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
        timeSource: TimeSource = TimeSource.Monotonic,
    ): Flow<List<SensorValue>> = flow {
        var consecutiveBusInitErrors = 0
        var lastSuccessMark = timeSource.markNow()
        while (true) {
            // elm is @Volatile; capture into a local so the reference stays alive even if
            // a concurrent disconnect() nulls elm after the null check.
            val controller = elm ?: break
            val values = mutableListOf<SensorValue>()
            var busInitOccurred = false
            var timeoutOrUnknownOccurred = false
            for (pid in pids) {
                try {
                    val raw = controller.sendCommand(pid.id)
                    values.add(pidParser.parse(pid, raw))
                } catch (_: ObdException.NoData) {
                    // Benign: the ECU explicitly has nothing to report for this PID, so the link
                    // itself demonstrably works. A cycle of nothing else restarts the watchdog below.
                } catch (_: ObdException.UnknownResponse) {
                    // The adapter answered, but unparseably. Once persistent (e.g. echo back on
                    // after the adapter reset itself), no reading gets through: counts for the watchdog.
                    timeoutOrUnknownOccurred = true
                } catch (_: ObdException.Timeout) {
                    // skip this PID this cycle — adapter may be slow; counts for the watchdog
                    timeoutOrUnknownOccurred = true
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
            // Silent drop: when all PIDs return NoData/Timeout/UnknownResponse in a cycle, nothing
            // is emitted. The collector sees no update for this cycle; the UI retains the last values.
            // Also suppress partial-cycle values collected before a BusInit interrupted the loop —
            // emitting them would show a stale mix of fresh and un-refreshed PID readings.
            if (!busInitOccurred && values.isNotEmpty()) {
                lastSuccessMark = timeSource.markNow()
                emit(values)
            } else if (timeoutOrUnknownOccurred) {
                // The watchdog. A NoData from another PID in the same cycle does not offset the
                // Timeout/UnknownResponse. BusInit keeps its own 5-strike counter above.
                if (isReadingsStale(lastSuccessMark.elapsedNow().inWholeMilliseconds, watchdogTimeoutMs)) {
                    withContext(NonCancellable) { disconnect() }
                    throw ObdException.ConnectionLost()
                }
            } else if (!busInitOccurred) {
                // Entirely NoData this cycle: the link works, so nothing to escalate, but nothing
                // to emit either. Restart the watchdog's clock anyway, so a later isolated Timeout
                // is judged against this recent baseline, not against the last reading from before
                // a long, benign NO DATA stretch (e.g. parked, ignition off), which would escalate
                // on the spot.
                lastSuccessMark = timeSource.markNow()
            }
            delay(pollIntervalMs)
        }
    }

    suspend fun readDtcs(): List<DtcEntry> {
        val controller = elm ?: return emptyList()
        return try {
            val confirmed = controller.sendCommand("03")
            val pending   = controller.sendCommand("07")
            // Ein Code kann in beiden Antworten stehen (bestaetigt UND noch ausstehend) oder von zwei
            // Steuergeraeten doppelt gemeldet werden. Eine Zeile je Code: die DTC-Seite nutzt den Code
            // als LazyColumn-Key, ein Duplikat laesst sie abstuerzen. Bestaetigt steht zuerst und gewinnt.
            (dtcParser.parse(confirmed, isPending = false) +
                    dtcParser.parse(pending, isPending = true))
                .distinctBy { it.code }
        } catch (e: ObdException.Timeout) {
            // A timeout leaves the serial stream in an unknown state: the stale response may
            // arrive during the next sendCommand and corrupt it. Disconnect now so the stream
            // is fully reset before the next caller sends anything.
            withContext(NonCancellable) { disconnect() }
            throw e
        } catch (e: ObdException.ConnectionLost) {
            // Wie livePids() und readFreezeFrame(): ein verlorener Link wird nie als "verbunden" stehen
            // gelassen — sonst haelt isConnectedFlow einen toten Adapter fuer lebendig.
            withContext(NonCancellable) { disconnect() }
            throw e
        }
    }

    /**
     * Reads freeze frame data (mode 02) for all known PIDs.
     * Mode 02 uses the same PID bytes as mode 01; responses carry "42" prefix.
     * Returns a map of PID id → formatted value, or empty map if no freeze frame is stored.
     * Keyed by id (not the English PidDefinition.name) so the UI can resolve the localized
     * label via PidRegistry, the same pattern used for sparklines and slot assignment.
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
            val raw = try {
                controller.sendCommand("02 ${pidByte} 00")
            } catch (_: ObdException.NoData)          { null }
              catch (_: ObdException.Timeout)         { null }
              catch (_: ObdException.UnknownResponse) { null }
              catch (_: ObdException.BusInit)         { null }
              catch (e: ObdException.ConnectionLost)  {
                  withContext(NonCancellable) { disconnect() }
                  throw e
              } ?: continue
            val value = pidParser.parseFreezeFrame(pid, raw) ?: continue
            // Die Einheit gehoert NICHT in den Formatstring: "%" (Last, Gaspedal, Trims, Tank) waere sonst ein
            // unvollstaendiger Platzhalter und wirft UnknownFormatConversionException.
            result[pid.id] = String.format(Locale.getDefault(), "%.1f", value) + " ${pid.unit}"
        }
        return result
    }

    /**
     * Die Fahrgestellnummer (17 Zeichen) oder `null`, wenn das Fahrzeug keine liefert (viele
     * Motorraeder kennen Modus 09 nicht) oder die Antwort keine FIN ist — nie der rohe Adaptertext.
     */
    suspend fun readVin(): String? {
        val controller = elm ?: return null
        return VinDecoder.decode(controller.sendCommand("0902"))
    }
}
