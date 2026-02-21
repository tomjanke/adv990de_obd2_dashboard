package de.adv990de.obd2.crash

import android.util.Log
import javax.inject.Inject

interface CrashReporter {
    fun recordException(t: Throwable)
    fun log(tag: String, message: String)
}

class LogCrashReporter @Inject constructor() : CrashReporter {
    override fun recordException(t: Throwable) {
        Log.e("CrashReporter", t.message ?: t.javaClass.simpleName, t)
    }

    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}
