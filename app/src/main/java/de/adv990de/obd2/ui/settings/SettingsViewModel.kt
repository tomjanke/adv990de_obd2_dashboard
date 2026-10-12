package de.adv990de.obd2.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.ObdServiceController
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    /** Why connecting or listing devices failed — localized for known causes, never empty. */
    val error: UiText? = null
)

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val scanner: BluetoothDeviceScanner,
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
    private val serviceController: ObdServiceController,
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

    val reconnectBaseDelaySeconds: StateFlow<Int> = appPreferences.reconnectBaseDelaySeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS)

    fun setReconnectBaseDelaySeconds(value: Int) {
        viewModelScope.launch { appPreferences.setReconnectBaseDelaySeconds(value) }
    }

    /**
     * Ob der angezeigte [SettingsUiState.error] vom Laden der Geraeteliste stammt (statt von einem Verbindungsversuch).
     * Ein Fehler gilt nur, solange seine Ursache besteht: der Listenfehler endet mit dem naechsten erfolgreichen Laden
     * (die Berechtigung wurde inzwischen erteilt), der Verbindungsfehler mit der naechsten Verbindung — keiner raeumt den anderen ab.
     */
    private var errorFromDeviceList = false

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

                    // The connection stands — by whatever path (this screen, the dashboard's reconnect). A "connect
                    // failed" message from an earlier attempt would now contradict the screen. A device-list error
                    // (missing Bluetooth permission) is a different cause and stays until the list loads again.
                    if (!errorFromDeviceList && _uiState.value.error != null) {
                        _uiState.value = _uiState.value.copy(error = null)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(connectedDevice = null, connectedDeviceAddress = null)
                }
            }
        }
    }

    fun loadDevices() {
        // Die Bluetooth-Berechtigung kann ab Android 12 auch nach dem PermissionGate wegfallen.
        // Dann bleibt die Geraeteliste leer statt die App mit einer SecurityException zu beenden.
        var failed = false
        val devices = try {
            scanner.bondedDevices(showAllBtDevices.value)
        } catch (e: BluetoothException.PermissionDenied) {
            failed = true
            _uiState.value = _uiState.value.copy(error = ObdErrorMapper.toUiText(e))
            emptyList()
        } catch (e: SecurityException) {
            failed = true
            _uiState.value = _uiState.value.copy(error = ObdErrorMapper.toUiText(e))
            emptyList()
        }
        if (failed) errorFromDeviceList = true
        _uiState.value = if (!failed && errorFromDeviceList) {
            errorFromDeviceList = false
            _uiState.value.copy(bondedDevices = devices, error = null)
        } else {
            _uiState.value.copy(bondedDevices = devices)
        }
    }

    fun disconnect() {
        viewModelScope.launch { serviceController.endSession() }
    }

    fun connect(device: ScannedDevice) {
        viewModelScope.launch {
            errorFromDeviceList = false // the attempt replaces whatever was shown
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
                // Dieselbe Entscheidung (Dienst-Typ, abgelehnter Start) wie im ConnectionCoordinator — eine Stelle:
                // ObdServiceController.start() wirft nicht, auch wenn Android den Dienst ablehnt (B4).
                serviceController.start()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogRepository.log(LogLevel.ERROR, "Connection error: ${e.message ?: e.javaClass.simpleName}")
                _uiState.value = _uiState.value.copy(isConnecting = false, error = ObdErrorMapper.toUiText(e))
            }
        }
    }
}
