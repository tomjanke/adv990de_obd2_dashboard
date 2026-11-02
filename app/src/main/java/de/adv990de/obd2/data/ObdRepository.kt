package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.InitResult
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.compactForLog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *    Fahrt nicht kosten; die verspaetete Antwort verwirft der naechste Befehl als Altbyte, sofern sie bis dahin eingetroffen ist — trifft sie erst
 *    danach ein, kann die Antwort des naechsten PID verschoben sein (ein uebersprungener PID, den der Watchdog zaehlt). Nur ein verlorener Link
 *    trennt wie ueberall.
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

    /** Die Abfrageschleife der Live-Werte ([livePids]); sie erfragt die Verbindung je Zyklus ueber `elm` und meldet ihre Rohantworten und ihre gebrochene Verbindung hierher. */
    private val poller = LivePoller(
        pidParser,
        currentController = { elm },
        onLinkBroken = ::disconnectIfCurrent,
        onCycleRaw = { lastCycleRaw = it },
    )

    @Volatile private var connection: ObdConnection? = null
    @Volatile private var elm: ElmController? = null

    /**
     * Die Antwort des Adapters auf den Reset (`ATZ`) beim Verbinden samt Dauer — gehoert zur AKTUELLEN Verbindung, `null` ohne Verbindung und nach
     * einem gescheiterten Aufbau. Nur fuer die Zeile "Connected to ..." im App-Log ([de.adv990de.obd2.obd.connectedLogMessage]).
     */
    @Volatile var adapterInit: InitResult? = null
        private set

    /** Die rohen Antworten des letzten Abfragezyklus je Befehl ("010C" -> "41 0C 19 31") — nur fuer die Diagnose in [dropFrozenLink]. */
    @Volatile private var lastCycleRaw: Map<String, String> = emptyMap()
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
     * Beendet die Verbindung, weil die Werte EINGEFROREN sind (das Fahrzeug antwortet, aber immer mit demselben Wert; Fahrten 52 und 60, `FrozenValuesGuard`),
     * und liefert vorher die Diagnose fuers App-Log: die rohen Antworten des letzten Zyklus und die Spannung, die der ADAPTER an seinem Anschluss misst (`ATRV`).
     * Sie trennt Adapter von Steuergeraet: zeigt `ATRV` eine lebendige Spannung, waehrend die Fahrzeugspannung (`0142`) feststeht, haengt das Steuergeraet oder
     * der Adapter wiederholt dessen letzte Antwort; ein Adapter ohne Antwort auf `ATRV` hing selbst. **`ATRV` ist ein Befehl an den Adapter, nichts geht ans Fahrzeug.**
     *
     * Wie die anderen Lesepfade trennt sie nur die Verbindung, auf der gelesen wurde ([disconnectIfCurrent]), und zaehlt NICHT als Trennen des Nutzers
     * ([disconnectByUser]): die Wiederverbindung soll von selbst kommen. Ohne Verbindung gibt es nichts zu trennen.
     */
    suspend fun dropFrozenLink(): String {
        val controller = elm ?: return "no adapter"
        val raw = lastCycleRaw.entries.joinToString("; ") { (command, answer) -> "$command=${answer.compactForLog()}" }
        // Ein Zeitlimit oder ein Verlust hier aendert nichts am Trennen; eine echte Abbruch-Ausnahme (keine ObdException) geht durch.
        val adapterVolts = try {
            controller.sendCommand("ATRV").compactForLog()
        } catch (e: ObdException) {
            "n/a (${e::class.simpleName})"
        }
        disconnectIfCurrent(controller)
        return "raw: $raw; ATRV $adapterVolts"
    }

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
        connection?.close()
        connection = null
        elm = null
        adapterInit = null
        lastCycleRaw = emptyMap()
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
            val (controller, supported, init) = withConnectDeadline(conn) {
                val controller = conn.connect()
                val init = controller.initialize()
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
                Triple(controller, supported, init)
            }
            _supportedPidIds.value = supported
            connection = conn
            elm = controller
            adapterInit = init
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
     * Pollt die PIDs fortlaufend und liefert die jeweils neuesten Werte; die Schleife (Eskalation bei BusInit, der Watchdog, die langsamen PIDs) steht in [LivePoller.poll],
     * dort auch die Beschreibung der Parameter. Die Zeitlimit-Richtlinie steht oben an der Klasse.
     */
    fun livePids(
        pids: List<PidDefinition> = PidRegistry.defaults,
        pollIntervalMs: Long = 500L,
        watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
        timeSource: TimeSource = TimeSource.Monotonic,
        slowPids: List<PidDefinition> = emptyList(),
        slowEveryCycles: Int = SLOW_PID_EVERY_CYCLES,
    ): Flow<List<SensorValue>> = poller.poll(pids, pollIntervalMs, watchdogTimeoutMs, timeSource, slowPids, slowEveryCycles)

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
