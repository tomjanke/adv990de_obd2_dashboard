package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Was der Messwert-Strom dem ViewModel meldet. */
sealed interface SessionEvent {
    /**
     * Ein vollstaendiger Abfragezyklus, bereits um die GPS-Geschwindigkeit ergaenzt.
     *
     * [gpsSpeedKmh] ist der ROHE GPS-Wert desselben Zyklus — unabhaengig davon, ob er in [values]
     * tatsaechlich zum Einsatz kam ([mergeGpsSpeed] gibt dem Fahrzeugwert Vorrang, sobald das
     * Fahrzeug selbst eine Geschwindigkeit meldet). Die virtuelle Ganganzeige braucht ihn separat
     * und exklusiv, s. Kommentar in `DashboardViewModel.applyReadings()`.
     */
    data class Values(val values: List<SensorValue>, val gpsSpeedKmh: Float?) : SessionEvent

    /** Keine Verbindung — es fliessen keine Werte. */
    data object Idle : SessionEvent

    /**
     * Die Verbindung steht, aber seit [OBD_STALE_AFTER_MS] kam kein Zyklus mit einem Messwert mehr (das
     * Steuergeraet schweigt, z. B. bei ausgeschalteter Zuendung — der Watchdog toleriert reines NO DATA
     * bewusst). Die zuletzt gezeigten Werte sind veraltet und duerfen nicht weiter als live erscheinen.
     * Der naechste [Values] hebt das wieder auf.
     */
    data object Stale : SessionEvent

    /** Die Verbindung ist waehrend der Fahrt abgerissen; der Koordinator soll uebernehmen. */
    data object ConnectionLost : SessionEvent

    /** Die Sitzung ist an einem anderen Fehler gescheitert. */
    data class Failed(val error: UiText) : SessionEvent
}

/**
 * So lange darf kein OBD-Zyklus mit mindestens einem Messwert eintreffen, bevor die Werte als veraltet
 * gelten ([SessionEvent.Stale]). Ein Zyklus dauert normalerweise ~1 s; ein einzelner Timeout blockiert 5 s.
 * 10 s ueberbruecken ein paar verpasste Zyklen, liegen aber deutlich vor dem Watchdog (Standard 20 s), der
 * einen stummen Adapter ohnehin neu verbindet.
 */
internal const val OBD_STALE_AFTER_MS = 10_000L

/**
 * Der Messwert-Pfad: Auswahl der abzufragenden PIDs, Aufzeichnung der Sitzung, Zusammenfuehrung
 * der Geschwindigkeitsquellen, Fahrterkennung.
 *
 * Herausgeloest aus `DashboardViewModel`. Was hier nicht mehr steht, ist Absicht: Der
 * Verbindungsaufbau gehoert in den [ConnectionCoordinator], und wie ein Zyklus in der Oberflaeche
 * aussieht — Sparklines, Statusfarben, Kachelbelegung — entscheidet das ViewModel.
 */
class ReadingsPipeline @Inject constructor(
    private val obdRepository: ObdRepository,
    private val sessionRepository: SessionRepository,
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
    private val crashReporter: CrashReporter,
    private val gpsSpeedSource: GpsSpeedSource,
    private val dtcHistoryRepository: DtcHistoryRepository,
) {

    /**
     * Die zuletzt vom Fahrzeug gelesenen, noch NICHT mit GPS zusammengefuehrten Werte eines
     * OBD-Zyklus. Existiert nur, damit [gpsRefresh] zwischen zwei OBD-Zyklen erneut zusammenfuehren
     * kann — [mergeGpsSpeed] auf ein bereits zusammengefuehrtes Ergebnis anzuwenden waere falsch:
     * die zuvor eingesetzte GPS-Geschwindigkeit traegt dieselbe PID-ID wie ein echter Fahrzeugwert
     * und wuerde als "Fahrzeug liefert Geschwindigkeit" missverstanden.
     */
    private val lastObdReadings = MutableStateFlow<List<SensorValue>?>(null)

    /** Zaehlt die OBD-Zyklen mit Messwerten dieser Sitzung (0 = noch keiner). Treibt die Frist fuer
     *  [SessionEvent.Stale]; ein Zaehler statt eines Vergleichs von [lastObdReadings], weil ein
     *  StateFlow gleiche Werte zusammenfasst und die Frist dann nicht neu anliefe. */
    private val obdCycles = MutableStateFlow(0)

    /** Wahr, sobald die Werte als veraltet gemeldet wurden: [gpsRefresh] fuehrt sie dann nicht mehr neu
     *  mit einem frischen GPS-Wert zusammen. Bewusst NICHT [lastObdReadings] auf null zu setzen — die
     *  Frist haengt (ueber [obdCycles]) nicht daran, ein Rueckschreiben aus dem eigenen Strom brachte die
     *  Reentrancy-Falle, die [connectionLostSignal] beschreibt. */
    private val obdStale = MutableStateFlow(false)

    /** Hysterese-Zustand der Fahrterkennung ([isMoving]); zu Beginn jeder Sitzung "steht". */
    private var moving = false

    /** Laeuft die Messwert-Quelle der Sitzung noch? Nur dann kann sie "schweigen": Eine beendete Quelle
     *  (Verbindungsverlust, Fehler) meldet das auf ihrem eigenen Weg, dort waere [SessionEvent.Stale]
     *  falsch. */
    private var sessionRunning = false

    /**
     * Getrennter Signalweg fuer [SessionEvent.ConnectionLost] — analog zu
     * [ConnectionCoordinator]s `trigger`, aus demselben Grund: Ein direktes `emit()` innerhalb von
     * [runSession] waere ungeschuetzt, weil dieses Ereignis erst entsteht, NACHDEM
     * [ObdRepository.livePids] `disconnect()` gerufen hat — und `disconnect()` setzt
     * `isConnectedFlow.value = false`, genau den Schluessel, ueber den [events] denselben Aufruf
     * per `combine()`/`flatMapLatest()` selbst steuert. Auf einem synchron/reentrant liefernden
     * Dispatcher (`Dispatchers.Main.immediate` in Produktion, s. android-test-environment-pitfalls.md)
     * kann dieser Wertwechsel [runSession]s Coroutine noch WAEHREND des catch-Blocks abbrechen,
     * bevor ein `emit(SessionEvent.ConnectionLost)` dort ankommt — der Reconnect-Ausloeser ginge
     * damit stillschweigend verloren (empirisch reproduziert in [ReadingsPipelineTest]).
     *
     * [connectionLostSignal] wird in [events] als GESCHWISTER von `combine().flatMapLatest{}`
     * gemerged, nicht als Teil davon — dieselbe Coroutine, die `flatMapLatest` gerade abbricht,
     * kann diesen Geschwister-Collector nicht mit abbrechen. `tryEmit()` (nicht-suspendierend) im
     * catch-Block schreibt das Signal atomar, noch bevor die eigentliche Coroutine ueberhaupt zu
     * einem Suspendierungspunkt kommt, an dem der Abbruch greifen koennte. Ein direkter
     * `withContext(NonCancellable) { emit(...) }` an Ort und Stelle waere die naheliegendere
     * Alternative gewesen — verworfen, weil Flow das mit einer `IllegalStateException` ablehnt
     * ("Emission from another coroutine is detected", empirisch bestaetigt): `emit()` darf nur aus
     * genau der Coroutine kommen, die den Flow sammelt, `NonCancellable` aendert dafuer genug am
     * Kontext, dass die Pruefung anschlaegt. Ein `channelFlow{}` mit `send()` umgeht zwar diese
     * Pruefung (Channels sind fuer nebenlaeufige Sender ausgelegt), aendert am eigentlichen Problem
     * aber nichts: Der Kanal haengt an genau der Coroutine, die abgebrochen wird — ist sie bereits
     * am Schliessen, verwirft ein `send()` danach ebenso still (auch das empirisch geprueft). Ein
     * wirklich unabhaengiger Signalweg muss in einer Coroutine leben, die vom Abbruch gar nicht
     * erst betroffen ist — genau das leistet [connectionLostSignal] als Geschwister-Flow.
     *
     * `replay = 0` reicht hier aus (anders als bei [ConnectionCoordinator.trigger]s `replay = 1`):
     * Dieser Signalweg und der Zweig, der ihn ausloest, werden im selben `merge()`-Aufruf in
     * [events] gestartet — es gibt keine Startluecke, in der ein Sammler noch fehlen koennte.
     *
     * **Bekannte, akzeptierte Einschraenkung:** Weil dieser Signalweg unabhaengig vom normalen
     * Werte-Pfad lauft, ist die Ankunftsreihenfolge relativ zu einem SessionEvent.Values DERSELBEN
     * Sitzung nicht mehr garantiert — anders als vor dieser Aenderung, wo beide sequenziell aus
     * derselben Coroutine kamen. Theoretisch koennte ein letzter, noch nicht zugestellter
     * Messwert-Zyklus NACH diesem Signal ankommen und `DashboardViewModel.applyReadings()`s
     * `isReconnecting = false` wuerde den gerade gesetzten Reconnect-Zustand wieder ueberschreiben.
     * In der echten Anwendung liegen zwischen zwei Zyklen aber immer echte Wartezeit
     * (`pollIntervalMs`, mindestens mehrere hundert ms) plus die Zeit fuer die PID-Abfragen selbst
     * (ein Timeout allein blockiert 5s) — der vorausgehende Zyklus ist damit laengst zugestellt,
     * bevor ein SPAETERER Zyklus ueberhaupt ConnectionLost werfen kann. Nur ein Test, der (anders als jede
     * echte Verbindung) einen Messwert und den Verbindungsabbruch aus demselben Sitzungszyklus ohne
     * jede Zeit dazwischen ausloest, kann diese Reihenfolge ueberhaupt umkehren.
     */
    private val connectionLostSignal = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    /**
     * GPS-Geschwindigkeit in km/h, null wenn das Feature aus ist, keine Berechtigung vorliegt oder
     * kein Fix mit Geschwindigkeit ansteht.
     *
     * `Eagerly` ist hier Pflicht, nicht Geschmack: Ein `WhileSubscribed`-Flow ohne Sammler startet
     * seinen Upstream nie — der GPS-Empfaenger liefe nie an. Genau dieser Fehler liess die
     * GPS-Geschwindigkeit bis 1.10.1 nie im Dashboard ankommen. Seit 1.15.2 wird dieser Flow
     * zusaetzlich per `collect` in [gpsRefresh] gesammelt (nicht mehr nur ueber [StateFlow.value]
     * gelesen), damit ein neuer GPS-Fix die Anzeige sofort ausloest statt auf den naechsten
     * OBD-Takt zu warten.
     *
     * Der `flatMapLatest` sorgt dafuer, dass der GPS-Empfaenger trotzdem nur laeuft, solange der
     * Schalter an ist.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun gpsSpeedKmh(scope: CoroutineScope): StateFlow<Float?> = appPreferences.useGpsSpeed
        .flatMapLatest { enabled -> if (enabled) gpsSpeedSource.speedKmh else flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Der Ereignisstrom einer Sitzung. Kalt: Er laeuft erst, wenn jemand sammelt, und endet mit
     * dem [scope], der die GPS-Quelle traegt.
     *
     * Hinweis: Eine Slot-Aenderung waehrend der Fahrt beendet die laufende Sitzung und beginnt eine
     * neue. Das ist gewollt — die Kachelbelegung liegt unter den Einstellungen und wird zwischen
     * Fahrten geaendert.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(scope: CoroutineScope): Flow<SessionEvent> {
        val gpsSpeed = gpsSpeedKmh(scope)
        val readingsFlow = combine(
            obdRepository.isConnectedFlow,
            appPreferences.slotPids,
        ) { connected, slotIds -> connected to slotIds }
            .flatMapLatest { (connected, slotIds) ->
                if (!connected) {
                    flowOf(SessionEvent.Idle)
                } else {
                    // Zwei bewusst getrennte Quellen: [runSession] entscheidet ueber Aufzeichnung
                    // und Sitzungs-Lebenszyklus (Start/Ende/Fehler) und bleibt dafuer unveraendert
                    // allein am OBD-Takt haengen; [gpsRefresh] frischt zusaetzlich zwischen zwei
                    // OBD-Zyklen nur die Anzeige auf. Die Sitzungslogik soll von einem GPS-Fix
                    // weder beschleunigt noch verzoegert werden.
                    //
                    // Nichts von der vorigen Verbindung ueberlebt in die neue Sitzung: Ohne das
                    // fuehrte der erste GPS-Zwischenwert nach einem Reconnect die ALTEN Messwerte neu
                    // zusammen und zeigte sie als live, bis der erste echte Zyklus eintrifft.
                    merge(
                        runSession(slotIds, gpsSpeed).onCompletion { sessionRunning = false },
                        gpsRefresh(gpsSpeed),
                        staleness(),
                    )
                        .onStart { resetSession() }
                }
            }
        // connectionLostSignal als Geschwister, nicht als Teil der flatMapLatest-Kette — s. Doku
        // an [connectionLostSignal].
        return merge(readingsFlow, connectionLostSignal.map { SessionEvent.ConnectionLost })
    }

    /**
     * Aktualisiert Anzeige und Fahrterkennung bei jedem NEUEN GPS-Fix zwischen zwei OBD-Zyklen —
     * unabhaengig vom Sitzungsstrom in [runSession], der Aufzeichnung und Lebenszyklus haelt.
     *
     * `drop(1)`: Der erste Wert ist der Replay des aktuellen [StateFlow]-Standes beim Abonnieren,
     * keine echte Aenderung — der ist bereits (oder wird gleich) durch den laufenden OBD-Zyklus
     * abgedeckt, der `gpsSpeed.value` ohnehin an jedem eigenen Takt liest. Ohne `drop(1)` koennte
     * dieser Ersatz-Wert in einer engen Race mit dem allerersten OBD-Zyklus um den ersten Platz im
     * Ereignisstrom konkurrieren.
     */
    private fun resetSession() {
        lastObdReadings.value = null
        obdCycles.value = 0
        obdStale.value = false
        moving = false
        sessionRunning = true
    }

    /**
     * Meldet [SessionEvent.Stale], wenn [OBD_STALE_AFTER_MS] lang kein Zyklus mit Messwerten kam. Die Frist
     * startet mit dem Sitzungsbeginn und mit jedem Zyklus neu ([obdCycles]): Ein Steuergeraet, das von
     * Anfang an schweigt (Zuendung aus beim Verbinden), soll nicht ewig als "warte auf Werte" enden (B24).
     * Setzt die Fahrterkennung auf "steht" — eine Geschwindigkeit, die niemand mehr liefert, darf die
     * Navigationsleiste nicht dauerhaft ausgeblendet halten.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun staleness(): Flow<SessionEvent> = obdCycles
        .flatMapLatest {
            flow {
                delay(OBD_STALE_AFTER_MS)
                if (sessionRunning) emit(SessionEvent.Stale)
            }
        }
        .onEach {
            obdStale.value = true
            moving = false
            obdRepository.setMoving(false)
        }

    private fun gpsRefresh(gpsSpeed: StateFlow<Float?>): Flow<SessionEvent.Values> =
        gpsSpeed.drop(1).mapNotNull { gpsKmh ->
            if (obdStale.value) return@mapNotNull null
            val readings = lastObdReadings.value ?: return@mapNotNull null
            mergeGpsSpeed(readings, gpsKmh) to gpsKmh
        }.onEach { (merged, _) -> applyMerged(merged) }
            .map { (merged, gpsKmh) -> SessionEvent.Values(merged, gpsKmh) }

    private fun runSession(slotIds: List<String>, gpsSpeed: StateFlow<Float?>): Flow<SessionEvent> = flow {
        // Stand der Nutzer-Trennungen beim Start dieser Sitzung. "Trennen" schliesst den Socket; ein gerade
        // laufendes Lesen scheitert dann u. U. mit ConnectionLost, bevor flatMapLatest die Sitzung abbricht.
        // Dieser Verbindungsverlust ist gewollt und darf keinen Reconnect ausloesen (s. [lostLinkWantsReconnect]).
        val userDisconnectsAtStart = obdRepository.userDisconnectCount.value
        fun lostLinkWantsReconnect() = obdRepository.userDisconnectCount.value == userDisconnectsAtStart

        val selectedPids = selectPids(slotIds)

        // Aufzeichnungs-Einstellungen einmal pro Sitzung lesen, nicht pro Zyklus.
        val logOnlyDtc = appPreferences.logOnlyDtcSessions.first()
        val logOnlyDiscovered = appPreferences.logOnlyDiscoveredPids.first()
        val loggedPidIds = appPreferences.loggedPidIds.first()
        val watchdogTimeoutMs = appPreferences.obdWatchdogTimeoutSeconds.first() * 1_000L

        val vin = try { obdRepository.readVin() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        // Fehlercodes beim Verbinden lesen: protokollieren und als Filter fuer die Aufzeichnung
        // verwenden. Kein Banner in der Oberflaeche — sichtbar sind sie im App-Log.
        val dtcList = try { obdRepository.readDtcs() } catch (e: Exception) {
            if (e is CancellationException) throw e
            // readDtcs() trennt den Adapter selbst, wenn die Antwort ausbleibt (Zeitueberschreitung:
            // ein spaeter Rest haette den naechsten Befehl verfaelscht) oder der Link abreisst. Ist das
            // hier geschehen, steht die Verbindung nicht mehr — und weil nur ConnectionLost einen
            // Reconnect ausloest, ginge sie sonst still verloren (Anzeige "getrennt", kein neuer
            // Versuch; jeder Slot-Wechsel startet diese Sitzung und damit das Auslesen neu).
            // tryEmit() (nicht-suspendierend) aus demselben Grund wie im ConnectionLost-Zweig unten:
            // der Wechsel von isConnectedFlow bricht diese Coroutine am naechsten Suspendierungspunkt ab.
            if ((e is ObdException.Timeout || e is ObdException.ConnectionLost) && !obdRepository.isConnectedFlow.value) {
                if (lostLinkWantsReconnect()) {
                    connectionLostSignal.tryEmit(Unit)
                    logBestEffort(LogLevel.WARN, "Fault-code read lost the adapter link, reconnecting")
                } else {
                    logBestEffort(LogLevel.INFO, "Fault-code read ended by the user's disconnect, not reconnecting")
                }
                return@flow
            }
            null
        }
        if (dtcList?.isNotEmpty() == true) {
            appLogRepository.log(LogLevel.WARN, "Active DTCs on connect: ${dtcList.joinToString()}")
        }
        // Lokale Fehlerhistorie: jede Fahrt fuellt sie passiv. Nur nach einem erfolgreichen Auslesen
        // (null = Lesefehler: nichts aufzeichnen). record() ist selbst "best effort" — die Absicherung
        // hier deckt das Unerwartete ab, denn eine Ausnahme an dieser Stelle wuerde die ganze Sitzung kosten.
        if (dtcList != null) {
            try { dtcHistoryRepository.record(dtcList) } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        val hasDtcForRecording: Boolean = dtcList?.isNotEmpty() ?: true

        // "Nur Fahrten mit DTCs": Die Entscheidung steht schon fest — keine Fahrt anlegen, statt sie
        // aufzuzeichnen und am Ende zu verwerfen (Schreiblast; ein Prozess-Kill dazwischen hinterliess
        // eine DTC-lose Fahrt entgegen der Einstellung).
        val recordThisTrip = !(logOnlyDtc && !hasDtcForRecording)

        // Aufzeichnen ist Beiwerk der Live-Anzeige: Ein Datenbankfehler (Platte voll, Datenbank gesperrt)
        // darf sie nicht beenden. Einmal je Sitzung melden — ein Fehler mit 2 Hz wuerde sonst das
        // ebenfalls betroffene App-Log fluten.
        var recordingFailureReported = false
        suspend fun recordingFailed(e: Exception) {
            if (recordingFailureReported) return
            recordingFailureReported = true
            crashReporter.recordException(e)
            logBestEffort(LogLevel.WARN, "Recording failed, the live display continues: ${e.message}")
        }

        var sessionId = -1L
        try {
            if (recordThisTrip) {
                sessionId = try { sessionRepository.startSession(vin) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    recordingFailed(e)
                    -1L
                }
            }
            emitAll(
                obdRepository.livePids(selectedPids, watchdogTimeoutMs = watchdogTimeoutMs)
                    // Roh zwischenspeichern, bevor GPS ergaenzt wird — s. Doku an [lastObdReadings].
                    .onEach { readings ->
                        lastObdReadings.value = readings
                        obdStale.value = false
                        obdCycles.update { it + 1 }
                    }
                    // GPS ergaenzt die Geschwindigkeit, bevor aufgezeichnet und angezeigt wird —
                    // der Fahrzeugwert hat Vorrang. Der rohe GPS-Wert wird trotzdem mitgefuehrt
                    // (s. SessionEvent.Values-KDoc) — die Ganganzeige braucht ihn unabhaengig davon,
                    // ob das Fahrzeug selbst eine Geschwindigkeit meldet.
                    .map { readings -> val gpsKmh = gpsSpeed.value; mergeGpsSpeed(readings, gpsKmh) to gpsKmh }
                    .onEach { (values, _) ->
                        if (sessionId == -1L) return@onEach // keine Fahrt: nichts aufzuzeichnen
                        try {
                            record(sessionId, values, logOnlyDiscovered, loggedPidIds)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            recordingFailed(e)
                        }
                    }
                    .onEach { (values, _) -> applyMerged(values) }
                    .map { (values, gpsKmh) -> SessionEvent.Values(values, gpsKmh) }
            )
        } catch (e: CancellationException) {
            throw e  // Abbruch einer Coroutine nie verschlucken
        } catch (e: ObdException.ConnectionLost) {
            // tryEmit() statt emit() — s. Doku an [connectionLostSignal]: nicht-suspendierend,
            // daher atomar gegenueber genau dem Abbruch, den isConnectedFlow (bereits false, s.
            // ObdRepository.livePids()) hier ausloesen kann. Signal zuerst, Log danach: Das Log
            // ist best-effort (ein suspendierender DB-Schreibvorgang waere selbst ein moeglicher
            // Abbruchpunkt), der Reconnect-Ausloeser darf davon nicht abhaengen.
            if (lostLinkWantsReconnect()) {
                connectionLostSignal.tryEmit(Unit)
                appLogRepository.log(LogLevel.WARN, "OBD connection lost, reconnecting (readings stopped or bus stuck)")
            } else {
                appLogRepository.log(LogLevel.INFO, "OBD readings ended by the user's disconnect, not reconnecting")
            }
        } catch (e: Exception) {
            crashReporter.recordException(e)
            emit(SessionEvent.Failed(ObdErrorMapper.toUiText(e)))
        } finally {
            // Datenbankfehler duerfen das Aufraeumen der Sitzung nicht verhindern.
            if (sessionId != -1L) {
                try { sessionRepository.endSession(sessionId) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Schreibt ins App-Log, auch wenn die Sitzung gerade abgebrochen wird: der Schreibvorgang ist ein
     * Suspendierungspunkt und wuerde sonst am Abbruch scheitern. Datenbankfehler wirft
     * [AppLogRepository.log] selbst nie.
     */
    private suspend fun logBestEffort(level: LogLevel, message: String) {
        withContext(NonCancellable) { appLogRepository.log(level, message) }
    }

    /**
     * Die tatsaechlich abzufragenden PIDs: die belegten Slots, gefiltert gegen die vom Steuergeraet
     * gemeldete Menge, dazu immer Drehzahl (RPM-Balken, Ganganzeige) und Kuehlmitteltemperatur
     * (Oel-Temperatur-Balken) — bei dynamischer Schwelle zusaetzlich die Oeltemperatur.
     *
     * Die Kuehlmitteltemperatur ist seit dem Oel-Temperatur-Balken unbedingt, genau wie RPM: Hing
     * sie nur an der Slot-Belegung (oder der standardmaessig ausgeschalteten dynamischen Schwelle),
     * bekam jeder Nutzer, der die Kuehlmittel-Kachel gegen einen anderen Wert getauscht hatte, nie
     * einen Messwert — der Balken blieb trotz Verbindung dauerhaft grau. Doppelt abgefragt wird sie
     * trotzdem nie: `distinctBy` unten entfernt den Eintrag, wenn sie zugleich ein Slot ist.
     *
     * Greift keiner davon, bleiben die Standard-PIDs. Das faengt den Fall ab, dass im DataStore
     * nur noch unbekannte IDs stehen, etwa nach einem Downgrade.
     */
    private suspend fun selectPids(slotIds: List<String>) = run {
        val dynamicThresholdEnabled = appPreferences.rpmDynamicThreshold.first()
        val supported = obdRepository.supportedPidIds.value
        val filteredSlotIds = if (supported.isNullOrEmpty()) slotIds else slotIds.filter { it in supported }
        buildList {
            addAll(filteredSlotIds)
            add(PidRegistry.RPM.id)
            add(PidRegistry.COOLANT_TEMP.id)
            if (dynamicThresholdEnabled) {
                add(PidRegistry.ENGINE_OIL_TEMP.id)
            }
        }
            .mapNotNull { PidRegistry.all[it] }
            .distinctBy { it.id }
            .ifEmpty { PidRegistry.defaults }
    }

    /**
     * Aktualisiert Anzeige und Fahrterkennung fuer einen zusammengefuehrten Zyklus. Wird sowohl
     * vom OBD-Takt in [runSession] als auch vom GPS-Zwischen-Update in [gpsRefresh] aufgerufen —
     * nur [record] bleibt exklusiv dem OBD-Takt vorbehalten.
     */
    private fun applyMerged(values: List<SensorValue>) {
        obdRepository.updateLatestReadings(values)
        // Fahrterkennung aus der Geschwindigkeit — gleich ob sie vom Fahrzeug oder vom GPS kommt, mit
        // Hysterese (s. isMoving): GPS-Rauschen im Stand schaltet das Hauptmenue nicht mehr um.
        // Fehlt sie ganz, gilt "steht", damit die Navigation nie dauerhaft ausgeblendet bleibt.
        val speed = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
        moving = isMoving(speed?.value, moving)
        obdRepository.setMoving(moving)
    }

    private suspend fun record(
        sessionId: Long,
        values: List<SensorValue>,
        logOnlyDiscovered: Boolean,
        loggedPidIds: Set<String>,
    ) {
        val filtered = values
            .filter { sv ->
                !logOnlyDiscovered || (obdRepository.supportedPidIds.value?.contains(sv.pid.id) ?: true)
            }
            .filter { sv -> sv.pid.id in loggedPidIds }
        sessionRepository.writeReadings(sessionId, filtered)
    }
}
