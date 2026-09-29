package de.adv990de.obd2.ui.dashboard

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
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

// SavedStateHandle-Schluessel fuer den Oel-Balken-Timerzustand — s. Kommentare an
// oilBarTimerState/lastOilBarSampleTimestamp.
private const val KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS = "oilBarAboveGreenSinceMillis"
private const val KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS = "oilBarLastSampleAtMillis"
private const val KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP = "oilBarLastSampleTimestamp"

private fun SensorValue.isPlausible() = value.isFinite()

data class DashboardUiState(
    val readings: List<SensorValue> = emptyList(),
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    // References RECONNECT_ATTEMPT_COUNT so the default stays in sync if it ever changes.
    val reconnectTotal: Int = RECONNECT_ATTEMPT_COUNT,
    // UiText: static messages use StringResource (localizable), dynamic messages (e.g. exception
    // messages from the BT stack) use DynamicString.
    val error: UiText? = null,
    val sparklines: Map<String, List<Float>> = emptyMap(),
    val rpmValue: Float = 0f,
    val engineTempC: Float? = null,
    val speedKmh: Float? = null,
    val gear: Int? = null,
    val oilBarColor: OilBarColor? = null
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
    private val savedStateHandle: SavedStateHandle,
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

    // Interner Timer-Zustand des Oel-Balkens (Gruen-Hysterese) — bewusst NICHT Teil von
    // DashboardUiState: OilBarTimerState ist ein Berechnungsdetail, das die UI nie liest; in den
    // UI-State gehoert nur die daraus abgeleitete OilBarColor. Wird bei einem Verbindungsabbruch
    // bewusst NICHT zurueckgesetzt (anders als gear/speedKmh in applyIdle()): Die
    // Kuehlmitteltemperatur aendert sich nicht, nur weil BLE kurz aussetzt. Unbegrenzt ueberlebt
    // der Timer eine Trennung trotzdem nicht — das Luecken-Limit in updateOilBarTimer()
    // (MAX_SAMPLE_GAP_MS, 60 s) laesst ihn verfallen, wenn zwischen zwei Messwerten mehr Zeit
    // liegt: Ein Reconnect innerhalb dieser Zeit behaelt die Zaehlung, eine laengere Trennung
    // (oder eine stundenlang geschlossene App) beginnt von vorn.
    //
    // Ueber savedStateHandle statt eines einfachen Feldes (Backlog-Sweep 2026-09-28): eine reine
    // In-Memory-Property ueberlebt nur Konfigurationsaenderungen (die ViewModelStore-Instanz bleibt
    // dabei erhalten), nicht aber eine Activity-Neuerzeugung BEI LAUFENDEM PROZESS — "Nicht im
    // Hintergrund behalten" oder aggressives OEM-Reclaiming erzeugen dabei ein frisches ViewModel,
    // ohne dass ConnectionLost oder das Luecken-Limit je eintraten. SavedStateHandle uebersteht
    // genau das (Bundle aus onSaveInstanceState), zusaetzlich zu Prozess-Tod. OilBarTimerState hat
    // keinen eigenen Parcelize-Support, deshalb als zwei einzelne Long?-Werte gespeichert statt als
    // ein Objekt.
    private var oilBarTimerState: OilBarTimerState
        get() = OilBarTimerState(
            aboveGreenThresholdSinceMillis = savedStateHandle[KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS],
            lastSampleAtMillis = savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS],
        )
        set(value) {
            savedStateHandle[KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS] = value.aboveGreenThresholdSinceMillis
            savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS] = value.lastSampleAtMillis
        }

    // SensorValue.timestamp des zuletzt in updateOilBarTimer() eingespeisten Kuehlmittelwerts.
    // ReadingsPipeline.gpsRefresh() stellt bei jeder GPS-Aenderung (bis 5 Hz) die letzten
    // OBD-Werte erneut zu — dieselben SensorValue-Objekte mit ihrem urspruenglichen Zeitstempel,
    // auch wenn das Steuergeraet laengst nichts Neues mehr liefert (Zuendung aus: nur noch
    // NO DATA, das der Watchdog beliebig lange toleriert). Ohne diese Pruefung bestaetigte
    // GPS-Rauschen allein einen alten Wert ueber 80 °C immer wieder, bis der Balken bei
    // abgestelltem Motor gruen wurde. Deshalb zaehlt nur ein Messwert mit echt NEUEREM
    // Zeitstempel als neuer Messwert; eine Wiederholung laesst Timer und Farbe unveraendert.
    //
    // Bekannte Grenze: Der Zeitstempel ist Wanduhr-Zeit (System.currentTimeMillis() in
    // PidParser). Springt die Systemzeit waehrend der Fahrt zurueck (manuelle Umstellung, grosse
    // Netzzeit-Korrektur), gelten neue Messwerte so lange als "nicht neuer", bis die Uhr den
    // zuletzt verarbeiteten Zeitstempel wieder eingeholt hat — der Balken steht fuer die Dauer des
    // Sprungs still (nie faelschlich Gruen; nach mehr als 60 s greift danach das Luecken-Limit).
    // Das ist der Preis fuer `>` statt `!=`: `>` macht auch eine Wiederholung wirkungslos, die erst
    // nach einem neueren Messwert ankaeme.
    //
    // Ebenfalls SavedStateHandle-gestuetzt, aus demselben Grund wie oilBarTimerState oben: ohne das
    // wuerde eine Activity-Neuerzeugung bei laufendem Prozess diesen Wert auf null zuruecksetzen und
    // damit einmalig genau die GPS-Wiederholungs-Falle wieder oeffnen, die diese Pruefung eigentlich
    // verhindern soll.
    private var lastOilBarSampleTimestamp: Long?
        get() = savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP]
        set(value) { savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP] = value }

    // Testbarer Uhr-Seam (Projekt-Konvention: Zeit als Parameter statt System-Uhr-Zugriff im
    // Funktionskoerper, vgl. GpsSpeedSource/isReadingsStale). Bewusst KEIN Konstruktor-Parameter:
    // Dagger/Hilt ignoriert Kotlin-Defaultwerte bei @Inject-Konstruktoren und scheitert ohne ein
    // zusaetzliches @Provides-Binding fuer Function0<Long> mit [Dagger/MissingBinding] (empirisch
    // verifiziert). Als var-Property aendert sich die Konstruktor-Signatur fuer Hilt gar nicht
    // erst; Tests setzen die Property direkt nach dem Bauen des ViewModels (s. makeVmFrom).
    //
    // Monotone Uhr (elapsedRealtime) statt Wanduhr: Gruen-Timer und Luecken-Limit rechnen Dauern,
    // und die duerfen nicht springen, wenn Netzzeit-Abgleich oder Nutzer die Systemzeit verstellen
    // — dieselbe Begruendung wie TimeSource.Monotonic im OBD-Watchdog (ObdRepository.livePids()).
    // Bewusst nicht uptimeMillis(): elapsedRealtime zaehlt Tiefschlaf mit, eine Stunde Standby ist
    // fuer das Luecken-Limit also auch eine Stunde Pause.
    internal var nowMillis: () -> Long = { SystemClock.elapsedRealtime() }

    init {
        viewModelScope.launch {
            readingsPipeline.events(viewModelScope).collect(::onSessionEvent)
        }
        connectionCoordinator.start(viewModelScope, ::onConnectionEvent)
    }

    // ── Messwert-Ereignisse ──────────────────────────────────────────────────

    private fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Values -> applyReadings(event.values, event.gpsSpeedKmh)
            SessionEvent.Idle -> applyIdle()
            SessionEvent.ConnectionLost -> startReconnecting()
            is SessionEvent.Failed -> _uiState.value = DashboardUiState.disconnected(event.error)
        }
    }

    private fun applyReadings(values: List<SensorValue>, gpsSpeedKmh: Float?) {
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
        // Kein Fallback auf den letzten Wert (anders als newRpmValue): Fehlt SPEED in diesem
        // Zyklus, bedeutet das bei GPS-basierter Geschwindigkeit "kein aktueller Fix" — GPS aus,
        // Anbieter deaktiviert, oder schlicht kein Fix gerade jetzt. Das ist das Signal, dass der
        // Wert JETZT unbekannt ist, keine transiente Luecke, die man ueberbruecken sollte. Ein
        // Fallback wuerde eine veraltete Geschwindigkeit unbegrenzt weiterzeigen und
        // determineGear() mit ihr fuettern, obwohl das Fahrzeug sie nicht mehr meldet.
        val newSpeedKmh = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id && it.isPlausible() }
            ?.value
        // Bewusst NICHT newSpeedKmh (das ist der ggf. vom Fahrzeug dominierte, zusammengefuehrte
        // Anzeigewert, s. mergeGpsSpeed) — die Ganganzeige braucht exklusiv den rohen GPS-Wert.
        // determineGear()s Annahmen (MIN_SPEED_KMH als GPS-Rauschgrenze, die 2s-Staleness in
        // GpsSpeedSource) sind auf GPS-Charakteristik zugeschnitten, nicht auf die vom Fahrzeug
        // gemeldete Geschwindigkeit. Fehlt GPS (aus, keine Berechtigung, kein Fix), ist der Gang
        // unbekannt, auch wenn das Fahrzeug selbst eine Geschwindigkeit liefert.
        val computedGear = determineGear(newRpmValue, gpsSpeedKmh, _uiState.value.gear, gearRatios.value)
        // Nutzer-Feedback nach einer Testfahrt (v1.16.6): Kupplung ziehen oder Gas wegnehmen
        // entkoppelt die Motordrehzahl kurz von der Radgeschwindigkeit — determineGear() liefert
        // dann fuer einen Zyklus `null`, obwohl das Fahrzeug erkennbar weiterrollt. Ohne
        // holdGear() wuerde das nicht nur die Anzeige kurz auf "–" springen lassen, sondern auch
        // das Hysterese-Gedaechtnis loeschen (naechster Zyklus haette wieder lastGear=null) — der
        // Gang muesste sich danach komplett neu einrasten (ENTER_TOLERANCE statt STAY_TOLERANCE),
        // was sich als zaeher Wiedereinstieg bemerkbar machte. holdGear() haelt den zuletzt
        // angezeigten Gang, solange die Geschwindigkeit ueber MIN_SPEED_KMH bleibt, und loescht
        // ihn nur bei echtem Stillstand oder fehlendem GPS-Fix.
        val newGear = holdGear(computedGear, gpsSpeedKmh, _uiState.value.gear)
        // Oel-Balken-Farbe: unabhaengig von rpmDynamicThreshold (anders als engineTempC oben) —
        // die Kuehlmitteltemperatur wird hier immer gesucht, wenn ein plausibler Wert vorliegt.
        // Fehlt sie in diesem Zyklus (kein Reading oder implausibel, z.B. NaN) oder ist sie nur
        // die GPS-Wiederholung eines schon verarbeiteten Messwerts (Zeitstempel nicht neuer, s.
        // lastOilBarSampleTimestamp), bleibt die zuletzt angezeigte Farbe unveraendert — derselbe
        // "keine Regression bei einem einzelnen fehlenden Wert"-Ansatz wie bei holdGear() weiter
        // oben, hier realisiert, indem der Timer-Zustand unangetastet bleibt und newOilBarColor
        // auf den bisherigen UI-Wert faellt, statt den Copy-Aufruf unten in zwei Varianten
        // aufzuspalten.
        val newCoolantReading = values
            .firstOrNull { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.isPlausible() }
            ?.takeIf { reading -> lastOilBarSampleTimestamp.let { last -> last == null || reading.timestamp > last } }
        val newOilBarColor = if (newCoolantReading != null) {
            lastOilBarSampleTimestamp = newCoolantReading.timestamp
            val now = nowMillis()
            oilBarTimerState = updateOilBarTimer(newCoolantReading.value, now, oilBarTimerState)
            oilBarColor(newCoolantReading.value, now, oilBarTimerState)
        } else {
            _uiState.value.oilBarColor
        }
        _uiState.value = _uiState.value.copy(
            readings = values.filter { sv -> sv.pid.id in displayIds },
            rpmValue = newRpmValue,
            engineTempC = engineTempC,
            speedKmh = newSpeedKmh,
            gear = newGear,
            oilBarColor = newOilBarColor,
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
            // speedKmh/gear werden hier explizit mit zurueckgesetzt (anders als rpmValue/
            // engineTempC): Sonst faedelt der erste Zyklus nach einem Reconnect
            // determineGear(..., lastGear = <alter, nie neu bestaetigter Gang>, ...) ein und
            // die Hysterese "erinnert" sich an einen Zustand von vor dem Verbindungsabbruch.
            _uiState.value.copy(
                isConnected = false,
                readings = emptyList(),
                speedKmh = null,
                gear = null,
                oilBarColor = null
            )
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
            reconnectTotal = RECONNECT_ATTEMPT_COUNT
        )
        connectionCoordinator.requestReconnect()
    }
}
