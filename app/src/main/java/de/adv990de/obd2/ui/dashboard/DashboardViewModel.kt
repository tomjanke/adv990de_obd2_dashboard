package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// DashboardUiState.disconnected() is the canonical way to reset to a disconnected/error state.
// If you add a field with a non-trivial default, update disconnected() accordingly.
private const val SPARKLINE_WINDOW = 60

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
    val engineTempC: Float? = null,
    val speedKmh: Float? = null,
    val gear: Int? = null
) {
    companion object {
        fun disconnected(error: UiText? = null) = DashboardUiState(isConnected = false, error = error)
    }
}

/**
 * Setzt den Dashboard-Zustand aus zwei Quellen zusammen: dem [ReadingsPipeline]-Strom und den
 * Ereignissen des [ConnectionCoordinator].
 *
 * Bis 1.10.2 lag beides hier, zusammen mit Wiederverbindungs-Backoff, Auto-Connect,
 * Service-Steuerung, Sitzungsaufzeichnung und GPS-Zusammenfuehrung — 427 Zeilen mit sieben
 * Zustaendigkeiten. Was davon blieb, ist Darstellung: Kachelbelegung, Sparkline-Puffer,
 * Schwellwerte, und die Uebersetzung der Ereignisse in einen Zustand.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences,
    private val connectionCoordinator: ConnectionCoordinator,
    private val readingsPipeline: ReadingsPipeline,
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

    /** Fahrzeugspezifische Getriebedaten fuer die virtuelle Ganganzeige. `Eagerly`: wird
     *  synchron ueber `.value` in [applyReadings] gelesen, nicht ueber einen Compose-Collector
     *  gehalten — ohne `Eagerly` bliebe der Wert fuer immer auf dem Default stehen (derselbe
     *  Fehler, der die GPS-Geschwindigkeit bis 1.10.1 nie im Dashboard ankommen liess, siehe
     *  `ReadingsPipeline.gpsSpeedKmh`). */
    private val gearRatios: StateFlow<GearRatios> = combine(
        appPreferences.wheelCircumferenceMm,
        appPreferences.primaryRatio,
        appPreferences.secondaryRatio,
        appPreferences.gearRatios,
    ) { wheelCircumferenceMm, primaryRatio, secondaryRatio, ratios ->
        GearRatios(wheelCircumferenceMm, primaryRatio, secondaryRatio, ratios)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GearRatios(
            AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
            AppPreferences.DEFAULT_PRIMARY_RATIO,
            AppPreferences.DEFAULT_SECONDARY_RATIO,
            AppPreferences.DEFAULT_GEAR_RATIOS,
        )
    )

    /** The 4 slot → PID-ID assignments, in slot order. */
    val slotPids: StateFlow<List<String>> = appPreferences.slotPids
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SLOT_PIDS)

    /** Vom Steuergeraet gemeldete PID-Menge; null solange keine Discovery gelaufen ist.
     *  Die UI unterscheidet damit "Slot nicht belegt" von "Fahrzeug liefert den Wert nicht". */
    val supportedPidIds: StateFlow<Set<String>?> get() = obdRepository.supportedPidIds

    /** PIDs available in the picker: ECU-supported when connected, full registry otherwise. */
    val availablePids: StateFlow<List<PidDefinition>> = combine(
        obdRepository.supportedPidIds,
        appPreferences.useGpsSpeed,
    ) { supported, gpsEnabled ->
        val all = PidRegistry.all.values.sortedBy { it.name }
        if (supported.isNullOrEmpty()) {
            all
        } else {
            // Die Geschwindigkeit bleibt waehlbar, solange das GPS sie liefern kann — sonst
            // verschwaende sie bei genau den Fahrzeugen aus dem Picker, fuer die das Feature
            // gebaut wurde.
            all.filter { it.id in supported || (gpsEnabled && it.id == PidRegistry.SPEED.id) }
        }
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

    init {
        viewModelScope.launch {
            readingsPipeline.events(viewModelScope).collect(::onSessionEvent)
        }
        connectionCoordinator.start(viewModelScope, ::onConnectionEvent)
    }

    // ── Messwert-Ereignisse ──────────────────────────────────────────────────

    private fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Values -> applyReadings(event.values)
            SessionEvent.Idle -> applyIdle()
            SessionEvent.ConnectionLost -> startReconnecting()
            is SessionEvent.Failed -> _uiState.value = DashboardUiState.disconnected(event.error)
        }
    }

    private fun applyReadings(values: List<SensorValue>) {
        values.forEach { sv ->
            val deque = sparklineBuffer.getOrPut(sv.pid.id) { ArrayDeque(SPARKLINE_WINDOW) }
            if (deque.size >= SPARKLINE_WINDOW) deque.removeFirst()
            deque.addLast(sv.value)
        }
        val displayIds = slotPids.value.toSet()
        val engineTempC = if (rpmDynamicThreshold.value) {
            values.firstOrNull { it.pid.id == PidRegistry.ENGINE_OIL_TEMP.id && it.isPlausible() }?.value
                ?: values.firstOrNull { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.isPlausible() }?.value
        } else null
        val newRpmValue = values.firstOrNull { it.pid.id == PidRegistry.RPM.id }
            ?.value ?: _uiState.value.rpmValue
        val newSpeedKmh = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id && it.isPlausible() }
            ?.value ?: _uiState.value.speedKmh
        val newGear = determineGear(newRpmValue, newSpeedKmh, _uiState.value.gear, gearRatios.value)
        _uiState.value = _uiState.value.copy(
            readings = values.filter { sv -> sv.pid.id in displayIds },
            rpmValue = newRpmValue,
            engineTempC = engineTempC,
            speedKmh = newSpeedKmh,
            gear = newGear,
            isConnected = true,
            isReconnecting = false,
            error = null,
            sparklines = sparklineBuffer.mapValues { (_, deque) -> deque.toList() }
        )
    }

    /**
     * Keine Verbindung. Steht ein Fehler oder eine laufende Wiederverbindung im Zustand, bleibt
     * dieser erhalten und nur die veralteten Messwerte verschwinden — sonst ueberschriebe der
     * Leerlauf genau die Meldung, die der Nutzer lesen soll.
     */
    private fun applyIdle() {
        _uiState.value = if (_uiState.value.error != null || _uiState.value.isReconnecting) {
            _uiState.value.copy(isConnected = false, readings = emptyList())
        } else {
            DashboardUiState.disconnected()
        }
    }

    // ── Verbindungs-Ereignisse ───────────────────────────────────────────────

    private fun onConnectionEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Attempting -> {
                _uiState.value = if (event.attempt == 0) {
                    // Auto-Connect beim Start: Der uebrige Zustand bleibt, es gab noch keinen.
                    _uiState.value.copy(isReconnecting = true, reconnectAttempt = 0)
                } else {
                    DashboardUiState(
                        isReconnecting = true,
                        reconnectAttempt = event.attempt,
                        reconnectTotal = event.total,
                    )
                }
            }
            // Der Spinner haelt nicht bis zum ersten Messwert an — die Verbindung steht.
            ConnectionEvent.Connected ->
                _uiState.value = _uiState.value.copy(isReconnecting = false, reconnectAttempt = 0)
            is ConnectionEvent.Failed ->
                _uiState.value = DashboardUiState.disconnected(event.error)
        }
    }

    /**
     * Setzt isReconnecting synchron und stoesst dann den Koordinator an.
     *
     * Die Reihenfolge ist wesentlich: Der Messwert-Strom meldet [SessionEvent.Idle], sobald
     * livePids() bei Verbindungsverlust disconnect() ruft. Steht isReconnecting da noch nicht,
     * setzt [applyIdle] den Zustand auf sauber getrennt zurueck und die Wiederverbindung wird
     * unsichtbar.
     */
    private fun startReconnecting() {
        _uiState.value = _uiState.value.copy(
            isReconnecting = true,
            reconnectAttempt = 0,
            reconnectTotal = RECONNECT_DELAYS.size
        )
        connectionCoordinator.requestReconnect()
    }
}
