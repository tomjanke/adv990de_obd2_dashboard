package de.adv990de.obd2

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.adv990de.obd2.crash.CrashReportingController
import de.adv990de.obd2.data.DataRetention
import de.adv990de.obd2.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class Obd2Application : Application() {

    @Inject lateinit var dataRetention: DataRetention

    @Inject lateinit var crashReporting: CrashReportingController

    @Inject lateinit var serviceController: ObdServiceController

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        createObdNotificationChannel(this)

        // Aufbewahrung (alte Fahrten/Log-Zeilen) einmal je Start, abseits des Main-Threads. Hilt hat die
        // Felder in super.onCreate() befuellt.
        applicationScope.launch { dataRetention.run() }

        // Sentry startet nicht von selbst (auto-init=false), sondern erst, wenn die Einstellung des Nutzers gelesen ist,
        // und folgt dem Schalter danach (QS B33). Ohne DSN im Build passiert hier nichts.
        applicationScope.launch { crashReporting.run() }

        // Der Typ des Vordergrunddienstes folgt der Einstellung "GPS-Geschwindigkeit", auch bei stehender Verbindung.
        applicationScope.launch { serviceController.keepServiceTypeCurrent() }
    }
}
