package de.adv990de.obd2

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.location.LocationPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Whether [ObdForegroundService] should request the `location` foreground-service type on this (re)connect.
 * Both conditions are required: the user opted in, and Android will actually deliver background location
 * callbacks only when the permission is granted.
 */
internal fun shouldIncludeLocationServiceType(useGpsSpeed: Boolean, hasBackgroundLocation: Boolean): Boolean =
    useGpsSpeed && hasBackgroundLocation

/**
 * Fuehrt [block] aus, das den Vordergrunddienst anfordert, und liefert die Ausnahme zurueck, wenn Android den
 * Start ablehnt — sonst `null`. Zwei Ablehnungen sind bekannt und kein Programmfehler:
 *  - [IllegalStateException]: ab API 31 darf eine App im Hintergrund keinen Vordergrunddienst mehr starten
 *    (`ForegroundServiceStartNotAllowedException`, eine Unterklasse; die Oberklasse faengt es auch auf aelteren
 *    APIs, ohne die API-31-Klasse zu referenzieren);
 *  - [SecurityException]: dem Dienst-Typ fehlt eine Berechtigung (Bluetooth, Standort).
 * Alles andere (NPE, IllegalArgument ...) laeuft durch: das waere ein Fehler im Code, kein abgelehnter Start.
 */
internal inline fun catchingForegroundStart(block: () -> Unit): Exception? =
    try {
        block()
        null
    } catch (e: IllegalStateException) {
        e
    } catch (e: SecurityException) {
        e
    }

/**
 * Das eine Verhalten rund um den Vordergrunddienst (B1/B4): starten, stoppen, die Sitzung des Nutzers beenden.
 *
 * Vorher standen Start (im `ConnectionCoordinator` UND im `SettingsViewModel`), Stopp (dreimal) und die Trennung
 * "auf Wunsch des Nutzers" nebeneinander — und keine Stelle fing ab, dass `startForegroundService()` ab API 31 im
 * Hintergrund eine Ausnahme wirft. Die landete im `catch` des Verbindungsaufbaus: eine STEHENDE Verbindung wurde als
 * "Reconnect-Versuch fehlgeschlagen" geloggt und `Connected` blieb aus.
 *
 * Ohne eigenen Scope (`@Singleton` nur wo unverzichtbar): der `@ApplicationScope` ueberlebt Activity und ViewModels,
 * was [endSessionInBackground] braucht — eine zerstoerte Activity kann keine eigene Coroutine mehr halten.
 */
class ObdServiceController @Inject constructor(
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val appContext: Context,
    private val appLogRepository: AppLogRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Fordert den Vordergrunddienst an. Wirft nicht, wenn Android das ablehnt: Die Verbindung steht dann trotzdem, und
     * die App laeuft weiter — nur ohne Schutz davor, im Hintergrund beendet zu werden. Der Grund steht im App-Log.
     *
     * GPS-Geschwindigkeit im Hintergrund braucht ACCESS_BACKGROUND_LOCATION *und* den Typ `location` beim Start (s.
     * [ObdForegroundService.resolveForegroundServiceType]); ohne die Berechtigung startet der Dienst wie bisher nur
     * mit `connectedDevice`.
     */
    suspend fun start() {
        val includeLocation = shouldIncludeLocationServiceType(
            useGpsSpeed = appPreferences.useGpsSpeed.first(),
            hasBackgroundLocation = LocationPermissions.hasBackgroundLocation(appContext),
        )
        val refused = catchingForegroundStart {
            appContext.startForegroundService(ObdForegroundService.startIntent(appContext, includeLocation))
        }
        if (refused != null) appLogRepository.log(LogLevel.WARN, startFailureMessage(refused))
    }

    /** Beendet den Dienst (und damit die Benachrichtigung). Trennt die Verbindung NICHT. */
    fun stop() {
        appContext.stopService(ObdForegroundService.stopIntent(appContext))
    }

    /**
     * Beendet die Sitzung auf Wunsch des Nutzers: trennen (als Nutzer-Trennung, damit keine Wiederverbindung
     * dagegenarbeitet) und den Dienst stoppen. Die Trennung laeuft zu Ende, auch wenn der Aufrufer abgebrochen wird
     * (z. B. weil die Coroutine des Dienstes mit `onDestroy` stirbt) — sonst bliebe der Adapter belegt. Der Dienst
     * wird auch gestoppt, wenn die Trennung scheitert.
     */
    suspend fun endSession() {
        try {
            withContext(NonCancellable) { obdRepository.disconnectByUser() }
        } finally {
            stop()
        }
    }

    /**
     * Wie [endSession], fuer Aufrufer ohne eigene Coroutine (`Activity.onDestroy`). Wirft nie: ein Fehler steht im
     * App-Log, statt im ungefangenen Scope den Prozess zu beenden.
     */
    fun endSessionInBackground() {
        scope.launch {
            try {
                endSession()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogRepository.log(LogLevel.WARN, "Ending the session failed: ${e.javaClass.simpleName}: ${e.message ?: "-"}")
            }
        }
    }

    /** Der Dienst meldet, dass sein eigenes `startForeground()` abgelehnt wurde (er beendet sich danach selbst). */
    fun onServiceStartFailed(e: Exception) {
        scope.launch { appLogRepository.log(LogLevel.WARN, startFailureMessage(e)) }
    }

    private fun startFailureMessage(e: Exception): String =
        "Foreground service could not be started: ${e.javaClass.simpleName}: ${e.message ?: "-"} " +
            "(the connection stays up; without the service Android may stop the app in the background)"
}
