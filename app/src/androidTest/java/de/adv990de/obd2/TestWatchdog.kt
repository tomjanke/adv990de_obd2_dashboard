package de.adv990de.obd2

import android.os.Looper
import android.util.Log
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Wachhund fuer haengende Geraete-Tests: ein Test, der laenger als [LIMIT_MS] laeuft, wird mit einem Thread-Dump im Logcat (Tag `TestWatchdog`)
 * belegt und danach unterbrochen — er endet als Fehler, statt den ganzen Lauf zu blockieren (bis 1.49.3 hing ein voller Lauf in
 * `TrackFollowScreenDeviceTest` ueber 50 Minuten, bis ich ihn abbrach). Der Test-Thread ist der des Laeufers (nie der Main-Thread).
 *
 * Angemeldet wird er vom [HiltTestRunner] (Argument `listener`); er gibt jedem Test dieselbe Frist, der laengste Test (`PreviewsRenderDeviceTest`, alle Vorschauen) laeuft rund 145 s, alle anderen unter 30 s.
 */
class TestWatchdog : RunListener() {

    @Volatile private var current: Description? = null
    @Volatile private var startedAt = 0L
    @Volatile private var thread: Thread? = null
    @Volatile private var dumped = false
    @Volatile private var warned = false

    private val watcher = Thread({
        while (true) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val test = current ?: continue
            val running = System.currentTimeMillis() - startedAt
            val runner = thread
            if (running > WARN_MS && runner != null && !warned) {
                warned = true
                Log.e(TAG, "Test laeuft lange seit ${running / 1000} s: ${test.className}.${test.methodName}")
                logViewRoots()
            }
            if (running > LIMIT_MS && runner != null && !dumped) {
                dumped = true
                Log.e(TAG, "Test haengt seit ${running / 1000} s: ${test.className}.${test.methodName}")
                // Was die Warteschlange des Main-Threads gerade enthaelt: wer postet dauernd, ohne dass Espresso je „ruhig“ wird?
                try {
                    Looper.getMainLooper().dump({ line -> Log.e(TAG, "looper: $line") }, "")
                } catch (e: Exception) {
                    Log.e(TAG, "looper dump failed: $e")
                }
                for ((t, frames) in Thread.getAllStackTraces()) {
                    Log.e(TAG, "Thread '${t.name}' state=${t.state}")
                    frames.take(MAX_FRAMES).forEach { Log.e(TAG, "    at $it") }
                }
                runner.interrupt()
            }
        }
    }, "test-watchdog").apply { isDaemon = true }

    /** Alle Fenster dieses Prozesses (ein liegengebliebenes Fenster haelt Compose „beschaeftigt“); `WindowManagerGlobal` ist versteckt, deshalb Reflexion. */
    private fun logViewRoots() {
        try {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val names = global!!.javaClass.getMethod("getViewRootNames").invoke(global) as Array<*>
            Log.e(TAG, "Fenster im Prozess: ${names.size}")
            names.forEach { Log.e(TAG, "  window: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Fensterliste nicht lesbar: $e")
        }
    }

    init {
        watcher.start()
    }

    override fun testStarted(description: Description) {
        thread = Thread.currentThread()
        startedAt = System.currentTimeMillis()
        dumped = false
        warned = false
        current = description
    }

    override fun testFinished(description: Description) {
        current = null
        thread = null
    }

    private companion object {
        const val TAG = "TestWatchdog"
        const val WARN_MS = 200_000L
        const val LIMIT_MS = 300_000L
        const val POLL_MS = 5_000L
        const val MAX_FRAMES = 40
    }
}
