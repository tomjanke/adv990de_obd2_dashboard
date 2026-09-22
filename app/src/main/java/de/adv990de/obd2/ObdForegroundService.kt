package de.adv990de.obd2

import android.annotation.SuppressLint
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

@AndroidEntryPoint
class ObdForegroundService : Service() {

    @Inject lateinit var obdRepository: ObdRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val includeLocationType = intent?.getBooleanExtra(EXTRA_INCLUDE_LOCATION, false) ?: false
                    startForeground(
                        NOTIF_ID, notification,
                        resolveForegroundServiceType(includeLocationType)
                    )
                } else {
                    startForeground(NOTIF_ID, notification)
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
