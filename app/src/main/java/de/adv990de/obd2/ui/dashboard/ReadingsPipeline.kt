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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
     * Die Entscheidung bei eingefrorenen Werten (das Fahrzeug antwortet weiter, aber immer mit demselben Wert; s. [FrozenValuesGuard]). Haelt ueber Sitzungen,
     * ob fuer den laufenden Fall schon neu verbunden wurde — ein zweites Einfrieren nach dem Neuverbinden haelt die Werte zurueck, statt in einer Schleife zu verbinden.
     */
    private val frozenGuard = FrozenValuesGuard()

    /** Die Uhr der Erkennung eingefrorener Werte; nur die Tests setzen sie (Hilt kennt keine Kotlin-Defaultwerte im Konstruktor). */
    internal var frozenTimeSource: TimeSource = TimeSource.Monotonic

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
     * Der Zustand der Fahrzeugwerte dieser Sitzung (da / eingefroren / keine aktuellen, [ValuesState]): jeder Wechsel geht einmal ins App-Log und als Zeile in die Fahrt ([ValuesStateReporter]).
     * Mit der Sitzung neu begonnen ([resetSession]), damit er die Uhr [frozenTimeSource] nutzt, die Tests vorher setzen.
     */
    private val valuesState = ValuesStateReporter(log = ::logBestEffort, record = ::recordDerived)

    /** Die Fehlerwache der Sitzung (Nachlesen unterwegs, s. [FaultWatch]); die Pipeline reicht ihr Reconnect-Signal, Log und Fehlerhistorie durch. */
    private val faultWatch = FaultWatch(
        obdRepository = obdRepository,
        appPreferences = appPreferences,
        dtcHistoryRepository = dtcHistoryRepository,
        log = ::logBestEffort,
        requestReconnect = { connectionLostSignal.tryEmit(ConnectionLostCause.LINK) },
        isSessionRunning = { sessionRunning },
    )

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
    private val connectionLostSignal = MutableSharedFlow<ConnectionLostCause>(replay = 0, extraBufferCapacity = 1)

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
        val gpsSpeed = gpsSpeedFlow(scope, appPreferences.useGpsSpeed, gpsSpeedSource.speedKmh)
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
                        // Endet die Sitzung, waehrend die Verbindung noch steht (ein Fehler, der nicht trennt), laufen [gpsRefresh] und [staleness] weiter: ohne die
                        // letzten Werte zu vergessen, fuehrte jeder GPS-Fix sie wieder als live zusammen, und "veraltet" meldet eine beendete Sitzung nicht mehr.
                        runSession(slotIds, gpsSpeed).onCompletion { sessionRunning = false; lastObdReadings.value = null },
                        gpsRefresh(gpsSpeed),
                        staleness(),
                        faultWatch.events(),
                    )
                        .onStart { resetSession() }
                }
            }
        // connectionLostSignal als Geschwister, nicht als Teil der flatMapLatest-Kette — s. Doku
        // an [connectionLostSignal].
        return merge(readingsFlow, connectionLostSignal.map { SessionEvent.ConnectionLost(it) })
    }

    private fun resetSession() {
        lastObdReadings.value = null
        obdCycles.value = 0
        obdStale.value = false
        moving = false
        valuesState.begin(frozenTimeSource)
        faultWatch.reset()
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
        .flatMapLatest { cyclesAtStart ->
            flow {
                delay(OBD_STALE_AFTER_MS)
                // Die Frist gehoert zu dem Zyklus, mit dem sie begann. Trifft genau zu ihrem Ende der naechste ein (zwei PID-Zeitlimits dauern rund 10 s), ist der
                // Zaehler schon weiter, aber dieser Zweig noch nicht abgebrochen: ohne den Vergleich meldete er "veraltet" fuer Werte, die gerade gekommen sind.
                if (sessionRunning && obdCycles.value == cyclesAtStart) emit(SessionEvent.Stale)
            }
        }
        .onEach {
            markStale()
            // "Keine aktuellen Werte" ohne Sperre ist ein eigener Zustand; in der Sperre aendert das Ende der Frist nichts: der Grund steht schon (eingefroren).
            if (valuesState.state == ValuesState.OK) valuesState.enter(ValuesState.SILENT, System.currentTimeMillis())
        }

    /** Die Werte sind veraltet oder werden zurueckgehalten: [gpsRefresh] fuehrt nichts mehr neu zusammen, und die Fahrterkennung gilt als "steht" (die Geschwindigkeit liefert niemand mehr). */
    private fun markStale() {
        obdStale.value = true
        moving = false
        obdRepository.setMoving(false)
    }

    /**
     * Aktualisiert Anzeige und Fahrterkennung bei jedem NEUEN GPS-Fix zwischen zwei OBD-Zyklen —
     * unabhaengig vom Sitzungsstrom in [runSession], der Aufzeichnung und Lebenszyklus haelt.
     *
     * Fehlen die Fahrzeugwerte ([obdStale]: veraltet oder zurueckgehalten), werden die alten **nicht** neu zusammengefuehrt; die GPS-Geschwindigkeit geht aber als
     * [SessionEvent.GpsSpeed] an die Anzeige — sie haengt nicht am Adapter (Spezifikation 2026-10-24). Im Zustand "keine aktuellen Werte" ([ValuesState.SILENT]) gibt es
     * keinen Zyklus, der sie aufzeichnen koennte: hier schreibt jede GPS-Aenderung eine Zeile in die Fahrt (Auftrag 2026-10-25). In der Sperre ([ValuesState.FROZEN])
     * tut das schon jeder gehaltene Zyklus ([heldCycle]) — eine zweite Zeile je GPS-Aenderung stuende doppelt in der Fahrt.
     *
     * `drop(1)`: Der erste Wert ist der Replay des aktuellen [StateFlow]-Standes beim Abonnieren,
     * keine echte Aenderung — der ist bereits (oder wird gleich) durch den laufenden OBD-Zyklus
     * abgedeckt, der `gpsSpeed.value` ohnehin an jedem eigenen Takt liest. Ohne `drop(1)` koennte
     * dieser Ersatz-Wert in einer engen Race mit dem allerersten OBD-Zyklus um den ersten Platz im
     * Ereignisstrom konkurrieren.
     */
    private fun gpsRefresh(gpsSpeed: StateFlow<Float?>): Flow<SessionEvent> =
        gpsSpeed.drop(1).mapNotNull { gpsKmh ->
            if (obdStale.value) {
                if (valuesState.state == ValuesState.SILENT) recordGpsSpeedOnly(gpsKmh)
                return@mapNotNull SessionEvent.GpsSpeed(gpsKmh)
            }
            val readings = lastObdReadings.value ?: return@mapNotNull null
            val merged = mergeGpsSpeed(readings, gpsKmh)
            applyMerged(merged)
            SessionEvent.Values(merged, gpsKmh, isObdCycle = false)
        }

    private fun runSession(slotIds: List<String>, gpsSpeed: StateFlow<Float?>): Flow<SessionEvent> = flow {
        // Stand der Nutzer-Trennungen beim Start dieser Sitzung. "Trennen" schliesst den Socket; ein gerade
        // laufendes Lesen scheitert dann u. U. mit ConnectionLost, bevor flatMapLatest die Sitzung abbricht.
        // Dieser Verbindungsverlust ist gewollt und darf keinen Reconnect ausloesen (s. [lostLinkWantsReconnect]).
        val userDisconnectsAtStart = obdRepository.userDisconnectCount.value
        fun lostLinkWantsReconnect() = obdRepository.userDisconnectCount.value == userDisconnectsAtStart

        // Die Einstellungen der Sitzung einmal lesen, nicht pro Zyklus (s. [SessionPlan]).
        val plan = readSessionPlan(slotIds, obdRepository.supportedPidIds.value, appPreferences)

        val vin = try { obdRepository.readVin() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        // Fehlercodes beim Verbinden lesen (protokollieren, Fehlerhistorie, Basis der Fehlerwache — s. [FaultWatch.readOnConnect]); die Liste ist auch der Filter fuer
        // die Aufzeichnung. Die Basis geht an die Oberflaeche — auch ein Auslesen ohne Antwort (als null): sie behauptet dann weder Entwarnung noch Fehler.
        val dtcList = when (val read = faultWatch.readOnConnect(::lostLinkWantsReconnect)) {
            FaultWatch.ConnectRead.LinkLost -> return@flow
            is FaultWatch.ConnectRead.Answer -> read.codes
        }
        emit(SessionEvent.FaultCodes(dtcList, isBaseline = true))
        val hasDtcForRecording: Boolean = dtcList?.isNotEmpty() ?: true

        // "Nur Fahrten mit DTCs": Die Entscheidung steht schon fest — keine Fahrt anlegen, statt sie
        // aufzuzeichnen und am Ende zu verwerfen (Schreiblast; ein Prozess-Kill dazwischen hinterliess
        // eine DTC-lose Fahrt entgegen der Einstellung).
        val recordThisTrip = !(plan.logOnlyDtc && !hasDtcForRecording)

        val recorder = SessionRecorder(sessionRepository, crashReporter, ::logBestEffort)
        activeRecorder = recorder
        // Wahr, sobald die App die Verbindung wegen eingefrorener Werte selbst trennt (s. [FrozenAction.Reconnect]); der Verlust meldet sie als Grund.
        var droppedForFrozenValues = false
        try {
            // Dieselbe Antwort, die oben die Historie fuellt und "Nur Fahrten mit DTCs" entschieden hat, geht an die Fahrt:
            // null (Lesefehler) = nicht erfasst, leer = keine Codes, sonst die Liste.
            if (recordThisTrip) recorder.start(vin, dtcList)
            // Die Zeitgrenze der eingefrorenen Werte ist die Einstellung des Watchdogs ("Reconnect bei eingefrorenen Werten"), mindestens aber [FROZEN_MIN_CYCLES] Zyklen.
            frozenGuard.beginSession(frozenTimeSource, plan.watchdogTimeoutMs.milliseconds)
            emitAll(
                obdRepository.livePids(plan.selectedPids, watchdogTimeoutMs = plan.watchdogTimeoutMs, slowPids = plan.slowPids)
                    // Eingefrorene Werte erkennen, BEVOR irgendetwas sie fuer lebendig haelt: kein Zaehler, keine Anzeige, keine Aufzeichnung. Neu verbinden
                    // laeuft wie der Watchdog des Repositories ueber ConnectionLost (s. unten); gehaltene Zyklen erreichen die Anzeige nicht — sie bekommt statt
                    // ihrer EINMAL [SessionEvent.Frozen] (sofort "eingefroren", nicht erst nach [OBD_STALE_AFTER_MS]) und danach die GPS-Geschwindigkeit ([gpsRefresh]).
                    .transform { readings ->
                        when (val action = frozenGuard.onCycle(readings)) {
                            FrozenAction.Pass -> emit(freshCycle(recorder, gpsSpeed, readings))
                            FrozenAction.Hold -> heldCycle(recorder, gpsSpeed, readings)?.let { emit(it) }
                            is FrozenAction.Reconnect -> {
                                // Die App trennt selbst: der Grund geht mit dem Verlust an die Oberflaeche ("Werte eingefroren · verbinde neu").
                                droppedForFrozenValues = true
                                // NonCancellable wie beim Watchdog: dropFrozenLink() setzt isConnectedFlow auf false, und genau das bricht diese Sitzung ab
                                // (flatMapLatest) — das Signal und das Log darunter muessen trotzdem ankommen.
                                val details = withContext(NonCancellable) { obdRepository.dropFrozenLink() }
                                throw ObdException.ConnectionLost(
                                    reason = "values frozen: unchanged for ${action.cycles} cycles / ${action.unchangedFor.inWholeSeconds} s; $details",
                                )
                            }
                        }
                    }
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
                connectionLostSignal.tryEmit(if (droppedForFrozenValues) ConnectionLostCause.FROZEN_VALUES else ConnectionLostCause.LINK)
                // Hat die App selbst abgebrochen, steht der Grund dabei (eingefrorene Werte samt Diagnose); sonst hat der Link versagt.
                val message = e.reason?.let { "OBD connection dropped, reconnecting ($it)" }
                    ?: "OBD connection lost, reconnecting (readings stopped or bus stuck)"
                appLogRepository.log(LogLevel.WARN, message)
            } else {
                appLogRepository.log(LogLevel.INFO, "OBD readings ended by the user's disconnect, not reconnecting")
            }
        } catch (e: Exception) {
            crashReporter.recordException(e)
            emit(SessionEvent.Failed(ObdErrorMapper.toUiText(e)))
        } finally {
            // Endet die Sitzung in der Sperre (der Nutzer trennt, der Link reisst), sagt das Log es: sonst fehlte dort das Ende des Falls. Kein Marker — die Fahrt endet.
            if (valuesState.state == ValuesState.FROZEN) {
                logBestEffort(LogLevel.INFO, "Session ended while values were frozen (after ${valuesState.lasted().inWholeSeconds} s of holding them back)")
            }
            // Nur die eigene Aufzeichnung abmelden: eine Sitzung, die eine neue ersetzt (flatMapLatest), hat ihre schon eingetragen.
            if (activeRecorder === recorder) activeRecorder = null
            recorder.end()
        }
    }

    /**
     * Ein durchgelassener OBD-Zyklus: beendet "eingefroren" bzw. "keine aktuellen Werte" (Log und Marker, falls vorher einer galt), merkt ihn roh fuer [gpsRefresh], ergaenzt die
     * GPS-Geschwindigkeit, zeichnet auf und aktualisiert die Fahrterkennung.
     *
     * GPS ergaenzt die Geschwindigkeit, bevor aufgezeichnet und angezeigt wird — der Fahrzeugwert hat Vorrang. Der rohe GPS-Wert wird trotzdem mitgefuehrt
     * (s. [SessionEvent.Values]-KDoc) — die Ganganzeige braucht ihn unabhaengig davon, ob das Fahrzeug selbst eine Geschwindigkeit meldet.
     */
    private suspend fun freshCycle(recorder: SessionRecorder, gpsSpeed: StateFlow<Float?>, readings: List<SensorValue>): SessionEvent.Values {
        valuesState.enter(ValuesState.OK, readings.cycleTimestamp())
        // Roh zwischenspeichern, bevor GPS ergaenzt wird — s. Doku an [lastObdReadings].
        lastObdReadings.value = readings
        obdStale.value = false
        obdCycles.update { it + 1 }
        val gpsKmh = gpsSpeed.value
        val values = mergeGpsSpeed(readings, gpsKmh)
        recorder.record(values)
        applyMerged(values)
        return SessionEvent.Values(values, gpsKmh)
    }

    /**
     * Ein zurueckgehaltener Zyklus: die Fahrzeugwerte erreichen weder Anzeige noch Aufzeichnung, die **GPS-Geschwindigkeit** wird aber weiter aufgezeichnet (je gehaltenem Zyklus eine Zeile,
     * Quelle GPS wie sonst — sie haengt nicht am Adapter; ohne Fix keine). Der erste gehaltene Zyklus beginnt die Sperre: Log (mit dem gehaltenen Zyklus), Marker, "veraltet" und das
     * Ergebnis [SessionEvent.Frozen]; jeder weitere liefert `null`.
     */
    private suspend fun heldCycle(recorder: SessionRecorder, gpsSpeed: StateFlow<Float?>, readings: List<SensorValue>): SessionEvent? {
        val started = valuesState.enter(ValuesState.FROZEN, readings.cycleTimestamp(), held = readings)
        if (started) markStale()
        val gpsRow = mergeGpsSpeed(emptyList(), gpsSpeed.value)
        if (gpsRow.isNotEmpty()) recorder.record(gpsRow)
        return if (started) SessionEvent.Frozen else null
    }

    /** Schreibt die GPS-Geschwindigkeit allein in die laufende Fahrt (Quelle GPS, wie sonst); ohne brauchbaren Fix, ohne Sitzung oder ohne angelegte Fahrt passiert nichts. */
    private suspend fun recordGpsSpeedOnly(gpsKmh: Float?) {
        val row = mergeGpsSpeed(emptyList(), gpsKmh)
        if (row.isNotEmpty()) activeRecorder?.record(row)
    }

    /** Die Zeit eines Zyklus: der neueste Zeitstempel seiner Werte (so steht der Marker an der Stelle der Fahrt, an der er gilt). */
    private fun List<SensorValue>.cycleTimestamp(): Long = maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()

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
        // Fahrterkennung aus der Geschwindigkeit — gleich ob sie vom Fahrzeug oder vom GPS kommt, mit
        // Hysterese (s. isMoving): GPS-Rauschen im Stand schaltet das Hauptmenue nicht mehr um.
        // Fehlt sie ganz, gilt "steht", damit die Navigation nie dauerhaft ausgeblendet bleibt.
        val speed = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
        moving = isMoving(speed?.value, moving)
        obdRepository.setMoving(moving)
    }
}
