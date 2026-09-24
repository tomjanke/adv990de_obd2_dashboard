package de.adv990de.obd2

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import de.adv990de.obd2.data.ObdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which `startForeground` overload [ObdForegroundService.onStartCommand] must call. */
internal enum class ForegroundStartMode { TYPED, LEGACY }

@AndroidEntryPoint
class ObdForegroundService : Service() {

    @Inject lateinit var obdRepository: ObdRepository

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
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notif_obd_title))
                    .setContentText(getString(R.string.notif_obd_text))
                    .setSmallIcon(R.drawable.ic_bluetooth)
                    .setOngoing(true)
                    .addAction(0, getString(R.string.notif_obd_disconnect), stopPendingIntent)
                    .build()

                when (resolveForegroundStartMode()) {
                    ForegroundStartMode.TYPED -> {
                        val includeLocationType = intent?.getBooleanExtra(EXTRA_INCLUDE_LOCATION, false) ?: false
                        startForegroundTyped(notification, resolveForegroundServiceType(includeLocationType))
                    }
                    ForegroundStartMode.LEGACY -> startForeground(NOTIF_ID, notification)
                }
            }
            ACTION_STOP -> scope.launch {
                withContext(NonCancellable) { obdRepository.disconnect() }
                stopSelf()
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

        fun startIntent(context: Context, includeLocationType: Boolean = false): Intent =
            Intent(context, ObdForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCLUDE_LOCATION, includeLocationType)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ObdForegroundService::class.java).apply { action = ACTION_STOP }

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
         * added only when the caller already confirmed ACCESS_BACKGROUND_LOCATION is granted (see
         * ConnectionCoordinator's startObdForegroundService): requesting a type without its
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
