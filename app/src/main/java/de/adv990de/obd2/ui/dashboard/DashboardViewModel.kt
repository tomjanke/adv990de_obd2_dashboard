package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

// Three sites in DashboardViewModel construct a full DashboardUiState() to reset state:
// (1) generic Exception catch in the session loop, (2) reconnect loop terminal error,
// (3) the else branch in the collect lambda. If you add a field with a non-trivial default,
// update all three. Search for "DashboardUiState(" to find them.
data class DashboardUiState(
    val readings: List<SensorValue> = emptyList(),
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectTotal: Int = 5,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    private val sessionRepository: SessionRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    // Fired when ConnectionLost is caught in the session loop.
    // Separated from flatMapLatest inner flow because flatMapLatest would cancel the inner
    // flow the moment isConnectedFlow emits false — which happens immediately when livePids()
    // calls disconnect() on ConnectionLost, killing any retry loop inside it.
    private val _reconnectTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // ── Session loop ─────────────────────────────────────────────────────
        viewModelScope.launch {
            // NOTE: combining selectedPidIds means a PID toggle while connected ends the current
            // session and starts a new one. This is intentional: the DashboardConfig screen is
            // under Settings, so changes are expected to be made before/between drives.
            combine(
                obdRepository.isConnectedFlow,
                appPreferences.selectedPidIds
            ) { connected, selectedIds -> connected to selectedIds }
                .flatMapLatest { (connected, selectedIds) ->
                    if (!connected) {
                        flowOf(emptyList<SensorValue>() to false)
                    } else {
                        flow {
                            // Fall back to defaults if none of the stored IDs exist in PidRegistry
                            // (e.g. after a downgrade or manual DataStore wipe).
                            val selectedPids = selectedIds
                                .mapNotNull { PidRegistry.all[it] }
                                .ifEmpty { PidRegistry.defaults }

                            val vin = try { obdRepository.readVin() }
                                      catch (e: Exception) {
                                          if (e is CancellationException) throw e; null
                                      }
                            var sessionId = -1L
                            try {
                                sessionId = sessionRepository.startSession(vin)
                                emitAll(
                                    obdRepository.livePids(selectedPids)
                                        .onEach { sessionRepository.writeReadings(sessionId, it) }
                                        .map { it to true }
                                )
                            } catch (e: CancellationException) {
                                throw e  // never swallow coroutine cancellation
                            } catch (e: ObdException.ConnectionLost) {
                                // Signal the reconnect coroutine. Do NOT set error state here —
                                // the reconnect coroutine manages isReconnecting and error.
                                // If the loop is already active, tryEmit() drops silently —
                                // that is intentional: the running loop will attempt up to 5
                                // reconnects regardless of how many ConnectionLost events fired.
                                _reconnectTrigger.tryEmit(Unit)
                            } catch (e: Exception) {
                                // Set error state directly; inner flow completes cleanly so
                                // flatMapLatest outer collector survives and can recover.
                                _uiState.value = DashboardUiState(
                                    isConnected = false,
                                    error = e.message ?: e.javaClass.simpleName
                                )
                            } finally {
                                // DB errors must not prevent session cleanup.
                                try {
                                    if (sessionId != -1L) sessionRepository.endSession(sessionId)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                .collect { (values, connected) ->
                    if (!connected && (_uiState.value.error != null || _uiState.value.isReconnecting)) {
                        // Disconnected cleanly after an error or during reconnect — preserve state,
                        // just clear stale readings.
                        _uiState.value = _uiState.value.copy(isConnected = false, readings = emptyList())
                    } else {
                        _uiState.value = DashboardUiState(readings = values, isConnected = connected)
                    }
                }
        }

        // ── Reconnect loop ───────────────────────────────────────────────────
        // Known TOCTOU residual: two isConnectedFlow guards (pre- and post-delay) narrow the
        // race window to microseconds, but do not fully eliminate it. A manual connect from
        // Settings that completes between guard 2 and reconnect() acquiring connectMutex
        // will have its connection closed and replaced by the auto-reconnect attempt.
        // Eliminating this would require holding a mutex across the guard-plus-reconnect
        // sequence, which is out of scope for the current implementation.
        viewModelScope.launch {
            _reconnectTrigger.collect {
                val delays = listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L)
                for ((attempt, delayMs) in delays.withIndex()) {
                    // User may have reconnected manually in Settings while we waited
                    if (obdRepository.isConnectedFlow.value) return@collect
                    _uiState.value = DashboardUiState(
                        isReconnecting = true,
                        reconnectAttempt = attempt + 1,
                        reconnectTotal = delays.size
                    )
                    delay(delayMs)
                    // Second guard: user may have connected manually while we were waiting
                    if (obdRepository.isConnectedFlow.value) return@collect
                    try {
                        try {
                            obdRepository.reconnect()
                        } catch (_: IllegalStateException) {
                            // Cold launch: in-memory lastDevice not set yet.
                            // Fall back to the address persisted in DataStore.
                            val persisted = appPreferences.lastDevice.first()
                                ?: throw IllegalStateException("No persisted device to reconnect to")
                            obdRepository.connect(persisted)
                        }
                        // Clear reconnecting state immediately so the spinner stops as soon as
                        // the connection is confirmed. The session loop will overwrite with real
                        // readings once the first livePids emission arrives.
                        _uiState.value = _uiState.value.copy(isReconnecting = false, reconnectAttempt = 0)
                        return@collect  // isConnectedFlow emits true → flatMapLatest restarts
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        // try next attempt
                    }
                }
                _uiState.value = DashboardUiState(
                    isConnected = false,
                    error = "Verbindung dauerhaft unterbrochen."
                )
            }
        }
    }
}
