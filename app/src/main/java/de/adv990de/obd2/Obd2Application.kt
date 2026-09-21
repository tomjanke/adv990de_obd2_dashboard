package de.adv990de.obd2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class Obd2Application : Application() {

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
    }
}
