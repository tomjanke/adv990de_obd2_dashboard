package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// DashboardUiState.disconnected() is the canonical way to reset to a disconnected/error state.
// If you add a field with a non-trivial default, update disconnected() accordingly.
private val RECONNECT_DELAYS = listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L)

private fun SensorValue.isPlausible() = value.isFinite()

data class DashboardUiState(
    val readings: List<SensorValue> = emptyList(),
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    // References RECONNECT_DELAYS.size so the default stays in sync if the list ever changes.
    val reconnectTotal: Int = RECONNECT_DELAYS.size,
    // UiText: static messages use StringResource (localizable), dynamic messages (e.g. exception
    // messages from the BT stack) use DynamicString.
    val error: UiText? = null,
    val sparklines: Map<String, List<Float>> = emptyMap(),
    val rpmValue: Float = 0f,
    // Oil temp preferred, fallback to coolant temp; null = no plausible value available
    val engineTempC: Float? = null
) {
    companion object {
        fun disconnected(error: UiText? = null) = DashboardUiState(isConnected = false, error = error)
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    private val sessionRepository: SessionRepository,
    private val appPreferences: AppPreferences,
    private val crashReporter: CrashReporter,
    private val bluetoothAdapter: BluetoothAdapter,
    private val appLogRepository: AppLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    // Placeholder readings shown when not connected: slot PIDs with value 0.
    // Keeps the dashboard tiles visible at all times so the user always sees the layout.
    val placeholderReadings: StateFlow<List<SensorValue>> = appPreferences.slotPids
        .map { ids ->
            val pids = ids.mapNotNull { PidRegistry.all[it] }.ifEmpty { PidRegistry.defaults }
            pids.map { pid -> SensorValue(pid, 0f) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** RPM blink threshold in rpm. 0 = blinking disabled. */
    val rpmBlinkThreshold: StateFlow<Int> = appPreferences.rpmBlinkThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** When true, the blink threshold is scaled down at low engine temperatures. */
    val rpmDynamicThreshold: StateFlow<Boolean> = appPreferences.rpmDynamicThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The 4 slot → PID-ID assignments, in slot order. */
    val slotPids: StateFlow<List<String>> = appPreferences.slotPids
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SLOT_PIDS)

    /** PIDs available in the picker: ECU-supported when connected, full registry otherwise. */
    val availablePids: StateFlow<List<PidDefinition>> = obdRepository.supportedPidIds
        .map { supported ->
            val all = PidRegistry.all.values.sortedBy { it.name }
            if (supported.isNullOrEmpty()) all else all.filter { it.id in supported }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PidRegistry.all.values.sortedBy { it.name }
        )

    /** Persists a slot → PID assignment. */
    fun setSlotPid(slot: Int, pidId: String) {
        viewModelScope.launch { appPreferences.setSlotPid(slot, pidId) }
    }

    private val sparklineBuffer = mutableMapOf<String, ArrayDeque<Float>>()

    // Fired when ConnectionLost is caught in the session loop.
    // Separated from flatMapLatest inner flow because flatMapLatest would cancel the inner
    // flow the moment isConnectedFlow emits false — which happens immediately when livePids()
    // calls disconnect() on ConnectionLost, killing any retry loop inside it.
    private val _reconnectTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // ── Session loop ─────────────────────────────────────────────────────
        viewModelScope.launch {
            // NOTE: combining slotPids means a slot change while connected ends the current
            // session and starts a new one. This is intentional: the DashboardConfig screen is
            // under Settings, so changes are expected to be made before/between drives.
            combine(
                obdRepository.isConnectedFlow,
                appPreferences.slotPids
            ) { connected, slotIds -> connected to slotIds }
                .flatMapLatest { (connected, slotIds) ->
                    if (!connected) {
                        flowOf(emptyList<SensorValue>() to false)
                    } else {
                        flow {
                            // Fall back to defaults if none of the stored IDs exist in PidRegistry
                            // (e.g. after a downgrade or manual DataStore wipe).
                            val dynamicThresholdEnabled = appPreferences.rpmDynamicThreshold.first()
                            val selectedPids = buildList {
                                addAll(slotIds)
                                add(PidRegistry.RPM.id)
                                if (dynamicThresholdEnabled) {
                                    add(PidRegistry.ENGINE_OIL_TEMP.id)
                                    add(PidRegistry.COOLANT_TEMP.id)
                                }
                            }
                                .mapNotNull { PidRegistry.all[it] }
                                .distinctBy { it.id }
                                .ifEmpty { PidRegistry.defaults }

                            // If SPEED is not being polled, ensure moving=false so nav is never locked hidden.
                            if (selectedPids.none { it.id == PidRegistry.SPEED.id }) {
                                obdRepository.setMoving(false)
                            }

                            // Snapshot recording preferences once per session, not per cycle.
                            val logOnlyDtc        = appPreferences.logOnlyDtcSessions.first()
                            val logOnlyDiscovered = appPreferences.logOnlyDiscoveredPids.first()
                            val loggedPidIds      = appPreferences.loggedPidIds.first()

                            val vin = try { obdRepository.readVin() }
                                      catch (e: Exception) {
                                          if (e is CancellationException) throw e
                                          null
                                      }

                            // DTC check: read on connect; log any active codes, use result
                            // for recording filter only. No UI banner — errors are visible
                            // in the App Logs screen.
                            val dtcList = try { obdRepository.readDtcs() }
                                          catch (e: Exception) {
                                              if (e is CancellationException) throw e
                                              null
                                          }
                            if (dtcList?.isNotEmpty() == true) {
                                appLogRepository.log(
                                    LogLevel.WARN,
                                    "Active DTCs on connect: ${dtcList.joinToString()}"
                                )
                            }
                            val hasDtcForRecording: Boolean = dtcList?.isNotEmpty() ?: true

                            var sessionId = -1L
                            try {
                                sessionId = sessionRepository.startSession(vin)
                                emitAll(
                                    obdRepository.livePids(selectedPids)
                                        .onEach { values ->
                                            val filtered = values
                                                .filter { sv ->
                                                    !logOnlyDiscovered ||
                                                    (obdRepository.supportedPidIds.value?.contains(sv.pid.id) ?: true)
                                                }
                                                .filter { sv -> sv.pid.id in loggedPidIds }
                                            sessionRepository.writeReadings(sessionId, filtered)
                                        }
                                        .map { it to true }
                                )
                            } catch (e: CancellationException) {
                                throw e  // never swallow coroutine cancellation
                            } catch (e: ObdException.ConnectionLost) {
                                startReconnecting()
                            } catch (e: Exception) {
                                // Set error state directly; inner flow completes cleanly so
                                // flatMapLatest outer collector survives and can recover.
                                crashReporter.recordException(e)
                                _uiState.value = DashboardUiState.disconnected(ObdErrorMapper.toUiText(e))
                            } finally {
                                // DB errors must not prevent session cleanup.
                                if (sessionId != -1L) {
                                    if (logOnlyDtc && !hasDtcForRecording) {
                                        try { sessionRepository.deleteSession(sessionId) } catch (_: Exception) {}
                                    } else {
                                        try { sessionRepository.endSession(sessionId) } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                }
                .collect { (values, connected) ->
                    if (!connected && (_uiState.value.error != null || _uiState.value.isReconnecting)) {
                        // Disconnected cleanly after an error or during reconnect — preserve state,
                        // just clear stale readings.
                        _uiState.value = _uiState.value.copy(isConnected = false, readings = emptyList())
                    } else if (connected) {
                        obdRepository.updateLatestReadings(values)
                        values.forEach { sv ->
                            val deque = sparklineBuffer.getOrPut(sv.pid.id) { ArrayDeque(60) }
                            if (deque.size >= 60) deque.removeFirst()
                            deque.addLast(sv.value)
                        }
                        // Only updates moving state when SPEED is in the selected PID set.
                        // If the user has deselected SPEED, isMovingFlow retains its last value
                        // and the auto-hide feature effectively does nothing.
                        values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
                            ?.let { obdRepository.setMoving(it.value > 0f) }
                        val displayIds = slotPids.value.toSet()
                        val engineTempC = if (rpmDynamicThreshold.value) {
                            values.firstOrNull { it.pid.id == PidRegistry.ENGINE_OIL_TEMP.id && it.isPlausible() }?.value
                                ?: values.firstOrNull { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.isPlausible() }?.value
                        } else null
                        _uiState.value = _uiState.value.copy(
                            readings = values.filter { sv -> sv.pid.id in displayIds },
                            rpmValue = values.firstOrNull { it.pid.id == PidRegistry.RPM.id }
                                           ?.value ?: _uiState.value.rpmValue,
                            engineTempC = engineTempC,
                            isConnected = true,
                            isReconnecting = false,
                            error = null,
                            sparklines = sparklineBuffer.mapValues { (_, deque) -> deque.toList() }
                        )
                    } else {
                        _uiState.value = DashboardUiState.disconnected()
                    }
                }
        }

        // ── Reconnect loop ───────────────────────────────────────────────────
        // Known TOCTOU residual: guard 2 (post-delay) and reconnect() acquiring connectMutex
        // are separated by nanoseconds of function-call overhead — a manual connect from Settings
        // completing exactly in that instant would have its connection closed by the auto-reconnect.
        // The delay() before guard 2 is up to 48 s, but the race window is between guard 2 and
        // mutex acquisition, not between guard 1 and delay(). In practice this is extremely
        // unlikely; eliminating it would require a single mutex scope across guard + reconnect,
        // which is out of scope for the current implementation.
        viewModelScope.launch {
            _reconnectTrigger.collect {
                // On cold launch, in-memory lastConnectedDevice is null; fall back to DataStore.
                // Captured once before the loop — the persisted address does not change between
                // attempts, and re-reading on every iteration would repeat the I/O.
                val inMemoryDevice = obdRepository.lastConnectedDevice.value
                val coldLaunchDevice = if (inMemoryDevice != null) null
                                       else appPreferences.lastDevice.first()
                if (inMemoryDevice == null && coldLaunchDevice == null) {
                    // No device anywhere — abort immediately instead of burning
                    // all 5 delayed attempts (93 seconds of pointless waiting).
                    appLogRepository.log(LogLevel.WARN, "No device configured")
                    _uiState.value = DashboardUiState.disconnected(UiText.StringResource(R.string.err_no_device))
                    return@collect
                }

                for ((attempt, delayMs) in RECONNECT_DELAYS.withIndex()) {
                    // User may have reconnected manually in Settings while we waited
                    if (obdRepository.isConnectedFlow.value) return@collect
                    _uiState.value = DashboardUiState(
                        isReconnecting = true,
                        reconnectAttempt = attempt + 1,
                        reconnectTotal = RECONNECT_DELAYS.size
                    )
                    appLogRepository.log(LogLevel.INFO, "Reconnect attempt ${attempt + 1}/${RECONNECT_DELAYS.size}")
                    delay(delayMs)
                    if (!bluetoothAdapter.isEnabled) {
                        appLogRepository.log(LogLevel.WARN, "Bluetooth disabled")
                        _uiState.value = DashboardUiState.disconnected(
                            UiText.StringResource(R.string.obd_err_bluetooth_disabled)
                        )
                        return@collect
                    }
                    // Second guard: user may have connected manually while we were waiting
                    if (obdRepository.isConnectedFlow.value) return@collect
                    try {
                        if (inMemoryDevice != null) {
                            obdRepository.reconnect()
                        } else {
                            obdRepository.connect(coldLaunchDevice!!)  // non-null verified above
                        }
                        // Clear reconnecting state immediately so the spinner stops as soon as
                        // the connection is confirmed. The session loop will overwrite with real
                        // readings once the first livePids emission arrives.
                        val reconnectedName = inMemoryDevice?.name ?: coldLaunchDevice?.name ?: "device"
                        appLogRepository.log(LogLevel.INFO, "Connected to $reconnectedName")
                        _uiState.value = _uiState.value.copy(isReconnecting = false, reconnectAttempt = 0)
                        return@collect  // isConnectedFlow emits true → flatMapLatest restarts
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        // try next attempt
                    }
                }
                appLogRepository.log(LogLevel.ERROR, "Connection failed after ${RECONNECT_DELAYS.size} attempts")
                _uiState.value = DashboardUiState.disconnected(UiText.StringResource(R.string.err_reconnect_fail))
            }
        }

        // ── Auto-connect on startup ─────────────────────────────────────────────
        // Fires once on cold launch: if a device was previously saved and no
        // connection is active, attempt to connect immediately (no delay).
        // On success: isConnectedFlow goes true → session loop takes over.
        // On failure: delegate to startReconnecting() → backoff loop handles retries.
        viewModelScope.launch {
            if (obdRepository.isConnectedFlow.value) return@launch
            val savedDevice = appPreferences.lastDevice.first() ?: return@launch
            _uiState.value = _uiState.value.copy(isReconnecting = true, reconnectAttempt = 0)
            appLogRepository.log(LogLevel.INFO, "Connecting to ${savedDevice.name}…")
            try {
                obdRepository.connect(savedDevice)
                appLogRepository.log(LogLevel.INFO, "Connected to ${savedDevice.name}")
                // Session loop resets isReconnecting when first readings arrive.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogRepository.log(LogLevel.ERROR, "Connection error: ${e.message ?: "unknown"}")
                startReconnecting()
            }
        }
    }

    /**
     * Sets isReconnecting synchronously then fires the reconnect trigger.
     * isReconnecting is set BEFORE tryEmit so the collect lambda — which will see
     * connected=false as soon as livePids() calls disconnect() — takes the
     * preserve-state branch instead of resetting to a clean disconnected state.
     * If tryEmit drops silently (loop already active), isReconnecting is already true — no-op.
     * reconnectAttempt is reset to 0 here so the UI never shows a stale attempt count from
     * a previous reconnect cycle between startReconnecting() and the first loop iteration.
     */
    private fun startReconnecting() {
        _uiState.value = _uiState.value.copy(
            isReconnecting = true,
            reconnectAttempt = 0,
            reconnectTotal = RECONNECT_DELAYS.size
        )
        _reconnectTrigger.tryEmit(Unit)
    }
}
