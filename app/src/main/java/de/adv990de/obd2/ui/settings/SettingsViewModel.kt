package de.adv990de.obd2.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.location.LocationPermissions
import de.adv990de.obd2.ui.dashboard.shouldIncludeLocationServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val bondedDevices: List<ScannedDevice> = emptyList(),
    val isConnecting: Boolean = false,
    val connectedDevice: String? = null,
    val connectedDeviceAddress: String? = null,
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
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    val showAllBtDevices: StateFlow<Boolean> = appPreferences.showAllBtDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleShowAllBtDevices() {
        viewModelScope.launch { appPreferences.setShowAllBtDevices(!showAllBtDevices.value) }
    }

    val obdWatchdogTimeoutSeconds: StateFlow<Int> = appPreferences.obdWatchdogTimeoutSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS)

    fun setObdWatchdogTimeoutSeconds(value: Int) {
        viewModelScope.launch { appPreferences.setObdWatchdogTimeoutSeconds(value) }
    }

    init {
        viewModelScope.launch {
            appPreferences.lastDevice.collect { device ->
                // Keep lastDeviceName in sync so the isConnectedFlow collector (below) can
                // read the device name from state without a DataStore first() round-trip.
                _uiState.value = _uiState.value.copy(
                    lastDeviceAddress = device?.address,
                    lastDeviceName = device?.name
                )
                // Backfill: if the auto-reconnect path already established a connection while
                // DataStore was still loading (isConnectedFlow emitted true before this collector
                // had its first emission), connectedDevice is null. Set it now.
                if (obdRepository.isConnectedFlow.value
                        && !_uiState.value.isConnecting
                        && _uiState.value.connectedDevice == null) {
                    _uiState.value = _uiState.value.copy(
                        connectedDevice = device?.name,
                        connectedDeviceAddress = device?.address
                    )
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
                        _uiState.value = _uiState.value.copy(
                            connectedDevice = _uiState.value.lastDeviceName,
                            connectedDeviceAddress = _uiState.value.lastDeviceAddress
                        )
                    }
                    // Manual connect path: isConnecting=true is set synchronously before
                    // obdRepository.connect() is called (same coroutine), so this collector
                    // observes isConnecting=true and skips the DataStore read. connect() then
                    // sets connectedDevice directly after returning. The SettingsViewModelTest
                    // verifies this ordering assumption via yield() inside the mock.
                } else {
                    _uiState.value = _uiState.value.copy(connectedDevice = null, connectedDeviceAddress = null)
                }
            }
        }
    }

    fun loadDevices() {
        // Die Bluetooth-Berechtigung kann ab Android 12 auch nach dem PermissionGate wegfallen.
        // Dann bleibt die Geraeteliste leer statt die App mit einer SecurityException zu beenden.
        val devices = try {
            scanner.bondedDevices(showAllBtDevices.value)
        } catch (e: BluetoothException.PermissionDenied) {
            _uiState.value = _uiState.value.copy(error = e.message)
            emptyList()
        } catch (e: SecurityException) {
            _uiState.value = _uiState.value.copy(error = e.message)
            emptyList()
        }
        _uiState.value = _uiState.value.copy(bondedDevices = devices)
    }

    fun disconnect() {
        viewModelScope.launch {
            obdRepository.disconnect()
            appContext.stopService(ObdForegroundService.stopIntent(appContext))
        }
    }

    fun connect(device: ScannedDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, error = null)
            appLogRepository.log(LogLevel.INFO, "Connecting to ${device.name}…")
            try {
                obdRepository.connect(device)
                try { appPreferences.persistDevice(device) } catch (e: Exception) {
                    Log.w(TAG, "persistDevice failed — last-device highlight and auto-reconnect will not work", e)
                }
                appLogRepository.log(LogLevel.INFO, "Connected to ${device.name}")
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    connectedDevice = device.name,
                    connectedDeviceAddress = device.address,
                    error = null
                )
                // Mirrors ConnectionCoordinator.startObdForegroundService(): this is a separate
                // manual-connect call site, so it needs the same includeLocationType decision —
                // otherwise the service silently starts with connectedDevice-only type and GPS
                // speed stops delivering location callbacks the moment the app is backgrounded.
                val includeLocation = shouldIncludeLocationServiceType(
                    useGpsSpeed = appPreferences.useGpsSpeed.first(),
                    hasBackgroundLocation = LocationPermissions.hasBackgroundLocation(appContext),
                )
                appContext.startForegroundService(ObdForegroundService.startIntent(appContext, includeLocation))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogRepository.log(LogLevel.ERROR, "Connection error: ${e.message ?: "unknown"}")
                _uiState.value = _uiState.value.copy(isConnecting = false, error = e.message)
            }
        }
    }
}
