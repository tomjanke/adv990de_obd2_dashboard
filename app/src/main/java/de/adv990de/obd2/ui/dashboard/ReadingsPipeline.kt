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
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
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

    /** Die Aufzeichnung der laufenden Sitzung (`null` ausserhalb einer Sitzung) — das Ziel von [recordDerived]. */
    @Volatile
    private var activeRecorder: SessionRecorder? = null

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

    /**
     * Der Stand des letzten erfolgreichen Auslesens der Fehlercodes (Code, ob er nur aussteht) — die Basis fuer die Entscheidung der
     * [faultWatch], ob nach 10 s statt nach 60 s nachgelesen wird. `null`, solange nichts erfolgreich gelesen wurde (neue Sitzung, Auslesen
     * beim Verbinden ohne Antwort): dann gibt es nichts zu vergleichen. Geschrieben von [runSession] (Auslesen beim Verbinden) und [faultWatch].
     */
    @Volatile
    private var lastFaultSet: Set<Pair<String, Boolean>>? = null

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
                        faultWatch(),
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
        lastFaultSet = null
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
            .map { (merged, gpsKmh) -> SessionEvent.Values(merged, gpsKmh, isObdCycle = false) }

    private fun runSession(slotIds: List<String>, gpsSpeed: StateFlow<Float?>): Flow<SessionEvent> = flow {
        // Stand der Nutzer-Trennungen beim Start dieser Sitzung. "Trennen" schliesst den Socket; ein gerade
        // laufendes Lesen scheitert dann u. U. mit ConnectionLost, bevor flatMapLatest die Sitzung abbricht.
        // Dieser Verbindungsverlust ist gewollt und darf keinen Reconnect ausloesen (s. [lostLinkWantsReconnect]).
        val userDisconnectsAtStart = obdRepository.userDisconnectCount.value
        fun lostLinkWantsReconnect() = obdRepository.userDisconnectCount.value == userDisconnectsAtStart

        val supported = obdRepository.supportedPidIds.value
        val selectedPids = selectPids(
            slotIds,
            supported = supported,
            dynamicThresholdEnabled = appPreferences.rpmDynamicThreshold.first(),
        )

        // Aufzeichnungs-Einstellung einmal pro Sitzung lesen, nicht pro Zyklus.
        val logOnlyDtc = appPreferences.logOnlyDtcSessions.first()
        val watchdogTimeoutMs = appPreferences.obdWatchdogTimeoutSeconds.first() * 1_000L
        // Die Spannung nur fuer den Alarm: jeden 4. Zyklus, wenn sie keine Kachel ist. Wie die dynamische Schwelle einmal je Sitzung gelesen —
        // eingeschaltet waehrend der Verbindung wirkt die Abfrage ab der naechsten (der Tracker liest den Schalter selbst live).
        val slowPids = selectSlowPids(selectedPids, supported, appPreferences.alarmSettings.first().voltageEnabled)

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
            // Keine positive Antwort (NO DATA, Busfehler, Unverstaendliches — A9): kein Verbindungsverlust, die Sitzung laeuft
            // weiter, aber die Oberflaeche behauptet keine "Entwarnung" (nichts wird in der Historie vermerkt) und das App-Log
            // haelt fest, WAS der Adapter geantwortet hat — genau das Roh-Material, das fuer A9 Teil 2 gebraucht wird.
            logBestEffort(LogLevel.WARN, "Fault-code read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (dtcList?.isNotEmpty() == true) {
            appLogRepository.log(LogLevel.WARN, "Active DTCs on connect: ${dtcList.joinToString { if (it.isPending) "${it.code} (pending)" else it.code }}")
        }
        // Lokale Fehlerhistorie: jede Fahrt fuellt sie passiv. Nur nach einem erfolgreichen Auslesen
        // (null = Lesefehler: nichts aufzeichnen). record() ist selbst "best effort" — die Absicherung
        // hier deckt das Unerwartete ab, denn eine Ausnahme an dieser Stelle wuerde die ganze Sitzung kosten.
        if (dtcList != null) recordFaultHistory(dtcList)
        // Die Basis der Fehlerwache: dieser Stand gilt als bekannt, ein spaeter hinzukommender Code ist neu. Auch ein Auslesen ohne Antwort wird
        // gemeldet (als null) — die Oberflaeche behauptet dann weder Entwarnung noch Fehler, und die Wache hat noch nichts zum Vergleichen.
        lastFaultSet = dtcList?.toFaultSet()
        emit(SessionEvent.FaultCodes(dtcList, isBaseline = true))
        val hasDtcForRecording: Boolean = dtcList?.isNotEmpty() ?: true

        // "Nur Fahrten mit DTCs": Die Entscheidung steht schon fest — keine Fahrt anlegen, statt sie
        // aufzuzeichnen und am Ende zu verwerfen (Schreiblast; ein Prozess-Kill dazwischen hinterliess
        // eine DTC-lose Fahrt entgegen der Einstellung).
        val recordThisTrip = !(logOnlyDtc && !hasDtcForRecording)

        val recorder = SessionRecorder(sessionRepository, crashReporter, ::logBestEffort)
        activeRecorder = recorder
        try {
            // Dieselbe Antwort, die oben die Historie fuellt und "Nur Fahrten mit DTCs" entschieden hat, geht an die Fahrt:
            // null (Lesefehler) = nicht erfasst, leer = keine Codes, sonst die Liste.
            if (recordThisTrip) recorder.start(vin, dtcList)
            emitAll(
                obdRepository.livePids(selectedPids, watchdogTimeoutMs = watchdogTimeoutMs, slowPids = slowPids)
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
                    .onEach { (values, _) -> recorder.record(values) }
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
            // Nur die eigene Aufzeichnung abmelden: eine Sitzung, die eine neue ersetzt (flatMapLatest), hat ihre schon eingetragen.
            if (activeRecorder === recorder) activeRecorder = null
            recorder.end()
        }
    }

    /**
     * Die Fehlerwache: liest die Fehlercodes waehrend der Sitzung nach und meldet jedes erfolgreiche Auslesen als [SessionEvent.FaultCodes]
     * (`isBaseline = false`). Das erste Nachlesen kommt nach [FAULT_WATCH_INTERVAL_MS], danach im selben Abstand; **aendert sich der Stand
     * gegenueber dem vorigen** (auch gegenueber der Basis vom Verbinden), folgt das naechste nach [FAULT_RECHECK_MS] — ein Code, der gerade
     * auftaucht oder verschwindet, soll nicht eine volle Minute lang unbestaetigt stehen. Gelesen wird weich ([ObdRepository.readDtcsOrSkip]):
     * ein Zeitlimit oder NO DATA ergibt `null`, das hier nichts meldet und nichts aendert — kein Auslesen ist keine Aussage.
     *
     * Der Schalter „Fehler unterwegs melden“ (`alarmSettings.faultsEnabled`) wird bei **jedem** Durchlauf gelesen, nicht einmal je Sitzung: ein
     * Ein- oder Ausschalten wirkt beim naechsten Durchlauf. Der Fluss gehoert zur Sitzung: er endet mit ihr (Verbindungsverlust beendet das
     * `merge` in [events]; eine Sitzung, deren Quelle zu ist, nicht mehr — dann [sessionRunning] falsch) und liest dann nichts mehr.
     *
     * Jedes erfolgreiche Auslesen geht, wie das beim Verbinden, in die Fehlerhistorie (best effort, [recordFaultHistory]). Jeder unerwartete
     * Fehler des Nachlesens wird nur ins App-Log geschrieben: die Wache ist eine Zusatzauskunft und darf die Live-Anzeige nie beenden.
     */
    private fun faultWatch(): Flow<SessionEvent> = flow {
        // Stand der Nutzer-Trennungen beim Start der Sitzung — wie in [runSession]: ein Lesen, das scheitert, WEIL der Nutzer trennte, ist kein
        // Verbindungsverlust und loest keinen Reconnect aus.
        val userDisconnectsAtStart = obdRepository.userDisconnectCount.value
        var wait = FAULT_WATCH_INTERVAL_MS
        while (true) {
            delay(wait)
            wait = FAULT_WATCH_INTERVAL_MS
            if (!sessionRunning) return@flow
            if (!appPreferences.alarmSettings.first().faultsEnabled) continue
            val codes = try {
                obdRepository.readDtcsOrSkip()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ObdException.ConnectionLost) {
                // Wie beim Auslesen beim Verbinden (s. [runSession]): readDtcsOrSkip() hat getrennt, isConnectedFlow ist schon false, und der
                // Wechsel bricht diese Coroutine am naechsten Suspendierungspunkt ab — deshalb tryEmit() (nicht-suspendierend, s. Doku an
                // [connectionLostSignal]) zuerst, das Log danach. Steht die Verbindung noch (ersetzt, ein anderer Leser trennte): nichts tun,
                // die neue Verbindung startet ohnehin eine neue Sitzung.
                if (!obdRepository.isConnectedFlow.value) {
                    if (obdRepository.userDisconnectCount.value == userDisconnectsAtStart) {
                        connectionLostSignal.tryEmit(Unit)
                        logBestEffort(LogLevel.WARN, "Periodic fault-code read lost the adapter link, reconnecting")
                    } else {
                        logBestEffort(LogLevel.INFO, "Periodic fault-code read ended by the user's disconnect, not reconnecting")
                    }
                }
                return@flow
            } catch (e: Exception) {
                logBestEffort(LogLevel.WARN, "Periodic fault-code read failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            } ?: continue
            recordFaultHistory(codes)
            emit(SessionEvent.FaultCodes(codes, isBaseline = false))
            val now = codes.toFaultSet()
            val before = lastFaultSet
            if (before != null && now != before) wait = FAULT_RECHECK_MS
            lastFaultSet = now
        }
    }

    /**
     * Traegt ein erfolgreiches Auslesen in die lokale Fehlerhistorie ein. `record()` ist selbst „best effort“ — die Absicherung hier deckt das
     * Unerwartete ab, denn eine Ausnahme an dieser Stelle wuerde die ganze Sitzung (beim Verbinden) bzw. die Wache kosten.
     */
    private suspend fun recordFaultHistory(codes: List<DtcEntry>) {
        try { dtcHistoryRepository.record(codes) } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /**
     * Schreibt die abgeleiteten Werte eines OBD-Zyklus — den Gang, den die Gang-Box zeigt, und die Farbe des Oel-Balkens — in die laufende Fahrt
     * (Nutzerwuensche 2026-10-14). Beide entstehen im `DashboardViewModel` (`GearTracker`, `OilBarTracker`: Hysterese, Entprellen, Anzeige-Halten,
     * GPS-Gegenprobe), die Aufzeichnung gehoert hierher — eine zweite Berechnung an dieser Stelle wuerde von der Anzeige abweichen. Das ViewModel
     * ruft es je OBD-Zyklus ([SessionEvent.Values.isObdCycle]), mit dem Zeitstempel des Zyklus, und gibt die Zeilen eines Zyklus zusammen
     * (ein Schreibvorgang).
     *
     * Ohne laufende Sitzung oder ohne angelegte Fahrt ("nur Fahrten mit DTCs") passiert nichts; nach dem Ende der Sitzung werden spaet
     * eintreffende Werte verworfen (der Recorder ist dann zu). Wirft nie ausser der Abbruch-Ausnahme: ein Datenbankfehler wird einmal je
     * Sitzung gemeldet, wie bei den Messwerten. **Reihenfolge:** die Zeilen folgen dem Zyklus, stehen aber nicht zwingend direkt hinter
     * ihm (das ViewModel schreibt aus einer eigenen Coroutine); der Zeitstempel ist der des Zyklus.
     */
    internal suspend fun recordDerived(values: List<SensorValue>) {
        activeRecorder?.record(values)
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

    companion object {
        /** Abstand der Fehlerwache: das erste Nachlesen kommt so lange nach dem Sitzungsbeginn, jedes weitere im selben Abstand (zwei Befehle, rund 0,3-0,5 s je Minute — geschaetzt). */
        internal const val FAULT_WATCH_INTERVAL_MS = 60_000L

        /** Nachpruefung nach einer Aenderung des Stands gegenueber dem vorigen Auslesen — anstelle des vollen Abstands. */
        internal const val FAULT_RECHECK_MS = 10_000L
    }
}

/** Der Stand eines Auslesens fuer den Vergleich: Code und ob er nur aussteht — ein Wechsel von ausstehend zu bestaetigt ist eine Aenderung. */
private fun List<DtcEntry>.toFaultSet(): Set<Pair<String, Boolean>> = map { it.code to it.isPending }.toSet()
