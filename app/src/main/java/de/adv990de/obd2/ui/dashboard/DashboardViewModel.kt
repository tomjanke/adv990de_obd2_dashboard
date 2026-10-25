package de.adv990de.obd2.ui.dashboard

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmController
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.fuel.FuelDistanceTracker
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.terrain.TerrainDisplay
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.terrain.TerrainTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    // Wohin die GPS-Kilometer der Tankanzeige gehen (der Standardwert gilt nur fuer Tests; Hilt bindet das FuelRepository, s. FuelModule).
    private val fuelDistanceSink: FuelDistanceSink = FuelDistanceSink.None,
    // Die Eingaben der Gelaende-Kachel (GPS-Fix und Barometer); der Standardwert gilt nur fuer Tests, Hilt bindet die TerrainSource (s. TerrainModule).
    private val terrainInputs: TerrainInputs = TerrainInputs.None,
    // Treibt die Alarme (Spannung, Kuehlmittel, Fehlercodes) und meldet sie an Benachrichtigung und App-Log; der Standardwert gilt nur fuer Tests, Hilt baut ihn ueber seinen @Inject-Konstruktor (s. AlarmModule).
    private val alarmController: AlarmController = AlarmController(AlarmNotifier.None, AlarmLog.None),
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

    /** Fahrzeugspezifische Getriebedaten fuer die virtuelle Ganganzeige, `Eagerly` gehalten (s. [gearRatiosFlow]); gelesen ueber `.value` in [applyReadings]. */
    private val gearRatios: StateFlow<GearRatios> = gearRatiosFlow(appPreferences, viewModelScope)

    /** The 4 slot → PID-ID assignments, in slot order. */
    val slotPids: StateFlow<List<String>> = appPreferences.slotPids
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSlotPids)

    /** Vom Steuergeraet gemeldete PID-Menge; null solange keine Discovery gelaufen ist.
     *  Die UI unterscheidet damit "Slot nicht belegt" von "Fahrzeug liefert den Wert nicht". */
    val supportedPidIds: StateFlow<Set<String>?> get() = obdRepository.supportedPidIds

    /** Die PIDs der Slot-Auswahl, s. [availablePidsFor]: vom Steuergeraet gemeldet, ohne Verbindung die ganze Registry. */
    val availablePids: StateFlow<List<PidDefinition>> = combine(
        obdRepository.supportedPidIds,
        appPreferences.useGpsSpeed,
        ::availablePidsFor,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PidRegistry.all.values.sortedBy { it.name } + DerivedPids.TERRAIN
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

    /** Geglaettete Spannung und Lade-/Entlade-Zustand fuer den Balken der Spannungs-Kachel — s. [VoltageTrendTracker]. */
    private val voltageTracker = VoltageTrendTracker(now = { nowMillis() })

    /** Hoehe, Steigung und Peilung der Gelaende-Kachel ueber die Zeit einer Verbindung — s. [TerrainTracker]. */
    private val terrainTracker = TerrainTracker()

    /** Die GPS-Kilometer fuer die Tankanzeige (Strecke = Integral der GPS-Geschwindigkeit) — s. [FuelDistanceTracker] und `FuelRepository`. */
    private val fuelTracker = FuelDistanceTracker(fuelDistanceSink) { nowMillis() }

    /**
     * Die zuletzt gemeldete rohe GPS-Geschwindigkeit dieser Sitzung (aus [SessionEvent.Values] und [SessionEvent.GpsSpeed]). Beginnt "veraltet", steht sie sofort in der Anzeige,
     * ohne auf die naechste Aenderung des GPS zu warten — ein gleichbleibender Wert meldet das GPS nicht erneut.
     */
    private var lastGpsSpeedKmh: Float? = null

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

    /**
     * Die anzuzeigenden Alarme (nicht quittiert, nach Stufe und Art sortiert). Sie stehen bewusst **nicht** im [DashboardUiState]: dessen Zuruecksetzen bei jedem Verbindungsereignis
     * (`disconnected()`) loeschte sonst den Fehler-Alarm, der das Ende der Sitzung ueberleben soll. Sie leben so lange wie die Sitzung (Vertrag B1), nicht wie ein sichtbares Dashboard.
     */
    val alarms: StateFlow<List<Alarm>> = alarmController.alarms

    /** Quittiert den Alarm einer Art (Tipp auf das Band): Band und Benachrichtigung verschwinden. */
    fun acknowledgeAlarm(kind: AlarmKind) {
        viewModelScope.launch { alarmController.acknowledge(kind) }
    }

    /** Die Schonfrist der Zahlen-Alarme nach "veraltet" — s. [AlarmStaleGrace]. */
    private val alarmStaleGrace = AlarmStaleGrace(viewModelScope, alarmController)

    init {
        viewModelScope.launch {
            readingsPipeline.events(viewModelScope).collect { onSessionEvent(it) }
        }
        // Die Alarm-Einstellungen wirken sofort, auch auf einen bestehenden Alarm (ausgeschaltet = erloschen).
        viewModelScope.launch {
            appPreferences.alarmSettings.collect { alarmController.updateSettings(it) }
        }
        // Der Link steht, sobald das Repository es sagt — nicht erst mit dem ersten Messwert (B24).
        viewModelScope.launch {
            obdRepository.isConnectedFlow.collect { up -> if (up) onLinkUp() }
        }
        connectionCoordinator.start(viewModelScope, ::onConnectionEvent)
    }

    // ── Gelaende-Kachel ──────────────────────────────────────────────────────

    /** Was die Gelaende-Kachel zeigt — Rechnung, Zeitpunkte und Gruende stehen in [terrainDisplayFlow]. */
    val terrain: StateFlow<TerrainDisplay?> = terrainDisplayFlow(
        scope = viewModelScope,
        slotPids = slotPids,
        useGpsSpeed = appPreferences.useGpsSpeed,
        inputs = terrainInputs,
        tracker = terrainTracker,
        now = { nowMillis() },
    )

    // ── Messwert-Ereignisse ──────────────────────────────────────────────────

    private suspend fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Values -> {
                lastGpsSpeedKmh = event.gpsSpeedKmh
                val shown = applyReadings(event.values, event.gpsSpeedKmh, event.isObdCycle)
                // Auch ein GPS-Zwischenupdate zaehlt: die Strecke haengt an der Zeit und der GPS-Geschwindigkeit, nicht an den OBD-Zyklen.
                fuelTracker.sample(event.gpsSpeedKmh)
                // Der angezeigte Gang und die Farbe des Oel-Balkens gehoeren in die Aufzeichnung — je OBD-Zyklus, nicht bei jedem
                // GPS-Zwischenupdate (dasselbe Raster wie die anderen Werte), beide Zeilen in einem Aufruf. Ein Ereignis ohne Wert ist kein Zyklus.
                if (event.isObdCycle) {
                    // Die Alarme werten nur echte OBD-Zyklen aus: ein GPS-Zwischenupdate wiederholt die alten Werte, die Dauer-Regeln liefen sonst allein mit der Uhr weiter.
                    // Ein echter Zyklus beendet auch die Schonfrist nach "veraltet": die Werte sind zurueck.
                    alarmStaleGrace.cancel()
                    alarmController.onCycle(nowMillis(), event.values)
                    event.values.maxOfOrNull { it.timestamp }?.let { timestamp ->
                        readingsPipeline.recordDerived(
                            listOf(gearSensorValue(shown.gear, timestamp), oilBarSensorValue(shown.oilBarColor, timestamp))
                        )
                    }
                }
            }
            // Ohne Werte erloeschen Spannungs- und Kuehlmittelalarm; der Fehler-Alarm bleibt (AlarmTracker.onSessionEnded). Nach dem Zustand der Anzeige, damit
            // dessen Zuruecksetzen nicht auf das App-Log wartet.
            SessionEvent.Idle -> { endFuelInterval(); applyIdle(); alarmStaleGrace.cancel(); alarmController.onSessionEnded() }
            // "Veraltet" und "eingefroren" sind keine beendete Sitzung: die Zahlen-Alarme bekommen eine Schonfrist (s. [AlarmStaleGrace]) und die Tankanzeige zaehlt die GPS-Strecke weiter —
            // die Verbindung steht, das GPS haengt nicht am Adapter (Entscheidung D4, 2026-10-24). Ein zweites Ereignis (Stale nach Frozen) laesst die erste Frist stehen.
            SessionEvent.Stale -> { applyStale(ValuesLostCause.SILENT); alarmStaleGrace.start() }
            SessionEvent.Frozen -> { applyStale(ValuesLostCause.FROZEN); alarmStaleGrace.start() }
            // Nur in diesem Zustand gemeldet: keine Fahrzeugwerte, aber das GPS lebt — es geht an die Anzeige der Geschwindigkeit und an den Tankzaehler.
            is SessionEvent.GpsSpeed -> onGpsSpeed(event.kmh)
            is SessionEvent.ConnectionLost -> { endFuelInterval(); startReconnecting(event.cause); alarmStaleGrace.cancel(); alarmController.onSessionEnded() }
            is SessionEvent.Failed -> {
                endFuelInterval()
                sparklines.clear()
                gearTracker.reset()
                voltageTracker.reset()
                _uiState.value = DashboardUiState.disconnected(event.error)
                alarmStaleGrace.cancel()
                alarmController.onSessionEnded()
            }
            is SessionEvent.FaultCodes -> alarmController.onFaultCodes(event.codes, event.isBaseline)
        }
    }

    /** Ohne Verbindung gibt es keine Strecke: das Intervall endet (kein Raten ueber die Luecke) und der Zaehler wird gespeichert. */
    private fun endFuelInterval() {
        fuelTracker.endInterval()
        lastGpsSpeedKmh = null
    }

    /**
     * Die GPS-Geschwindigkeit hat sich geaendert, waehrend die Fahrzeugwerte fehlen (Spezifikation 2026-10-24): sie steht in [DashboardUiState.gpsSpeedKmh] (nur in "veraltet", ein spaetes
     * Ereignis nach frischen Werten aendert die Anzeige nicht) und zaehlt fuer die Tankanzeige — die Strecke haengt an der Zeit und dem GPS, nicht am Adapter.
     */
    private fun onGpsSpeed(kmh: Float?) {
        lastGpsSpeedKmh = kmh
        if (_uiState.value.isStale) _uiState.update { it.copy(gpsSpeedKmh = kmh) }
        fuelTracker.sample(kmh)
    }

    override fun onCleared() {
        try {
            // Ohne Sitzung gibt es keinen Spannungs- und Kuehlmittelalarm mehr: ihre Benachrichtigungen bleiben nicht im Schatten stehen (der Fehler-Alarm schon, bis zum Quittieren).
            alarmController.release()
        } finally {
            // Das ViewModel endet mit der Activity: der Zaehler soll nicht nur im Speicher stehen — auch dann nicht verloren gehen, wenn das Beiwerk oben scheitert.
            fuelTracker.flush()
        }
    }

    /** Der Link zum Adapter steht, Werte fehlen noch — der Uebergang steht in [afterLinkUp]. */
    private fun onLinkUp() {
        _uiState.update { it.afterLinkUp() }
    }

    /** Was die Anzeige nach einem Zyklus von den abgeleiteten Werten zeigt — das, was der Aufrufer aufzeichnet. */
    private class ShownDerived(val gear: GearReading, val oilBarColor: OilBarColor?)

    /** Rechnet die Anzeige fuer einen Zyklus neu und liefert, was die Gang-Box und der Oel-Balken jetzt zeigen (der Aufrufer zeichnet es auf). */
    private fun applyReadings(values: List<SensorValue>, gpsSpeedKmh: Float?, isObdCycle: Boolean): ShownDerived {
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
        // Nur ein echter OBD-Zyklus bewegt den Spannungs-Trend: ein GPS-Zwischenupdate wiederholt die alten Werte (s. VoltageTrendTracker).
        val newVoltageTrend = voltageTracker.update(values, isObdCycle)
        val oilBarGreenInMillis = oilBarTracker.greenInMillis()
        val sparklineSnapshot = sparklines.snapshot()
        _uiState.update {
            it.afterReadings(
                values = values,
                displayIds = displayIds,
                rpmValue = newRpmValue,
                engineTempC = engineTempC,
                speedKmh = newSpeedKmh,
                gear = gearReading,
                oilBarColor = newOilBarColor,
                oilBarGreenInMillis = oilBarGreenInMillis,
                voltageTrend = newVoltageTrend,
                sparklines = sparklineSnapshot,
            )
        }
        return ShownDerived(gearReading, newOilBarColor)
    }

    /** Die Fahrzeugwerte fehlen bei stehender Verbindung (schweigendes Steuergeraet oder eingefroren, [cause]) — der Zustand danach steht in [afterStale]. */
    private fun applyStale(cause: ValuesLostCause) {
        gearTracker.reset()
        voltageTracker.reset()
        _uiState.update { it.afterStale(cause, lastGpsSpeedKmh) }
    }

    /** Keine Verbindung — der Zustand danach steht in [afterIdle] (ein Fehler oder eine laufende Wiederverbindung bleiben stehen). */
    private fun applyIdle() {
        sparklines.clear()
        gearTracker.reset()
        voltageTracker.reset()
        _uiState.update { it.afterIdle() }
    }

    // ── Verbindungs-Ereignisse ───────────────────────────────────────────────

    private fun onConnectionEvent(event: ConnectionEvent) {
        _uiState.update { it.afterConnectionEvent(event) }
    }

    /**
     * Setzt isReconnecting synchron und stoesst dann den Koordinator an.
     *
     * Die Reihenfolge ist wesentlich: Der Messwert-Strom meldet [SessionEvent.Idle], sobald
     * livePids() bei Verbindungsverlust disconnect() ruft. Steht isReconnecting da noch nicht,
     * setzt [applyIdle] den Zustand auf sauber getrennt zurueck und die Wiederverbindung wird
     * unsichtbar.
     */
    private fun startReconnecting(cause: ConnectionLostCause) {
        sparklines.clear()
        gearTracker.reset()
        voltageTracker.reset()
        _uiState.update { it.afterConnectionLost(cause) }
        connectionCoordinator.requestReconnect()
    }

}


/** Die Platzhalter-Kacheln einer Belegung: je PID ein Wert 0; unbekannte IDs fallen auf die Standard-PIDs zurueck. */
private fun placeholdersFor(ids: List<String>): List<SensorValue> {
    val pids = ids.mapNotNull { PidRegistry.all[it] }.ifEmpty { PidRegistry.defaults }
    return pids.map { pid -> SensorValue(pid, 0f) }
}
