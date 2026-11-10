package de.adv990de.obd2

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Legt den Benachrichtigungskanal des Dienstes an (idempotent — Android ignoriert ein erneutes Anlegen mit
 * gleicher Id). Eine Funktion fuer `Obd2Application` UND die Tests (B36): Der Instrumentation-Test des Dienstes
 * baute den Kanal vorher von Hand nach, weil `Obd2Application` unter `HiltTestApplication` nie laeuft — wer das
 * Anlegen aus der Application entfernte, blieb also gruen. Kein SDK_INT-Guard noetig: minSdk 26 entspricht
 * `VERSION_CODES.O`, Kanaele gibt es auf jedem unterstuetzten Geraet.
 */
fun createObdNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        ObdForegroundService.CHANNEL_ID,
        context.getString(R.string.notif_channel_name),
        NotificationManager.IMPORTANCE_LOW
    )
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/** Which `startForeground` overload [ObdForegroundService.onStartCommand] must call. */
internal enum class ForegroundStartMode { TYPED, LEGACY }

@AndroidEntryPoint
class ObdForegroundService : Service() {

    @Inject lateinit var serviceController: ObdServiceController

    // internal, not private: lets ObdForegroundServiceTest (JVM) launch a probe coroutine on it
    // and assert onDestroy()'s scope.cancel() actually propagates, without a real Service lifecycle.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val stopPendingIntent = PendingIntent.getService(
                    this, 0, stopIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                // Antippen oeffnet die laufende App (B1): vorher tat die Benachrichtigung dabei nichts.
                val openAppPendingIntent = PendingIntent.getActivity(
                    this, 0, openAppIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notif_obd_title))
                    .setContentText(getString(R.string.notif_obd_text))
                    .setSmallIcon(R.drawable.ic_bluetooth)
                    .setOngoing(true)
                    .setContentIntent(openAppPendingIntent)
                    .addAction(0, getString(R.string.notif_obd_disconnect), stopPendingIntent)
                    .build()

                // startForeground() kann ablehnen (SecurityException: dem Typ fehlt eine Berechtigung; ab API 31
                // IllegalStateException, wenn die App im Hintergrund nicht mehr starten darf). Unbehandelt beendete das
                // den Prozess aus onStartCommand heraus (B4). Jetzt: ins App-Log, Dienst beenden — die Verbindung bleibt.
                val includeLocationType = intent.getBooleanExtra(EXTRA_INCLUDE_LOCATION, false)
                val wasRunning = runningIncludesLocation != null
                val refused = catchingForegroundStart {
                    when (resolveForegroundStartMode()) {
                        ForegroundStartMode.TYPED -> startForegroundTyped(notification, resolveForegroundServiceType(includeLocationType))
                        ForegroundStartMode.LEGACY -> startForeground(NOTIF_ID, notification)
                    }
                }
                if (refused == null) {
                    runningIncludesLocation = includeLocationType
                } else {
                    serviceController.onServiceStartFailed(refused)
                    // Ein Dienst, der schon LAEUFT (Typ-Wechsel bei stehender Verbindung, s. ObdServiceController.refreshServiceType),
                    // laeuft mit seinem alten Typ weiter: die abgelehnte Aufwertung darf ihn nicht beenden.
                    if (!wasRunning) {
                        runningIncludesLocation = null
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> scope.launch { serviceController.endSession() }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Der Nutzer hat die App aus der Uebersicht gewischt (B1). Die Aufzeichnung lebt im ViewModel der Activity und
     * endet damit ohnehin — Verbindung und die Benachrichtigung "Fahrzeugdaten werden aufgezeichnet" blieben
     * aber stehen, obwohl nichts mehr aufzeichnet. Wegwischen heisst "fertig": trennen und den Dienst beenden, wie
     * "Beenden" (kein Monitoring im Hintergrund).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scope.launch { serviceController.endSession() }
    }

    override fun onDestroy() {
        runningIncludesLocation = null
        super.onDestroy()
        scope.cancel()
    }

    // NewApi: the 3-arg startForeground(id, notification, type) overload requires API 29+.
    // Lint's version-guard check only recognizes a direct `Build.VERSION.SDK_INT >= ...`
    // comparison at the call site — it doesn't see through resolveForegroundStartMode()'s enum
    // result the way it would a literal `if`, even though the guard is exactly as real (this
    // call is only reachable from the ForegroundStartMode.TYPED branch, which
    // resolveForegroundStartMode() returns exclusively at API 34+). Same limitation as the
    // InlinedApi suppress in BluetoothPermissions.kt/LocationPermissions.kt/PermissionGate.kt.
    @SuppressLint("NewApi")
    private fun startForegroundTyped(notification: Notification, type: Int) {
        startForeground(NOTIF_ID, notification, type)
    }

    companion object {
        const val ACTION_START = "de.adv990de.obd2.action.START"
        const val ACTION_STOP  = "de.adv990de.obd2.action.STOP"
        const val CHANNEL_ID   = "obd2_connection"
        const val EXTRA_INCLUDE_LOCATION = "de.adv990de.obd2.extra.INCLUDE_LOCATION"
        private const val NOTIF_ID = 1

        /**
         * Mit welchem Typ der Dienst gerade laeuft: `true` = `connectedDevice` UND `location`, `false` = nur `connectedDevice`, `null` = er laeuft
         * nicht. Gesetzt, sobald `startForeground()` angenommen wurde, geleert in `onDestroy()` und nach einer abgelehnten ersten Anforderung.
         * Nur dafuer da, dass [ObdServiceController.refreshServiceType] sieht, ob sich der Typ geaendert hat — ein Dienst pro Prozess, daher
         * ein Wert im Companion statt eines Singletons.
         */
        @Volatile
        internal var runningIncludesLocation: Boolean? = null

        fun startIntent(context: Context, includeLocationType: Boolean = false): Intent =
            Intent(context, ObdForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCLUDE_LOCATION, includeLocationType)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ObdForegroundService::class.java).apply { action = ACTION_STOP }

        /**
         * Was ein Antippen der Benachrichtigung startet: die App. `NEW_TASK` ist fuer eine PendingIntent aus einem
         * Dienst Pflicht; `CLEAR_TOP` + `SINGLE_TOP` bringen eine laufende [MainActivity] nach vorn (sie bekommt
         * `onNewIntent`), statt eine zweite Instanz mit leerem ViewModel zu starten.
         */
        internal fun openAppIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        /**
         * The typed 3-arg `startForeground(id, notification, type)` overload only exists from
         * API 34 (UPSIDE_DOWN_CAKE) onward; below that, only the legacy 2-arg overload is
         * available. Pure and sdkInt-parameterized like `resolveForegroundServiceType` below —
         * same convention as `BluetoothPermissions`/`LocationPermissions` — so the branch
         * decision itself is unit-testable without Robolectric (see `ObdForegroundServiceTest`,
         * JVM). Deliberately NOT covered there: that `onStartCommand` actually calls the
         * overload each mode implies — that's a real `Service` framework method, which needs
         * either Robolectric (not a dependency here) or a pre-API-34 device to exercise.
         */
        @SuppressLint("InlinedApi")
        internal fun resolveForegroundStartMode(sdkInt: Int = Build.VERSION.SDK_INT): ForegroundStartMode =
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ForegroundStartMode.TYPED else ForegroundStartMode.LEGACY

        /**
         * `connectedDevice` is always requested — that's the service's core job. `location` is
         * added only when the caller already confirmed a location permission is granted (see
         * ObdServiceController.shouldIncludeLocationServiceType): requesting a type without its
         * permission throws a SecurityException at startForeground(), exactly like the existing
         * connectedDevice/Bluetooth-permission precondition this service already relies on.
         */
        @SuppressLint("InlinedApi")
        internal fun resolveForegroundServiceType(includeLocationType: Boolean): Int =
            if (includeLocationType) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
    }
}
