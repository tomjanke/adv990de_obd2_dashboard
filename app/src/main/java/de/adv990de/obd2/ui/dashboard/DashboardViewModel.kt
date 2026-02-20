package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    init {
        viewModelScope.launch {
            obdRepository.isConnectedFlow
                .flatMapLatest { connected ->
                    if (!connected) {
                        flowOf(emptyList<SensorValue>() to false)
                    } else {
                        flow {
                            val vin = runCatching { obdRepository.readVin() }.getOrNull()
                            val sessionId = sessionRepository.startSession(vin)
                            try {
                                emitAll(
                                    obdRepository.livePids()
                                        .onEach { sessionRepository.writeReadings(sessionId, it) }
                                        .map { it to true }
                                )
                            } finally {
                                sessionRepository.endSession(sessionId)
                            }
                        }
                    }
                }
                .catch { e ->
                    _uiState.value = DashboardUiState(
                        isConnected = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
                }
                .collect { (values, connected) ->
                    _uiState.value = DashboardUiState(
                        readings = values,
                        isConnected = connected
                    )
                }
        }
    }
}
