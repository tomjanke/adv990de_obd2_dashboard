package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * Obergrenze fuer den ganzen Verbindungsaufbau (Verbinden, ELM-Initialisierung, PID-Discovery). Grosszuegig, weil die
 * Protokollsuche des ELM327 bei einem K-Line-Motorrad lange dauern kann; sie soll nur ein ENDLOSES Haengen beenden.
 */
private const val CONNECT_TIMEOUT_MS = 60_000L

/** Nur relevant fuer direkte/Test-Aufrufer ohne eigenen Wert — die Produktion (ReadingsPipeline)
 *  uebergibt immer explizit den aus AppPreferences gelesenen, user-konfigurierten Wert. Bewusst aus
 *  dem AppPreferences-Default abgeleitet statt unabhaengig gepflegt: die `livePids(any())`-Stubs der
 *  JVM-Tests zeichnen diesen Default mit auf und matchen den Produktionsaufruf nur, solange beide
 *  Werte gleich sind. */
private const val DEFAULT_WATCHDOG_TIMEOUT_MS = AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS * 1_000L

/**
 * Die Verbindung zum Adapter und alle Lesevorgaenge darueber.
 *
 * **Zeitlimit-Richtlinie (PRO-13).** Ein Zeitlimit (`ObdException.Timeout`, 5 s ohne Prompt) laesst den Datenstrom in unbekanntem Zustand: die
 * Antwort kann spaeter eintreffen und wuerde beim naechsten Befehl fuer dessen Antwort gehalten (der ELM327 kennt keine Antwort-Kennung).
 * Je Lesepfad gilt deshalb, und zwar mit Absicht verschieden:
 *  - **Live-Abfrage** ([livePids]): den PID ueberspringen, der [Watchdog][isReadingsStale] eskaliert nach `watchdogTimeoutMs` — eine einzelne
 *    langsame Antwort ist im Betrieb normal und darf die Verbindung nicht kosten.
 *  - **Fehlercodes** ([readDtcs]) und **Freeze-Frame** ([readFreezeFrame]): beim ersten Zeitlimit abbrechen, trennen, weiterreichen. Es sind
 *    Folgen mehrerer Befehle, jede verschobene Antwort verfaelschte das Ergebnis; getrennt wird, damit der Reconnect den Strom zuruecksetzt —
 *    aber nur, wenn der Lesevorgang noch auf der AKTUELLEN Verbindung lief ([disconnectIfCurrent], PRO-11); ein ersetzter Leser reisst keine neue ein.
 *  - **Fehlercodes unterwegs** ([readDtcsOrSkip], die Fehlerwache der `ReadingsPipeline` jede Minute waehrend der Fahrt): ein Zeitlimit ueberspringt das
 *    Auslesen (`null`) und trennt **nie** — dieselben Befehle wie [readDtcs], aber das Nachlesen ist nur eine Zusatzauskunft und darf die laufende
 *    Fahrt nicht kosten; die verspaetete Antwort verwirft der naechste Befehl als Altbyte. Nur ein verlorener Link trennt wie ueberall.
 *  - **FIN** ([readVin]): das Zeitlimit wird gemeldet, die Verbindung bleibt. Die Pipeline fragt sie bei JEDEM Verbinden; ein Motorrad, das auf
 *    Modus 09 schweigt, geriete bei "trennen" in eine Schleife aus Verbinden, Zeitlimit, Trennen. Ein einzelner Befehl kann ausserdem keine Folge
 *    verschieben, und der naechste Befehl verwirft Altbytes ([de.adv990de.obd2.obd.ElmController]).
 *  - **Discovery** (beim Verbinden): ein Bereich ohne Antwort wird uebersprungen; der Aufbau steht unter einer eigenen Obergrenze.
 */
@Singleton
class ObdRepository @Inject constructor(
    private val connectionFactory: ConnectionFactory,
    private val pidParser: PidParser,
    dtcParser: DtcParser
) {

    private val diagnostics = DiagnosticReads(pidParser, dtcParser, onLinkBroken = ::disconnectIfCurrent)

    @Volatile private var connection: ObdConnection? = null
    @Volatile private var elm: ElmController? = null
    private val connectMutex = Mutex()

    /**
     * Die Verbindung, die gerade aufgebaut wird (haelt dabei [connectMutex]). Existiert nur, damit [disconnectByUser] sie
     * schliessen kann, OHNE auf den Lock zu warten: Ein blockierendes `connect()` haelt ihn, bis es endet.
     */
    @Volatile private var connecting: ObdConnection? = null

    /**
     * Obergrenze fuer Verbinden + Initialisieren + Discovery (siehe [withConnectDeadline]). Als Property statt
     * Konstruktor-Parameter, weil Hilt Kotlin-Defaultwerte nicht kennt; Tests setzen sie direkt.
     */
    internal var connectTimeoutMs: Long = CONNECT_TIMEOUT_MS

    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _supportedPidIds = MutableStateFlow<Set<String>?>(null)
    val supportedPidIds: StateFlow<Set<String>?> = _supportedPidIds.asStateFlow()

    /**
     * The last device that successfully connected in this process lifetime.
     * Null on cold launch until the first successful connect() call.
     * Not cleared by disconnect() — the reconnect path relies on this value being retained.
     */
    private val _lastConnectedDevice = MutableStateFlow<ScannedDevice?>(null)
    val lastConnectedDevice: StateFlow<ScannedDevice?> = _lastConnectedDevice.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMovingFlow: StateFlow<Boolean> = _isMoving.asStateFlow()

    fun setMoving(moving: Boolean) {
        if (_lastConnectedDevice.value?.type == BtType.SIMULATOR) return
        _isMoving.value = moving
    }

    /** Last complete poll cycle values — updated by DashboardViewModel, cleared on disconnect. */
    private val _latestReadings = MutableStateFlow<List<SensorValue>>(emptyList())
    val latestReadings: StateFlow<List<SensorValue>> = _latestReadings.asStateFlow()

    fun updateLatestReadings(readings: List<SensorValue>) {
        _latestReadings.value = readings
    }

    suspend fun connect(device: ScannedDevice) = connectMutex.withLock {
        connectLocked(device)
    }

    /**
     * Zaehlt die Trennungen, die der NUTZER ausgeloest hat ([disconnectByUser]) — im Gegensatz zu einem
     * verlorenen Link, bei dem das Repository selbst intern [disconnect] ruft. Wer automatisch
     * wiederverbindet oder einen Verbindungsverlust meldet, vergleicht den Zaehlerstand von vorher mit
     * dem von jetzt: Hat er sich geaendert, wollte der Nutzer getrennt sein.
     */
    private val _userDisconnectCount = MutableStateFlow(0)
    val userDisconnectCount: StateFlow<Int> = _userDisconnectCount.asStateFlow()

    /**
     * Trennt auf Wunsch des Nutzers ("Trennen" in der Benachrichtigung oder den Einstellungen, "Beenden").
     * Der Zaehler steigt VOR dem Warten auf den Lock: Laeuft gerade ein Verbindungsversuch (er haelt den
     * Lock, solange er dauert), ist der Wunsch trotzdem sofort sichtbar und die Wiederverbindungs-Schleife
     * kann aufhoeren, statt nach dem Versuch die gerade getrennte Verbindung wieder aufzubauen.
     */
    suspend fun disconnectByUser() {
        _userDisconnectCount.update { it + 1 }
        // Laeuft gerade ein Verbindungsaufbau, haelt er den Lock bis er endet — ein blockierendes connect() kann das
        // dauern. Ihn jetzt zu schliessen bricht ihn ab (er endet mit einem Fehler und gibt den Lock frei), statt dass
        // "Trennen"/"Beenden" hinter ihm warten (B6).
        connecting?.close()
        disconnect()
    }

    suspend fun disconnect() = connectMutex.withLock { resetLocked() }

    /**
     * Trennt nur, wenn [controller] noch die aktuelle Verbindung ist (PRO-11) — fuer die LESEPFADE, deren Fehler die Verbindung
     * beendet (Link-Verlust, Watchdog, ein Zeitlimit, das den Datenstrom in unbekanntem Zustand laesst). Ein Leser, der auf einer
     * inzwischen ERSETZTEN Verbindung scheitert — typisch: "Verbinden" zu einem anderen Adapter schliesst die alte, das blockierte
     * Lesen scheitert —, wartet mit seinem Trennen hinter [connectMutex], bis der neue Aufbau fertig ist. Ein unbedingtes
     * [disconnect] risse dann die frische Verbindung ein und loeste einen Verlust samt Reconnect aus. Ist die Verbindung schon
     * ersetzt oder weg, ist nichts mehr zu tun: [connectLocked] hat ihren Zustand beim Ersetzen bereits zurueckgesetzt.
     * Der WUNSCH des Nutzers bleibt das unbedingte [disconnect] bzw. [disconnectByUser].
     */
    private suspend fun disconnectIfCurrent(controller: ElmController) = connectMutex.withLock {
        if (elm === controller) resetLocked()
    }

    /** Schliesst die Verbindung und setzt alles zurueck, was zu ihr gehoerte. Must only be called while [connectMutex] is held. */
    private fun resetLocked() {
        _isConnected.value = false
        _isMoving.value = false
        _supportedPidIds.value = null
        _latestReadings.value = emptyList()
        connection?.close()
        connection = null
        elm = null
        // _lastConnectedDevice is intentionally NOT cleared: the reconnect path in
        // DashboardViewModel calls reconnect() after a connection drop and relies on this
        // value being retained. Clearing it here would silently break warm-launch auto-reconnect.
    }

    /**
     * Reconnects to the last successfully connected device — unless a connection is up by the time the
     * lock is acquired, in which case it does nothing.
     *
     * The check HAS to happen inside [connectMutex]: a caller that tests [isConnectedFlow] first has no
     * protection against a manual [connect] that finishes while this call is queued behind it, and
     * [connectLocked] starts by closing whatever connection exists — it would tear down the fresh one.
     * (Link loss and the watchdog both call [disconnect] before they report, so "connected" here always
     * means a connection somebody established deliberately.)
     * Throws [IllegalStateException] if no device has ever connected in this session.
     */
    suspend fun reconnect() = connectMutex.withLock {
        if (_isConnected.value) return@withLock
        val dev = _lastConnectedDevice.value
            ?: throw IllegalStateException("No previous device to reconnect to")
        connectLocked(dev)
    }

    /**
     * Connects to [device] unless a connection is already up (decided inside the lock, see [reconnect]).
     * For the AUTOMATIC paths (auto-connect at start, reconnect loop after a cold start): they must never
     * replace what the user connected in the meantime. A deliberate [connect] from the settings screen
     * stays unconditional — the user may well be switching adapters.
     */
    suspend fun connectIfDisconnected(device: ScannedDevice) = connectMutex.withLock {
        if (_isConnected.value) return@withLock
        connectLocked(device)
    }

    /** Must only be called while [connectMutex] is held. */
    private suspend fun connectLocked(device: ScannedDevice) {
        // Close any existing connection before replacing it — and drop what belonged to it: a reader of the old
        // connection that fails later does not disconnect any more once it is no longer the current one (PRO-11).
        resetLocked()

        val conn = connectionFactory.create(device)
        connecting = conn
        try {
            val (controller, supported) = withConnectDeadline(conn) {
                val controller = conn.connect()
                controller.initialize()
                // Best-effort: a discovery that merely fails or finds nothing must not abort the connection.
                // Two things are NOT "merely failing" and must not be swallowed: a cancelled caller (it would
                // otherwise come back from connect() "connected" although it was cancelled) and a dead link
                // (the connection would be declared up on a socket that is already gone). Both fall into the
                // catch below, which closes the connection.
                val supported = try {
                    discoverSupportedPids(controller)
                } catch (e: Exception) {
                    if (e is CancellationException || e is ObdException.ConnectionLost) throw e
                    null
                }
                controller to supported
            }
            _supportedPidIds.value = supported
            connection = conn
            elm = controller
            _isConnected.value = true
            _lastConnectedDevice.value = device
        } catch (e: Exception) {
            conn.close()   // prevent leak if connect() or initialize() throws
            throw e
        } finally {
            connecting = null
        }
    }

    /**
     * Fuehrt [block] (Verbinden, Initialisieren, Discovery) unter einer Obergrenze von [connectTimeoutMs] aus.
     *
     * Ein klassischer Bluetooth-Socket blockiert beim Verbinden den Thread und ist durch eine Coroutine nicht
     * abbrechbar; ohne Grenze haelt ein haengender Aufbau [connectMutex] auf unbestimmte Zeit, und "Beenden"/"Trennen"
     * warten dahinter (B6). Ein Waechter schliesst die Verbindung deshalb [conn] — das gibt den blockierenden Aufruf mit
     * einem Fehler frei — in drei Faellen:
     *  - die Frist laeuft ab: der Fehler wird zu [ObdException.Timeout] ("Adapter antwortet nicht");
     *  - der Aufrufer wird abgebrochen (ein blockierender Aufruf sieht das sonst nie);
     *  - (ueber [disconnectByUser], nicht hier) der Nutzer trennt.
     * Eine Antwort, die nur langsam kommt (Protokollsuche des ELM327: einige Sekunden, bei K-Line deutlich mehr), ist
     * kein Hanger — die Frist ist deshalb grosszuegig ([CONNECT_TIMEOUT_MS]); sie verhindert "fuer immer", nicht "langsam".
     */
    private suspend fun <T> withConnectDeadline(conn: ObdConnection, block: suspend () -> T): T = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val watchdog = launch {
            try {
                delay(connectTimeoutMs)
                timedOut.set(true)
                conn.close()
            } catch (e: CancellationException) {
                // Der Waechter wird abgebrochen, wenn der Block fertig ist (dann nichts schliessen) ODER wenn der Aufrufer
                // abgebrochen wurde (dann die Verbindung schliessen, damit ein blockierender Aufruf endet).
                if (!finished.get()) conn.close()
                throw e
            }
        }
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (timedOut.get()) throw ObdException.Timeout("connect")
            // War der Aufrufer inzwischen abgebrochen, ist der IO-Fehler eine Folge davon: als Abbruch weitermelden.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            finished.set(true)
            watchdog.cancel()
        }
    }

    /**
     * Continuously polls the given PIDs and emits the latest values.
     * Skips PIDs that return NO DATA without stopping the stream.
     *
     * Two independent escalation paths can force a reconnect, whichever crosses its own
     * threshold first: 5 consecutive BusInit errors (bus stuck), or the watchdog: a cycle without
     * a single reading that hit at least one Timeout or UnknownResponse, at a point where
     * [watchdogTimeoutMs] of real time have passed since the link last showed it works. That is
     * the last cycle with readings, or one the ECU answered entirely with NO DATA (e.g. ignition
     * off): a well-formed answer, so such a cycle restarts the clock rather than counting against
     * it, and NoData alone never trips the watchdog. It catches a silently frozen adapter/BLE
     * stack that keeps timing out without ever hitting BusInit, and an adapter that still answers
     * but only unparseably (e.g. echo back on after it reset itself), which a reconnect cures by
     * re-running initialize(). The check runs once per cycle, so it fires up to one cycle after
     * the threshold. See [isReadingsStale].
     *
     * [timeSource] only exists so tests can inject kotlinx-coroutines-test's virtual clock
     * (`testTimeSource`); production uses the default [TimeSource.Monotonic]. It has to be real
     * time: a single Timeout already blocks for ElmController's 5 s read timeout, so the earlier
     * approach of adding [pollIntervalMs] per empty cycle undercounted a real stall 30-40x.
     *
     * [slowPids] (nicht in [pids]) kommen zusaetzlich in jedem [slowEveryCycles]-ten Zyklus dran, der erste (Zaehler 0) eingeschlossen — die
     * Spannung fuer den Spannungsalarm aendert sich langsam und kostet 0,15-0,3 s je Befehl. Watchdog, BusInit und Teilzyklen behandeln so
     * einen Zyklus wie jeden anderen.
     */
    fun livePids(
        pids: List<PidDefinition> = PidRegistry.defaults,
        pollIntervalMs: Long = 500L,
        watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
        timeSource: TimeSource = TimeSource.Monotonic,
        slowPids: List<PidDefinition> = emptyList(),
        slowEveryCycles: Int = SLOW_PID_EVERY_CYCLES,
    ): Flow<List<SensorValue>> = flow {
        require(slowEveryCycles >= 1) { "slowEveryCycles must be at least 1, was $slowEveryCycles" }
        var consecutiveBusInitErrors = 0
        var lastSuccessMark = timeSource.markNow()
        var cycle = 0L
        while (true) {
            // elm is @Volatile; capture into a local so the reference stays alive even if
            // a concurrent disconnect() nulls elm after the null check.
            val controller = elm ?: break
            val values = mutableListOf<SensorValue>()
            var busInitOccurred = false
            var timeoutOrUnknownOccurred = false
            val cyclePids = if (slowPids.isNotEmpty() && cycle % slowEveryCycles == 0L) pids + slowPids else pids
            for (pid in cyclePids) {
                try {
                    val raw = controller.sendCommand(pid.id)
                    values.add(pidParser.parse(pid, raw))
                } catch (_: ObdException.NoData) {
                    // Benign: the ECU explicitly has nothing to report for this PID, so the link
                    // itself demonstrably works. A cycle of nothing else restarts the watchdog below.
                } catch (_: ObdException.UnknownResponse) {
                    // The adapter answered, but unparseably. Once persistent (e.g. echo back on
                    // after the adapter reset itself), no reading gets through: counts for the watchdog.
                    timeoutOrUnknownOccurred = true
                } catch (_: ObdException.Timeout) {
                    // skip this PID this cycle — adapter may be slow; counts for the watchdog
                    timeoutOrUnknownOccurred = true
                } catch (_: ObdException.BusInit) {
                    busInitOccurred = true
                    break  // bus re-initializing; skip rest of this poll cycle
                } catch (e: ObdException.ConnectionLost) {
                    // disconnect() acquires connectMutex. If reconnect() is currently holding
                    // that mutex (i.e., is mid-BT-connect), this NonCancellable block will
                    // suspend until reconnect() releases the lock. Self-resolving, not a true
                    // deadlock, but the NonCancellable section may run longer than expected.
                    withContext(NonCancellable) { disconnectIfCurrent(controller) }
                    throw e  // fatal — propagate to ViewModel
                }
            }
            if (busInitOccurred) {
                consecutiveBusInitErrors++
                // After 5 consecutive BusInit errors the bus is stuck — escalate to ConnectionLost
                // so the ViewModel triggers auto-reconnect instead of polling silently forever.
                if (consecutiveBusInitErrors >= 5) {
                    withContext(NonCancellable) { disconnectIfCurrent(controller) }
                    throw ObdException.ConnectionLost()
                }
            } else {
                consecutiveBusInitErrors = 0
            }
            // Silent drop: when all PIDs return NoData/Timeout/UnknownResponse in a cycle, nothing
            // is emitted. The collector sees no update for this cycle; the UI retains the last values.
            // Also suppress partial-cycle values collected before a BusInit interrupted the loop —
            // emitting them would show a stale mix of fresh and un-refreshed PID readings.
            if (!busInitOccurred && values.isNotEmpty()) {
                lastSuccessMark = timeSource.markNow()
                emit(values)
            } else if (timeoutOrUnknownOccurred) {
                // The watchdog. A NoData from another PID in the same cycle does not offset the
                // Timeout/UnknownResponse. BusInit keeps its own 5-strike counter above.
                if (isReadingsStale(lastSuccessMark.elapsedNow().inWholeMilliseconds, watchdogTimeoutMs)) {
                    withContext(NonCancellable) { disconnectIfCurrent(controller) }
                    throw ObdException.ConnectionLost()
                }
            } else if (!busInitOccurred) {
                // Entirely NoData this cycle: the link works, so nothing to escalate, but nothing
                // to emit either. Restart the watchdog's clock anyway, so a later isolated Timeout
                // is judged against this recent baseline, not against the last reading from before
                // a long, benign NO DATA stretch (e.g. parked, ignition off), which would escalate
                // on the spot.
                lastSuccessMark = timeSource.markNow()
            }
            cycle++
            delay(pollIntervalMs)
        }
    }

    // Die einmaligen Lesevorgaenge (Fehlercodes, Freeze-Frame, FIN) stehen in [DiagnosticReads]; ohne Verbindung liefern sie "nichts".
    suspend fun readDtcs(): List<DtcEntry> = elm?.let { diagnostics.readDtcs(it) } ?: emptyList()

    /** Das weiche Auslesen der Fehlercodes unterwegs: ein Zeitlimit ergibt `null` und trennt nicht (s. Zeitlimit-Richtlinie). Ohne Verbindung ebenfalls `null`, nie eine leere Liste. */
    suspend fun readDtcsOrSkip(): List<DtcEntry>? = elm?.let { diagnostics.readDtcsOrSkip(it) }

    suspend fun readFreezeFrame(): Map<String, String> = elm?.let { diagnostics.readFreezeFrame(it) } ?: emptyMap()

    suspend fun readVin(): String? = elm?.let { diagnostics.readVin(it) }

    companion object {
        /** Jeder wievielte Zyklus die `slowPids` von [livePids] mitfragt: die Spannung kostet 0,15-0,3 s je Befehl und aendert sich langsam. */
        const val SLOW_PID_EVERY_CYCLES = 4
    }
}
