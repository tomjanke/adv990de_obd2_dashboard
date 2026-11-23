package de.adv990de.obd2

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Der Wachhund (s. TestWatchdog) bricht einen haengenden Test ab; ein vom Aufrufer gesetzter `listener` bleibt erhalten.
        val args = arguments ?: Bundle()
        val existing = args.getString("listener")
        val watchdog = TestWatchdog::class.java.name
        args.putString("listener", if (existing.isNullOrBlank()) watchdog else "$existing,$watchdog")
        super.onCreate(args)
    }

    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
