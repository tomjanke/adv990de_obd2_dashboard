package de.adv990de.obd2.ui.dashboard

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.fuel.DistanceIntegrator
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
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
private fun SensorValue.isPlausible() = value.isFinite()

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
    // Die Restzeit bis Gruen fuer die Ambient-Statuszeile (der Standardwert gilt nur fuer Tests; Hilt reicht den Singleton).
    private val oilWarmupState: OilWarmupState = OilWarmupState(),
    // Wohin die GPS-Kilometer der Tankanzeige gehen (der Standardwert gilt nur fuer Tests; Hilt bindet das FuelRepository, s. FuelModule).
    private val fuelDistanceSink: FuelDistanceSink = FuelDistanceSink.None,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    // Die gespeicherte Kachel-Belegung, einmal beim Erzeugen synchron gelesen (~3 ms). Sie ist der STARTWERT der beiden Flows
    // unten: Das erste Bild zeigte sonst leere Kacheln (Platzhalter) bzw. die Standard-Belegung und sprang erst danach auf die
    // gespeicherte um (UID-12). Die Flows abonnieren die Einstellung danach wie bisher und bekommen jede Aenderung.
    private val initialSlotPids: List<String> =
        seedBlocking(AppPreferences.DEFAULT_SLOT_PIDS, source = appPreferences.slotPids)

    // Placeholder readings shown when not connected: slot PIDs with value 0.
    // Keeps the dashboard tiles visible at all times so the user always sees the layout.
    val placeholderReadings: StateFlow<List<SensorValue>> = appPreferences.slotPids
        .map(::placeholdersFor)
        .stateIn(viewModelScope, SharingStarted.Eagerly, placeholdersFor(initialSlotPids))

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSlotPids)

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

    /** Die Sparkline-Puffer; sie gehoeren zu EINER Verbindung ([SparklineBuffer.clear] bei Idle, Verlust und Fehler). */
    private val sparklines = SparklineBuffer()

    /** Ob der Tacho-Sweep dieses App-Starts schon angeboten wurde — s. [consumeDemoSweepOffer]. Bewusst nicht im `SavedStateHandle`. */
    private var demoSweepOffered = false

    /**
     * Der Tacho-Sweep (`DashboardScreen`) ist eine Start-Animation: er wird **einmal je App-Start** angeboten, nicht bei jedem Betreten
     * des Dashboards (Entscheidung 2026-10-12). Liefert `true` genau beim ersten Aufruf, danach immer `false`. "App-Start" ist die
     * Lebensdauer dieses ViewModels — es bleibt ueber Tabwechsel und Drehung bestehen (der Dashboard-Eintrag liegt auf dem Back-Stack,
     * s. `BottomNav`) und endet mit der Activity. Ein Systemkill mit Wiederherstellung zaehlt als neuer Start (kein `SavedStateHandle`).
     *
     * Das Angebot ist mit der ersten PRUEFUNG verbraucht, nicht erst mit dem Abspielen: lief die Animation beim Start nicht, weil schon eine
     * echte Drehzahl anlag, soll sie nicht spaeter mitten in der Sitzung nachgeholt werden. Ob sie dann wirklich laeuft, entscheidet
     * weiterhin [shouldStartRpmDemoSweep] (Drehzahl noch 0?).
     */
    fun consumeDemoSweepOffer(): Boolean {
        val firstOffer = !demoSweepOffered
        demoSweepOffered = true
        return firstOffer
    }

    /** Gang und "N" ueber die Zyklen einer Verbindung — s. [GearTracker]. */
    private val gearTracker = GearTracker(now = { nowMillis() })

    /** Farbe des Oel-Balkens samt Timer-Zustand im [SavedStateHandle] — s. [OilBarTracker]. */
    private val oilBarTracker = OilBarTracker(savedStateHandle, now = { nowMillis() })

    /** Die GPS-Kilometer fuer die Tankanzeige (Strecke = Integral der GPS-Geschwindigkeit) — s. [DistanceIntegrator] und `FuelRepository`. */
    private val fuelIntegrator = DistanceIntegrator()

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
            readingsPipeline.events(viewModelScope).collect { onSessionEvent(it) }
        }
        // Der Link steht, sobald das Repository es sagt — nicht erst mit dem ersten Messwert (B24).
        viewModelScope.launch {
            obdRepository.isConnectedFlow.collect { up -> if (up) onLinkUp() }
        }
        connectionCoordinator.start(viewModelScope, ::onConnectionEvent)
    }

    // ── Messwert-Ereignisse ──────────────────────────────────────────────────

    private suspend fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Values -> {
                val shown = applyReadings(event.values, event.gpsSpeedKmh)
                // Auch ein GPS-Zwischenupdate zaehlt: die Strecke haengt an der Zeit und der GPS-Geschwindigkeit, nicht an den OBD-Zyklen.
                val km = fuelIntegrator.sample(event.gpsSpeedKmh, nowMillis())
                if (km > 0f) fuelDistanceSink.addDistance(km)
                // Der angezeigte Gang und die Farbe des Oel-Balkens gehoeren in die Aufzeichnung — je OBD-Zyklus, nicht bei jedem
                // GPS-Zwischenupdate (dasselbe Raster wie die anderen Werte), beide Zeilen in einem Aufruf. Ein Ereignis ohne Wert ist kein Zyklus.
                if (event.isObdCycle) {
                    event.values.maxOfOrNull { it.timestamp }?.let { timestamp ->
                        readingsPipeline.recordDerived(
                            listOf(gearSensorValue(shown.gear, timestamp), oilBarSensorValue(shown.oilBarColor, timestamp))
                        )
                    }
                }
            }
            SessionEvent.Idle -> { endFuelInterval(); applyIdle() }
            SessionEvent.Stale -> { endFuelInterval(); applyStale() }
            SessionEvent.ConnectionLost -> { endFuelInterval(); startReconnecting() }
            is SessionEvent.Failed -> {
                endFuelInterval()
                sparklines.clear()
                gearTracker.reset()
                _uiState.value = DashboardUiState.disconnected(event.error)
            }
        }
    }

    /** Ohne Werte gibt es keine Strecke: das Intervall endet (kein Raten ueber die Luecke) und der Zaehler wird gespeichert. */
    private fun endFuelInterval() {
        fuelIntegrator.reset()
        fuelDistanceSink.flush()
    }

    override fun onCleared() {
        // Das ViewModel endet mit der Activity: der Zaehler soll nicht nur im Speicher stehen.
        fuelDistanceSink.flush()
    }

    /**
     * Der Link zum Adapter ist aufgebaut, Werte fehlen noch. Setzt nur, was bei einem schon "verbundenen"
     * Zustand nicht ueberschrieben werden soll (kommt der erste Messwert vor diesem Aufruf, bleibt alles).
     */
    private fun onLinkUp() {
        val current = _uiState.value
        if (current.isConnected) return
        _uiState.value = current.copy(
            isConnected = true,
            isReconnecting = false,
            reconnectAttempt = 0,
            error = null,
            isStale = false,
            awaitingFirstValues = true,
        )
    }

    /** Was die Anzeige nach einem Zyklus von den abgeleiteten Werten zeigt — das, was der Aufrufer aufzeichnet. */
    private class ShownDerived(val gear: GearReading, val oilBarColor: OilBarColor?)

    /** Rechnet die Anzeige fuer einen Zyklus neu und liefert, was die Gang-Box und der Oel-Balken jetzt zeigen (der Aufrufer zeichnet es auf). */
    private fun applyReadings(values: List<SensorValue>, gpsSpeedKmh: Float?): ShownDerived {
        sparklines.append(values)
        val displayIds = slotPids.value.toSet()
        val engineTempC = if (rpmDynamicThreshold.value) {
            values.firstOrNull { it.pid.id == PidRegistry.ENGINE_OIL_TEMP.id && it.isPlausible() }?.value
                ?: values.firstOrNull { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.isPlausible() }?.value
                // Fehlt die Temperatur in diesem Zyklus, gilt die letzte (B26): null liesse die dynamische Schwelle fuer
                // einen Zyklus auf den Kaltwert fallen (rpmThresholdTempScale(null)) — ein falscher Schaltblitz. Sie
                // aendert sich ohnehin nur langsam; ein schweigendes Steuergeraet beendet applyStale() (-> null).
                ?: _uiState.value.engineTempC
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
        // Gang, "N" und Oel-Balken-Farbe: die Begruendungen stehen an GearTracker und OilBarTracker.
        val gearReading = gearTracker.update(
            rpm = newRpmValue,
            ecuSpeedKmh = newSpeedKmh,
            gpsSpeedKmh = gpsSpeedKmh,
            previous = GearReading(_uiState.value.gear, _uiState.value.isNeutral),
            ratios = gearRatios.value,
        )
        val newOilBarColor = oilBarTracker.update(values, previous = _uiState.value.oilBarColor)
        // Die Restzeit laeuft mit der Uhr, nicht nur mit neuen Messwerten: auch ein GPS-Zwischenupdate rechnet sie neu.
        oilWarmupState.set(oilBarTracker.greenInMillis())
        _uiState.value = _uiState.value.copy(
            readings = values.filter { sv -> sv.pid.id in displayIds },
            rpmValue = newRpmValue,
            engineTempC = engineTempC,
            speedKmh = newSpeedKmh,
            gear = gearReading.gear,
            isNeutral = gearReading.isNeutral,
            oilBarColor = newOilBarColor,
            isConnected = true,
            isReconnecting = false,
            isStale = false,
            awaitingFirstValues = false,
            error = null,
            sparklines = sparklines.snapshot()
        )
        return ShownDerived(gearReading, newOilBarColor)
    }

    /**
     * Das Steuergeraet schweigt bei stehender Verbindung (B29): Die Werte sind veraltet und werden
     * nicht weiter als live gezeigt — Kacheln "--", Drehzahlbalken leer, Gang "–". Verbindungsstatus
     * und Verbindungs-Punkt bleiben (der Adapter ist ja da). Die Oel-Balken-Farbe bleibt gehalten (sie
     * hat ihr eigenes 60-s-Luecken-Limit); sie wird ohne "live" ohnehin grau gezeichnet.
     */
    private fun applyStale() {
        gearTracker.reset()
        _uiState.value = _uiState.value.copy(
            isStale = true,
            awaitingFirstValues = false,
            readings = emptyList(),
            rpmValue = 0f,
            engineTempC = null,
            speedKmh = null,
            gear = null,
            isNeutral = false,
        )
    }

    /**
     * Keine Verbindung. Steht ein Fehler oder eine laufende Wiederverbindung im Zustand, bleibt
     * dieser erhalten und nur die veralteten Messwerte verschwinden — sonst ueberschriebe der
     * Leerlauf genau die Meldung, die der Nutzer lesen soll.
     */
    private fun applyIdle() {
        sparklines.clear()
        gearTracker.reset()
        _uiState.value = if (_uiState.value.error != null || _uiState.value.isReconnecting) {
            // speedKmh/gear werden hier explizit mit zurueckgesetzt (anders als rpmValue/
            // engineTempC): Sonst faedelt der erste Zyklus nach einem Reconnect
            // determineGear(..., lastGear = <alter, nie neu bestaetigter Gang>, ...) ein und
            // die Hysterese "erinnert" sich an einen Zustand von vor dem Verbindungsabbruch.
            _uiState.value.copy(
                isConnected = false,
                isStale = false,
                awaitingFirstValues = false,
                readings = emptyList(),
                speedKmh = null,
                gear = null,
                isNeutral = false,
                oilBarColor = null,
                sparklines = emptyMap(),
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
            // Der Nutzer hat getrennt, waehrend wiederverbunden wurde: sauber getrennt, ohne Spinner und
            // ohne den Fehler eines frueheren Versuchs. (Der Messwert-Strom meldet Idle, sobald die
            // Verbindung weg ist; applyIdle() haette Spinner und Fehler sonst stehen lassen.)
            ConnectionEvent.Aborted -> _uiState.value = DashboardUiState()
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
        sparklines.clear()
        gearTracker.reset()
        _uiState.value = _uiState.value.copy(
            sparklines = emptyMap(),
            // Ein gemeldeter Verbindungsverlust heisst per Definition "nicht mehr verbunden" — unabhaengig davon,
            // ob das Idle-Ereignis des Messwert-Stroms schon da ist. Seit onLinkUp() den Link vor dem ersten Wert
            // als verbunden fuehrt, bliebe isConnected sonst wahr, solange isConnectedFlow nicht gekippt ist
            // (die Reihenfolge der beiden Wege ist nicht garantiert). readings/Gang/... raeumt applyIdle() ab.
            isConnected = false,
            isStale = false,
            awaitingFirstValues = false,
            isReconnecting = true,
            reconnectAttempt = 0,
            reconnectTotal = RECONNECT_ATTEMPT_COUNT
        )
        connectionCoordinator.requestReconnect()
    }
}

/** Die Platzhalter-Kacheln einer Belegung: je PID ein Wert 0; unbekannte IDs fallen auf die Standard-PIDs zurueck. */
private fun placeholdersFor(ids: List<String>): List<SensorValue> {
    val pids = ids.mapNotNull { PidRegistry.all[it] }.ifEmpty { PidRegistry.defaults }
    return pids.map { pid -> SensorValue(pid, 0f) }
}
