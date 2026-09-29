package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.location.LocationPermissions
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** Whether ObdForegroundService should request the `location` foreground-service type on this
 *  (re)connect. Both conditions are required: the user opted in, and Android will actually
 *  deliver background location callbacks only when the permission is granted. */
internal fun shouldIncludeLocationServiceType(useGpsSpeed: Boolean, hasBackgroundLocation: Boolean): Boolean =
    useGpsSpeed && hasBackgroundLocation

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
    @ApplicationContext private val appContext: Context,
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
    private val trigger = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

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
        trigger.tryEmit(Unit)
    }

    // GPS-Speed im Hintergrund braucht ACCESS_BACKGROUND_LOCATION *und* dass der Service den Typ
    // `location` beim Start anfordert (siehe ObdForegroundService.resolveForegroundServiceType).
    // Ohne die Berechtigung bleibt includeLocation false — der Service startet dann wie bisher
    // nur mit dem Typ `connectedDevice`, kein Verhaltensunterschied fuer Nutzer ohne GPS-Speed.
    private suspend fun startObdForegroundService() {
        val includeLocation = shouldIncludeLocationServiceType(
            useGpsSpeed = appPreferences.useGpsSpeed.first(),
            hasBackgroundLocation = LocationPermissions.hasBackgroundLocation(appContext),
        )
        appContext.startForegroundService(ObdForegroundService.startIntent(appContext, includeLocation))
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
            obdRepository.connect(savedDevice)
            appLogRepository.log(LogLevel.INFO, "Connected to ${savedDevice.name}")
            startObdForegroundService()
            onEvent(ConnectionEvent.Connected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogRepository.log(LogLevel.ERROR, "Connection error: ${e.message ?: "unknown"}")
            requestReconnect()
        }
    }

    // ── Wiederverbindung ─────────────────────────────────────────────────────
    // Bekannter Rest-TOCTOU: Zwischen der zweiten Pruefung nach dem delay() und dem Mutex im
    // Repository liegen Nanosekunden. Eine manuelle Verbindung aus den Einstellungen, die genau
    // in diesem Augenblick fertig wird, wuerde von der automatischen ueberschrieben. Das zu
    // schliessen braeuchte einen gemeinsamen Mutex ueber Pruefung und Verbindungsaufbau.
    private suspend fun runReconnectLoop(onEvent: (ConnectionEvent) -> Unit) {
        trigger.collect {
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
                onEvent(ConnectionEvent.Attempting(attempt + 1, RECONNECT_ATTEMPT_COUNT))
                appLogRepository.log(LogLevel.INFO, "Reconnect attempt ${attempt + 1}/$RECONNECT_ATTEMPT_COUNT")
                delay(delayMs)
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
                        obdRepository.connect(coldLaunchDevice!!)  // oben auf non-null geprueft
                    }
                    val name = inMemoryDevice?.name ?: coldLaunchDevice?.name ?: "device"
                    appLogRepository.log(LogLevel.INFO, "Connected to $name")
                    startObdForegroundService()
                    onEvent(ConnectionEvent.Connected)
                    return@collect  // isConnectedFlow wird true → der Messwert-Strom startet neu
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // naechster Versuch
                }
            }
            appLogRepository.log(LogLevel.ERROR, "Connection failed after $RECONNECT_ATTEMPT_COUNT attempts")
            fail(onEvent, R.string.err_reconnect_fail)
        }
    }

    private fun fail(onEvent: (ConnectionEvent) -> Unit, messageRes: Int) {
        onEvent(ConnectionEvent.Failed(UiText.StringResource(messageRes)))
        appContext.stopService(ObdForegroundService.stopIntent(appContext))
    }
}
