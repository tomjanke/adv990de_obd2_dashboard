package de.adv990de.obd2.crash

import android.util.Log
import io.sentry.Sentry
import javax.inject.Inject

interface CrashReporter {
    fun recordException(t: Throwable)
    fun log(tag: String, message: String)
}

class SentryCrashReporter @Inject constructor() : CrashReporter {
    override fun recordException(t: Throwable) {
        Sentry.captureException(t)
        Log.e("CrashReporter", t.message ?: t.javaClass.simpleName, t)
    }

    override fun log(tag: String, message: String) {
        Sentry.addBreadcrumb(message, tag)
        Log.d(tag, message)
    }
}
