package de.adv990de.obd2.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer die GPS-Quelle.
 *
 * Bis 1.12.0 war das die einzige Stelle des GPS-Pfads ohne Test: Die Klasse haengte direkt am
 * `LocationManager`, und der existiert im JVM-Test nicht. Seit der Naht [LocationSpeedProvider]
 * laesst sich pruefen, was zu pruefen war — Berechtigung, gescheiterte Registrierung, Umrechnung,
 * unplausible Werte und das Abmelden beim Ende der Sammlung.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GpsSpeedSourceTest {

    /**
     * Ein Provider, der nichts kann ausser dem, was der Test ihm sagt. Er haelt die zuletzt
     * registrierte Rueckmeldung fest, damit der Test Fixe von Hand einspielen kann.
     */
    private class FakeProvider(
        private val permitted: Boolean = true,
        private val registrationFails: Boolean = false,
    ) : LocationSpeedProvider {

        var startUpdatesCalled = false
            private set
        var stopped = false
            private set
        var onSpeed: ((Float?) -> Unit)? = null
            private set

        override fun hasPermission(): Boolean = permitted

        override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? {
            startUpdatesCalled = true
            if (registrationFails) return null
            this.onSpeed = onSpeed
            return { stopped = true }
        }
    }

    // --- Test 1 ---

    @Test
    fun `without permission the source reports nothing and registers nothing`() = runTest {
        val provider = FakeProvider(permitted = false)
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf<Float?>(null), received)
        assertFalse("Ohne Berechtigung darf nichts registriert werden", provider.startUpdatesCalled)
    }

    // --- Test 2 ---

    @Test
    fun `a failed registration reports nothing instead of hanging`() = runTest {
        val provider = FakeProvider(registrationFails = true)
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        job.cancel()

        assertTrue(provider.startUpdatesCalled)
        assertEquals(listOf<Float?>(null), received)
    }

    // --- Test 3 ---

    @Test
    fun `a fix is converted from metres per second to km per hour`() = runTest {
        val provider = FakeProvider()
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        assertNotNull("Der Provider wurde nicht registriert", provider.onSpeed)
        provider.onSpeed!!(10f)   // 10 m/s = 36 km/h
        // runCurrent() statt advanceUntilIdle(): Ein echter Fix startet jetzt einen
        // Staleness-Timer (Test 7/8) — advanceUntilIdle() wuerde die virtuelle Zeit bis zu
        // dessen Ablauf vorspulen und den frischen Fix hier faelschlich wieder auf null setzen.
        runCurrent()
        job.cancel()

        assertEquals(36f, received.last()!!, 0.001f)
    }

    // --- Test 4 ---

    @Test
    fun `a fix without a speed stays null`() = runTest {
        val provider = FakeProvider()
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        provider.onSpeed!!(null)
        advanceUntilIdle()
        job.cancel()

        assertNull(received.last())
    }

    // --- Test 5 ---

    @Test
    fun `implausible values become null rather than travelling upwards`() = runTest {
        val provider = FakeProvider()
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        provider.onSpeed!!(-5f)
        provider.onSpeed!!(Float.NaN)
        provider.onSpeed!!(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            "Kein unplausibler Wert darf durchkommen: $received",
            received.all { it == null }
        )
    }

    // --- Test 6 ---

    @Test
    fun `ending the collection unregisters the receiver`() = runTest {
        val provider = FakeProvider()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(mutableListOf()) }
        advanceUntilIdle()
        assertFalse("Zu frueh abgemeldet", provider.stopped)

        job.cancel()
        advanceUntilIdle()

        assertTrue("Der Empfaenger blieb registriert — das haette das GPS weiterlaufen lassen", provider.stopped)
    }

    // --- Test 7 ---

    @Test
    fun `a fix becomes stale and reverts to null after the timeout`() = runTest {
        val provider = FakeProvider()
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        provider.onSpeed!!(10f)   // 36 km/h
        runCurrent()
        assertEquals(36f, received.last()!!, 0.001f)

        advanceTimeBy(GpsSpeedSource.STALE_AFTER_MS + 1)   // just past the timeout, no new fix
        advanceUntilIdle()
        job.cancel()

        assertNull(
            "Ein Fix, der laenger als das Timeout her ist, muss als unbekannt gelten, nicht als" +
                " zuletzt bekannte Geschwindigkeit weiterlaufen",
            received.last()
        )
    }

    // --- Test 8 ---

    @Test
    fun `a fresh fix resets the staleness timer instead of the old one firing`() = runTest {
        val provider = FakeProvider()
        val received = mutableListOf<Float?>()

        val job = launch { GpsSpeedSource(provider).speedKmh.toList(received) }
        advanceUntilIdle()
        provider.onSpeed!!(10f)   // 36 km/h
        runCurrent()
        advanceTimeBy(GpsSpeedSource.STALE_AFTER_MS - 500L)   // still within the first timeout
        provider.onSpeed!!(12f)  // 43.2 km/h — a fresh fix arrives in time
        runCurrent()
        advanceTimeBy(GpsSpeedSource.STALE_AFTER_MS - 500L)   // within the SECOND timeout only
        // runCurrent(), nicht advanceUntilIdle(): Der zweite Staleness-Timer ist zu diesem
        // Zeitpunkt bewusst noch nicht abgelaufen — advanceUntilIdle() wuerde die virtuelle Zeit
        // bis zu seinem Ablauf vorspulen und genau das Verhalten verdecken, das dieser Test
        // pruefen soll.
        runCurrent()
        job.cancel()

        assertEquals(
            "Der zweite Fix haette den Timer zuruecksetzen muessen, statt nur den ersten " +
                "Timer unveraendert weiterlaufen zu lassen",
            43.2f, received.last()!!, 0.001f
        )
    }
}
