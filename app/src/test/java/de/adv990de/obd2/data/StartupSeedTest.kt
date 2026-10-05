package de.adv990de.obd2.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QS C / UID-12: Theme und Kachel-Belegung kommen asynchron aus dem DataStore. Die Einstellung wird erst waehrend der
 * ersten Komposition abonniert; das erste Bild zeigte deshalb kurz das Standard-Theme und leere/Standard-Kacheln, dann
 * sprang es um (Kaltstart am Pixel gemessen: der erste Zugriff dauert rund 3 ms, also etwa ein Bild). [seedBlocking]
 * liest den Wert einmal beim Start synchron, mit Obergrenze — als Startwert, nicht als Quelle der Wahrheit.
 */
class StartupSeedTest {

    @Test
    fun `the first value of the flow is the seed`() {
        assertEquals("amber", seedBlocking("system", source = flowOf("amber", "blue")))
    }

    @Test
    fun `a state flow delivers its current value`() {
        assertEquals(7, seedBlocking(0, source = MutableStateFlow(7)))
    }

    // Mit Test-Timeout: Ein Fehler (oder eine Mutation), die die Obergrenze ignoriert, soll den Test ROT machen, nicht haengen.
    @Test(timeout = 5_000)
    fun `without a value in time the default is used - a stalled disk must never block the start`() {
        val never: Flow<String> = flow { awaitCancellation() }

        val started = System.nanoTime()
        val seed = seedBlocking("system", timeoutMs = 60, source = never)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("system", seed)
        assertTrue("wartet hoechstens ungefaehr die Obergrenze, war $tookMs ms", tookMs < 1_000)
    }

    @Test
    fun `an empty flow yields the default`() {
        assertEquals("system", seedBlocking("system", source = emptyFlow()))
    }

    @Test
    fun `a failing flow yields the default - the seed is a hint, never a crash at startup`() {
        val failing: Flow<String> = flow { throw IllegalStateException("datastore broken") }
        assertEquals("system", seedBlocking("system", source = failing))
    }

    @Test
    fun `only the first element is taken - the source is not collected on`() {
        var emitted = 0
        val source = flow { repeat(5) { emitted++; emit("v$it") } }

        assertEquals("v0", seedBlocking("d", source = source))
        assertEquals("nach dem ersten Wert wird nichts mehr abgeholt", 1, emitted)
    }
}
