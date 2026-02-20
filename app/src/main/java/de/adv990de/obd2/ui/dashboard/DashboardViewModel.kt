package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val readings: List<SensorValue> = emptyList(),
    val isConnected: Boolean = false,
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

    init {
        viewModelScope.launch {
            combine(
                obdRepository.isConnectedFlow,
                appPreferences.selectedPidIds
            ) { connected, selectedIds -> connected to selectedIds }
                .flatMapLatest { (connected, selectedIds) ->
                    if (!connected) {
                        flowOf(emptyList<SensorValue>() to false)
                    } else {
                        flow {
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
                                throw e
                            } catch (e: Exception) {
                                _uiState.value = DashboardUiState(
                                    isConnected = false,
                                    error = e.message ?: e.javaClass.simpleName
                                )
                            } finally {
                                try {
                                    if (sessionId != -1L) sessionRepository.endSession(sessionId)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                .collect { (values, connected) ->
                    if (!connected && _uiState.value.error != null) {
                        _uiState.value = _uiState.value.copy(isConnected = false, readings = emptyList())
                    } else {
                        _uiState.value = DashboardUiState(readings = values, isConnected = connected)
                    }
                }
        }
    }
}
