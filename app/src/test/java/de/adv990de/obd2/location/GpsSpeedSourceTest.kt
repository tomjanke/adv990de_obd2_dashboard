package de.adv990de.obd2.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
        advanceUntilIdle()
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
}
