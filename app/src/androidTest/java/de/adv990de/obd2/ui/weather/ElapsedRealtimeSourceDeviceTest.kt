package de.adv990de.obd2.ui.weather

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Die Produktions-Uhr des 30-Minuten-Haltefensters der Aussentemperatur ([elapsedRealtimeSource]) — auf dem Geraet, weil
 * `SystemClock.elapsedRealtimeNanos()` Android braucht. Die JVM-Tests ([TimeSourceFromTest]) pruefen, dass die Zeitquelle nur
 * die gelieferte Uhr liest; hier steht, dass die echte Verdrahtung tatsaechlich `SystemClock.elapsedRealtimeNanos()` ist
 * (zaehlt Tiefschlaf mit) und mit der echten Zeit laeuft. Ein Tiefschlaf selbst laesst sich im Test nicht herbeifuehren.
 */
@RunWith(AndroidJUnit4::class)
class ElapsedRealtimeSourceDeviceTest {

    @Test
    fun theProductionClockAdvancesWithRealTime() {
        val mark = elapsedRealtimeSource.markNow()

        Thread.sleep(120)

        val elapsed = mark.elapsedNow()
        assertTrue("expected at least ~100 ms, was $elapsed", elapsed >= 100.milliseconds)
        assertTrue("expected far less than 10 s, was $elapsed", elapsed < 10.seconds)
    }

    @Test
    fun theProductionClockIsSystemClockElapsedRealtime() {
        val before = SystemClock.elapsedRealtimeNanos()
        val mark = elapsedRealtimeSource.markNow()

        Thread.sleep(60)

        val elapsedNanos = mark.elapsedNow().inWholeNanoseconds
        val after = SystemClock.elapsedRealtimeNanos()
        assertTrue("at least the slept time, was $elapsedNanos ns", elapsedNanos >= 50_000_000L)
        assertTrue("never more than SystemClock saw ($elapsedNanos > ${after - before})", elapsedNanos <= after - before)
    }
}
