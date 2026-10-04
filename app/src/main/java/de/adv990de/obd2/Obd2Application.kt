package de.adv990de.obd2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import de.adv990de.obd2.data.DataRetention
import de.adv990de.obd2.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class Obd2Application : Application() {

    @Inject lateinit var dataRetention: DataRetention

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Kein SDK_INT-Guard noetig: minSdk 26 entspricht VERSION_CODES.O, Notification-
        // Channels sind auf jedem unterstuetzten Geraet vorhanden.
        val channel = NotificationChannel(
            ObdForegroundService.CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        // Aufbewahrung (alte Fahrten/Log-Zeilen) einmal je Start, abseits des Main-Threads. Hilt hat die
        // Felder in super.onCreate() befuellt.
        applicationScope.launch { dataRetention.run() }
    }
}
