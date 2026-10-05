package de.adv990de.obd2

import android.app.Application
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
        createObdNotificationChannel(this)

        // Aufbewahrung (alte Fahrten/Log-Zeilen) einmal je Start, abseits des Main-Threads. Hilt hat die
        // Felder in super.onCreate() befuellt.
        applicationScope.launch { dataRetention.run() }
    }
}
