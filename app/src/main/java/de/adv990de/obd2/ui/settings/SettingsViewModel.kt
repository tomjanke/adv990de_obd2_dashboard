package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val bondedDevices: List<ScannedDevice> = emptyList(),
    val isConnecting: Boolean = false,
    val connectedDevice: String? = null,
    val lastDeviceAddress: String? = null,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val scanner: BluetoothDeviceScanner,
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            obdRepository.isConnectedFlow.collect { connected ->
                if (!connected) _uiState.value = _uiState.value.copy(connectedDevice = null)
            }
        }
        viewModelScope.launch {
            appPreferences.lastDevice.collect { device ->
                _uiState.value = _uiState.value.copy(lastDeviceAddress = device?.device?.address)
            }
        }
    }

    fun loadDevices() {
        _uiState.value = _uiState.value.copy(bondedDevices = scanner.bondedDevices())
    }

    fun connect(device: ScannedDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, error = null)
            try {
                obdRepository.connect(device)
                appPreferences.persistDevice(device)
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    connectedDevice = device.name,
                    error = null
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = _uiState.value.copy(isConnecting = false, error = e.message)
            }
        }
    }
}
