package de.adv990de.obd2

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.adv990de.obd2.crash.CrashReporter
import javax.inject.Inject

@HiltAndroidApp
class Obd2Application : Application() {

    @Inject lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashReporter.recordException(throwable)
            systemHandler?.uncaughtException(thread, throwable)
        }
    }
}
