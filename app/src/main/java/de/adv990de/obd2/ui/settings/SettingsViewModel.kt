package de.adv990de.obd2.ui.settings

import android.util.Log
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
    // Kept in sync with lastDevice flow so the auto-reconnect path can read the device name
    // from state without an extra DataStore I/O round-trip.
    val lastDeviceName: String? = null,
    val error: String? = null
)

private const val TAG = "SettingsViewModel"

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
            appPreferences.lastDevice.collect { device ->
                // Keep lastDeviceName in sync so the isConnectedFlow collector (below) can
                // read the device name from state without a DataStore first() round-trip.
                _uiState.value = _uiState.value.copy(
                    lastDeviceAddress = device?.device?.address,
                    lastDeviceName = device?.name
                )
                // Backfill: if the auto-reconnect path already established a connection while
                // DataStore was still loading (isConnectedFlow emitted true before this collector
                // had its first emission), connectedDevice is null. Set it now.
                if (obdRepository.isConnectedFlow.value
                        && !_uiState.value.isConnecting
                        && _uiState.value.connectedDevice == null) {
                    _uiState.value = _uiState.value.copy(connectedDevice = device?.name)
                }
            }
        }
        viewModelScope.launch {
            obdRepository.isConnectedFlow.collect { connected ->
                if (connected) {
                    if (!_uiState.value.isConnecting) {
                        // Auto-reconnect path: connect() in this VM did not run, so connectedDevice
                        // was not set directly. Use the name already in state (populated by the
                        // lastDevice collector above) to avoid an extra DataStore I/O call.
                        _uiState.value = _uiState.value.copy(connectedDevice = _uiState.value.lastDeviceName)
                    }
                    // Manual connect path: isConnecting=true is set synchronously before
                    // obdRepository.connect() is called (same coroutine), so this collector
                    // observes isConnecting=true and skips the DataStore read. connect() then
                    // sets connectedDevice directly after returning. The SettingsViewModelTest
                    // verifies this ordering assumption via yield() inside the mock.
                } else {
                    _uiState.value = _uiState.value.copy(connectedDevice = null)
                }
            }
        }
    }

    fun loadDevices() {
        _uiState.value = _uiState.value.copy(bondedDevices = scanner.bondedDevices())
    }

    fun disconnect() {
        viewModelScope.launch { obdRepository.disconnect() }
    }

    fun connect(device: ScannedDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, error = null)
            try {
                obdRepository.connect(device)
                // Best-effort: DataStore I/O failure must not poison the connected state.
                try { appPreferences.persistDevice(device) } catch (e: Exception) {
                    Log.w(TAG, "persistDevice failed — last-device highlight and auto-reconnect will not work", e)
                }
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
