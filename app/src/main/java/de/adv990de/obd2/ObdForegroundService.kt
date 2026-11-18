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
import de.adv990de.obd2.track.TrackRecorder
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

    @Inject lateinit var trackRecorder: TrackRecorder

    // internal, not private: lets ObdForegroundServiceTest (JVM) launch a probe coroutine on it
    // and assert onDestroy()'s scope.cancel() actually propagates, without a real Service lifecycle.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val demand = ForegroundDemand(
                    obd = intent.getBooleanExtra(EXTRA_OBD, true),
                    track = intent.getBooleanExtra(EXTRA_TRACK, false),
                )
                val kind = notificationKind(demand)
                if (kind == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val notification = buildNotification(kind)

                // startForeground() kann ablehnen (SecurityException: dem Typ fehlt eine Berechtigung; ab API 31
                // IllegalStateException, wenn die App im Hintergrund nicht mehr starten darf). Unbehandelt beendete das
                // den Prozess aus onStartCommand heraus (B4). Jetzt: ins App-Log, Dienst beenden — die Verbindung bleibt.
                val type = foregroundServiceType(demand, obdWantsLocation = intent.getBooleanExtra(EXTRA_INCLUDE_LOCATION, false))
                val wasRunning = runningIncludesLocation != null
                val refused = catchingForegroundStart {
                    when (resolveForegroundStartMode()) {
                        ForegroundStartMode.TYPED -> startForegroundTyped(notification, type)
                        ForegroundStartMode.LEGACY -> startForeground(NOTIF_ID, notification)
                    }
                }
                if (refused == null) {
                    runningIncludesLocation = hasLocationType(type)
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
            // „Track beenden“: der Aufzeichner beendet die Spur und nimmt seinen Bedarf selbst zurueck (ObdServiceController.setTrack) — nie am Controller vorbei.
            ACTION_STOP_TRACK -> scope.launch { trackRecorder.stop() }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Die Benachrichtigung zum Bedarf: nur OBD „Fahrzeugdaten werden aufgezeichnet“ mit „Trennen“, nur Track „Track wird aufgezeichnet“ mit „Track beenden“, beides ein gemeinsamer Text mit
     * beiden Aktionen. Das Symbol ist das Bluetooth-Zeichen, sobald der Adapter dabei ist, sonst das Ortungszeichen. Antippen oeffnet die App (B1).
     */
    private fun buildNotification(kind: NotificationKind): Notification {
        fun action(intent: Intent) = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openApp = PendingIntent.getActivity(
            this, 0, openAppIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setOngoing(true).setContentIntent(openApp)
        when (kind) {
            NotificationKind.OBD -> builder
                .setContentTitle(getString(R.string.notif_obd_title)).setContentText(getString(R.string.notif_obd_text))
                .setSmallIcon(R.drawable.ic_bluetooth)
                .addAction(0, getString(R.string.notif_obd_disconnect), action(stopIntent(this)))
            NotificationKind.TRACK -> builder
                .setContentTitle(getString(R.string.notif_track_title)).setContentText(getString(R.string.notif_track_text))
                .setSmallIcon(R.drawable.ic_notification_track)
                .addAction(0, getString(R.string.notif_track_stop), action(stopTrackIntent(this)))
            NotificationKind.BOTH -> builder
                .setContentTitle(getString(R.string.notif_both_title)).setContentText(getString(R.string.notif_both_text))
                .setSmallIcon(R.drawable.ic_bluetooth)
                .addAction(0, getString(R.string.notif_obd_disconnect), action(stopIntent(this)))
                .addAction(0, getString(R.string.notif_track_stop), action(stopTrackIntent(this)))
        }
        return builder.build()
    }

    /**
     * Der Nutzer hat die App aus der Uebersicht gewischt (B1). Die OBD-Sitzung lebt im ViewModel der Activity und
     * endet damit ohnehin — Verbindung und die Benachrichtigung "Fahrzeugdaten werden aufgezeichnet" blieben
     * aber stehen, obwohl nichts mehr aufzeichnet. Wegwischen heisst "fertig": trennen, wie "Beenden" (kein Monitoring im Hintergrund).
     * **Eine laufende Streckenaufzeichnung laeuft weiter** (Entscheidung des Nutzers 2026-11-16): [ObdServiceController.endSession] nimmt nur den OBD-Bedarf zurueck,
     * der Dienst bleibt, solange die Aufzeichnung ihn braucht; sie endet mit „Track beenden“ in der Benachrichtigung oder in der App.
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
        const val ACTION_STOP_TRACK = "de.adv990de.obd2.action.STOP_TRACK"
        const val CHANNEL_ID   = "obd2_connection"
        const val EXTRA_INCLUDE_LOCATION = "de.adv990de.obd2.extra.INCLUDE_LOCATION"
        const val EXTRA_OBD = "de.adv990de.obd2.extra.OBD"
        const val EXTRA_TRACK = "de.adv990de.obd2.extra.TRACK"
        private const val NOTIF_ID = 1

        /**
         * Ob der laufende Dienst den Typ `location` hat (`true`, auch bei nur `location` fuer den Track), nicht (`false`) oder gar nicht laeuft (`null`). Gesetzt, sobald `startForeground()` angenommen wurde, geleert in `onDestroy()` und nach einer abgelehnten ersten Anforderung.
         * Nur dafuer da, dass [ObdServiceController.refreshServiceType] sieht, ob sich der Typ geaendert hat — ein Dienst pro Prozess, daher
         * ein Wert im Companion statt eines Singletons.
         */
        @Volatile
        internal var runningIncludesLocation: Boolean? = null

        /**
         * Fordert den Dienst fuer [demand] an. [includeLocationType] gilt nur fuer die OBD-Sitzung (GPS-Geschwindigkeit an und ein Standort erteilt, [shouldIncludeLocationServiceType]);
         * fuer den Track ist der Typ `location` immer dabei ([foregroundServiceType]).
         */
        fun startIntent(context: Context, demand: ForegroundDemand = ForegroundDemand(obd = true), includeLocationType: Boolean = false): Intent =
            Intent(context, ObdForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OBD, demand.obd)
                putExtra(EXTRA_TRACK, demand.track)
                putExtra(EXTRA_INCLUDE_LOCATION, includeLocationType)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ObdForegroundService::class.java).apply { action = ACTION_STOP }

        /** „Track beenden“ aus der Benachrichtigung. */
        fun stopTrackIntent(context: Context): Intent =
            Intent(context, ObdForegroundService::class.java).apply { action = ACTION_STOP_TRACK }

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
         * available. Pure and sdkInt-parameterized like `foregroundServiceType` (ForegroundDemand.kt) —
         * same convention as `BluetoothPermissions`/`LocationPermissions` — so the branch
         * decision itself is unit-testable without Robolectric (see `ObdForegroundServiceTest`,
         * JVM). Deliberately NOT covered there: that `onStartCommand` actually calls the
         * overload each mode implies — that's a real `Service` framework method, which needs
         * either Robolectric (not a dependency here) or a pre-API-34 device to exercise.
         */
        @SuppressLint("InlinedApi")
        internal fun resolveForegroundStartMode(sdkInt: Int = Build.VERSION.SDK_INT): ForegroundStartMode =
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ForegroundStartMode.TYPED else ForegroundStartMode.LEGACY
    }
}
