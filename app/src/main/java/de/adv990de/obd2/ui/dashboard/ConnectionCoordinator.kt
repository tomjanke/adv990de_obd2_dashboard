package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import de.adv990de.obd2.ObdServiceController
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Anzahl der Wiederverbindungs-Versuche — fest, nur die Basis-Verzoegerung ist konfigurierbar
 *  (s. [reconnectDelays]). */
internal const val RECONNECT_ATTEMPT_COUNT = 5

/**
 * Wartezeiten der Wiederverbindungs-Versuche, in Millisekunden: verdoppelt sich je Versuch ab
 * [baseDelaySeconds] (Backlog-Cleanup, Sweep 2026-09-28/29 — vorher hartcodiert als
 * `listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L)`). Der bisherige Default von 3s
 * (`AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS`) reproduziert exakt diese Werte.
 */
internal fun reconnectDelays(baseDelaySeconds: Int): List<Long> =
    List(RECONNECT_ATTEMPT_COUNT) { i -> baseDelaySeconds * 1_000L * (1L shl i) }

/**
 * Was der Koordinator dem ViewModel ueber den Verbindungszustand meldet. Bewusst Ereignisse und
 * kein Zustand: Der Koordinator beschreibt, was passiert ist; wie sich das auf die Oberflaeche
 * auswirkt, entscheidet allein das ViewModel.
 */
sealed interface ConnectionEvent {
    /** Ein Versuch laeuft. [attempt] ist 1-basiert; 0 bedeutet "Auto-Connect beim Start". */
    data class Attempting(val attempt: Int, val total: Int) : ConnectionEvent

    /** Verbindung steht. Der Messwert-Strom uebernimmt ab jetzt die Anzeige. */
    data object Connected : ConnectionEvent

    /** Endgueltig gescheitert — kein Geraet, Bluetooth aus, oder alle Versuche verbraucht. */
    data class Failed(val error: UiText) : ConnectionEvent

    /**
     * Der Nutzer hat getrennt, waehrend eine Wiederverbindung lief ("Trennen" in der Benachrichtigung,
     * in den Einstellungen, "Beenden"). Die Schleife hoert auf — ohne Fehler: Der Nutzer wollte
     * getrennt sein, das ist kein Ausfall.
     */
    data object Aborted : ConnectionEvent
}

/**
 * Haelt die Verbindung zum Adapter: Auto-Connect beim Start, Wiederverbindung mit wachsenden
 * Wartezeiten, Start und Stopp des Foreground Service, Protokollierung.
 *
 * Herausgeloest aus `DashboardViewModel`, das mit 427 Zeilen sieben Zustaendigkeiten trug. Der
 * Auslöser war messbar: Eine einzige Verhaltensaenderung am Messwert-Pfad liess 18 Tests fallen,
 * von denen keiner die Verbindung betraf. Beide Teile sind jetzt einzeln pruefbar.
 *
 * Die Klasse besitzt keinen eigenen Scope. Sie bekommt den des ViewModels in [start] und stirbt
 * damit automatisch mit ihm — ein eigener Scope waere eine zweite Lebensdauer, die jemand
 * aufraeumen muesste.
 */
class ConnectionCoordinator @Inject constructor(
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
    private val bluetoothAdapter: BluetoothAdapter,
    private val serviceController: ObdServiceController,
) {

    // Getrennt vom Messwert-Strom: flatMapLatest dort wuerde die Schleife in dem Moment abbrechen,
    // in dem isConnectedFlow auf false geht — und das tut es, sobald livePids() bei Verbindungs-
    // verlust disconnect() ruft. Der Trigger ueberlebt das.
    //
    // `replay = 1` schliesst ein Zustellungsloch: Ein SharedFlow ohne Replay verwirft, was vor dem
    // ersten Sammler emittiert wird. Der Auto-Connect kann aber scheitern, bevor die Schleife im
    // selben Scope ueberhaupt zu sammeln begonnen hat — dann ginge die Aufforderung zur
    // Wiederverbindung verloren und die App bliebe stumm getrennt. Ein Sammler pro Instanz, also
    // gibt es auch keine verspaetete Doppelzustellung.
    //
    // Der Wert ist der Stand von [ObdRepository.userDisconnectCount] ZUM ZEITPUNKT der Aufforderung:
    // Trennt der Nutzer danach (auch noch bevor die Schleife die Aufforderung abgeholt hat), unterscheidet
    // sich der Stand — und die Runde wird verworfen. Eine Trennung VOR der Aufforderung unterdrueckt sie
    // dagegen nicht: ein spaeterer Verbindungsverlust soll wieder verbinden.
    private val trigger = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)

    /**
     * Startet Auto-Connect und Wiederverbindungs-Schleife im uebergebenen Scope.
     *
     * @param onEvent wird aus den Coroutinen des Scopes gerufen. Ein Callback statt eines Flows,
     *   weil es hier keine Zustellungsluecke geben darf: Ein SharedFlow ohne Replay verwirft
     *   Ereignisse, die vor dem ersten Sammler entstehen, und der Auto-Connect feuert sofort.
     */
    fun start(scope: CoroutineScope, onEvent: (ConnectionEvent) -> Unit) {
        scope.launch { runReconnectLoop(onEvent) }
        scope.launch { autoConnect(onEvent) }
    }

    /** Stoesst die Wiederverbindung an. Laeuft die Schleife bereits, verpufft der Aufruf. */
    fun requestReconnect() {
        trigger.tryEmit(obdRepository.userDisconnectCount.value)
    }

    /** Hat der Nutzer seit [requestedAt] getrennt? */
    private fun userDisconnectedSince(requestedAt: Int): Boolean =
        obdRepository.userDisconnectCount.value != requestedAt

    private suspend fun abort(onEvent: (ConnectionEvent) -> Unit) {
        appLogRepository.log(LogLevel.INFO, "Reconnect cancelled: the user disconnected")
        onEvent(ConnectionEvent.Aborted)
    }

    // ── Auto-Connect ─────────────────────────────────────────────────────────
    // Feuert einmal beim Kaltstart: Gibt es ein gespeichertes Geraet und keine aktive Verbindung,
    // wird ohne Wartezeit verbunden. Scheitert das, uebernimmt die Wiederverbindungs-Schleife.
    private suspend fun autoConnect(onEvent: (ConnectionEvent) -> Unit) {
        if (obdRepository.isConnectedFlow.value) return
        val savedDevice = appPreferences.lastDevice.first() ?: return
        onEvent(ConnectionEvent.Attempting(attempt = 0, total = RECONNECT_ATTEMPT_COUNT))
        appLogRepository.log(LogLevel.INFO, "Connecting to ${savedDevice.name}…")
        try {
            // Nicht connect(): Der Nutzer koennte in den ersten Sekunden selbst verbunden haben, und der
            // Auto-Connect darf diese Verbindung nicht ersetzen (die Entscheidung faellt im Repository
            // unter dem Lock, s. ObdRepository.connectIfDisconnected).
            obdRepository.connectIfDisconnected(savedDevice)
            appLogRepository.log(LogLevel.INFO, "Connected to ${savedDevice.name}")
            serviceController.start()
            onEvent(ConnectionEvent.Connected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogRepository.log(LogLevel.ERROR, "Connection error: ${e.message ?: e.javaClass.simpleName}")
            requestReconnect()
        }
    }

    // ── Wiederverbindung ─────────────────────────────────────────────────────
    // Die Pruefung "schon verbunden?" nach dem delay() ist nur ein billiger Vorab-Test. Massgeblich ist
    // die im Repository unter dem Lock (ObdRepository.reconnect()/connectIfDisconnected()): Eine manuelle
    // Verbindung, die waehrend eines laufenden Versuchs fertig wird, wird dort nicht mehr ersetzt.
    //
    // Der Nutzer-Wunsch "getrennt" beendet die Schleife (ObdRepository.userDisconnectCount): in der
    // Wartezeit sofort, vor jedem Versuch, und nach einem Versuch, der waehrenddessen fertig wurde — dann
    // weder Dienst-Start noch "Connected".
    private suspend fun runReconnectLoop(onEvent: (ConnectionEvent) -> Unit) {
        trigger.collect { requestedAt ->
            if (userDisconnectedSince(requestedAt)) {
                abort(onEvent)
                return@collect
            }
            // Beim Kaltstart ist das Geraet im Speicher null — dann aus dem DataStore. Einmal vor
            // der Schleife gelesen: Die Adresse aendert sich zwischen Versuchen nicht.
            val inMemoryDevice = obdRepository.lastConnectedDevice.value
            val coldLaunchDevice = if (inMemoryDevice != null) null else appPreferences.lastDevice.first()
            if (inMemoryDevice == null && coldLaunchDevice == null) {
                // Kein Geraet bekannt — sofort abbrechen statt 93 Sekunden lang ins Leere zu warten.
                appLogRepository.log(LogLevel.WARN, "No device configured")
                fail(onEvent, R.string.err_no_device)
                return@collect
            }

            // Einmal vor der Schleife gelesen, wie coldLaunchDevice oben: die Basis-Verzoegerung
            // aendert sich zwischen Versuchen derselben Reconnect-Runde nicht.
            val baseDelaySeconds = appPreferences.reconnectBaseDelaySeconds.first()
            for ((attempt, delayMs) in reconnectDelays(baseDelaySeconds).withIndex()) {
                // Der Nutzer koennte inzwischen manuell verbunden haben.
                if (obdRepository.isConnectedFlow.value) return@collect
                if (userDisconnectedSince(requestedAt)) {
                    abort(onEvent)
                    return@collect
                }
                onEvent(ConnectionEvent.Attempting(attempt + 1, RECONNECT_ATTEMPT_COUNT))
                appLogRepository.log(LogLevel.INFO, "Reconnect attempt ${attempt + 1}/$RECONNECT_ATTEMPT_COUNT")
                // Die Wartezeit endet vorzeitig, sobald der Nutzer trennt (StateFlow: kein Zustellungsloch).
                val interrupted = withTimeoutOrNull(delayMs) {
                    obdRepository.userDisconnectCount.first { it != requestedAt }
                } != null
                if (interrupted) {
                    abort(onEvent)
                    return@collect
                }
                if (!bluetoothAdapter.isEnabled) {
                    appLogRepository.log(LogLevel.WARN, "Bluetooth disabled")
                    fail(onEvent, R.string.obd_err_bluetooth_disabled)
                    return@collect
                }
                // Zweite Pruefung: Der Nutzer koennte waehrend der Wartezeit verbunden haben.
                if (obdRepository.isConnectedFlow.value) return@collect
                try {
                    if (inMemoryDevice != null) {
                        obdRepository.reconnect()
                    } else {
                        obdRepository.connectIfDisconnected(coldLaunchDevice!!)  // oben auf non-null geprueft
                    }
                    if (userDisconnectedSince(requestedAt)) {
                        // Der Versuch ist waehrend des "Trennen" fertig geworden; dessen Trennung folgt
                        // gleich (sie wartet auf denselben Lock). Weder Dienst starten noch Connected melden.
                        abort(onEvent)
                        return@collect
                    }
                    val name = inMemoryDevice?.name ?: coldLaunchDevice?.name ?: "device"
                    appLogRepository.log(LogLevel.INFO, "Connected to $name")
                    serviceController.start()
                    onEvent(ConnectionEvent.Connected)
                    return@collect  // isConnectedFlow wird true → der Messwert-Strom startet neu
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Naechster Versuch. Die Oberflaeche zeigt nur das Endergebnis; der Grund jedes
                    // gescheiterten Versuchs steht im App-Log.
                    appLogRepository.log(
                        LogLevel.WARN,
                        "Reconnect attempt ${attempt + 1}/$RECONNECT_ATTEMPT_COUNT failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
            appLogRepository.log(LogLevel.ERROR, "Connection failed after $RECONNECT_ATTEMPT_COUNT attempts")
            fail(onEvent, R.string.err_reconnect_fail)
        }
    }

    private fun fail(onEvent: (ConnectionEvent) -> Unit, messageRes: Int) {
        onEvent(ConnectionEvent.Failed(UiText.StringResource(messageRes)))
        serviceController.stop()
    }
}
