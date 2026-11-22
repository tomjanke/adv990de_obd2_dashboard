package de.adv990de.obd2.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TileSceneCacheTest {
    private val a = TileKey(5, 1, 1)
    private val b = TileKey(5, 2, 1)
    private val c = TileKey(5, 3, 1)

    @Test
    fun `a requested tile is loaded once and then peeked`() = runTest {
        val loads = ArrayList<TileKey>()
        var notified = 0
        val cache = TileSceneCache<String>(10, backgroundScope, onLoaded = { notified++ }) { loads.add(it); "tile $it" }
        assertNull(cache.peek(a))
        assertFalse(cache.isSettled(a))
        cache.request(listOf(a))
        cache.request(listOf(a))
        runCurrent()
        assertEquals(listOf(a), loads)
        assertEquals("tile $a", cache.peek(a))
        assertTrue(cache.isSettled(a))
        assertEquals(1, notified)
    }

    @Test
    fun `a tile already on its way is not ordered again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val cache = TileSceneCache<String>(10, backgroundScope, onLoaded = {}) { loads++; gate.await(); "x" }
        cache.request(listOf(a))
        runCurrent()
        cache.request(listOf(a))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, loads)
    }

    @Test
    fun `the same key twice in one order is loaded once`() = runTest {
        var loads = 0
        val cache = TileSceneCache<String>(10, backgroundScope, onLoaded = {}) { loads++; "x" }
        cache.request(listOf(a, a))
        runCurrent()
        assertEquals(1, loads)
    }

    @Test
    fun `a tile the file does not have is remembered as missing and not read again`() = runTest {
        var loads = 0
        val cache = TileSceneCache<String>(10, backgroundScope, onLoaded = {}) { loads++; null }
        cache.request(listOf(a))
        runCurrent()
        cache.request(listOf(a))
        runCurrent()
        assertEquals(1, loads)
        assertNull(cache.peek(a))
        assertTrue(cache.isSettled(a))
    }

    @Test
    fun `a loader that throws leaves an empty place and the cache keeps working`() = runTest {
        val cache = TileSceneCache<String>(10, backgroundScope, onLoaded = {}) { if (it == a) error("kaputt") else "ok" }
        cache.request(listOf(a, b))
        runCurrent()
        assertNull(cache.peek(a))
        assertTrue(cache.isSettled(a))
        assertEquals("ok", cache.peek(b))
    }

    @Test
    fun `the least recently used tile is dropped over the capacity`() = runTest {
        val cache = TileSceneCache<String>(2, backgroundScope, onLoaded = {}) { "t$it" }
        cache.request(listOf(a))
        runCurrent()
        cache.request(listOf(b))
        runCurrent()
        assertEquals("t$a", cache.peek(a)) // a ist jetzt der juengste Zugriff
        cache.request(listOf(c))
        runCurrent()
        assertEquals("t$a", cache.peek(a))
        assertNull(cache.peek(b))
        assertFalse(cache.isSettled(b))
        assertEquals("t$c", cache.peek(c))
    }

    @Test
    fun `at most the given number of tiles load at the same time`() = runTest {
        var running = 0
        var peak = 0
        val gate = CompletableDeferred<Unit>()
        val cache = TileSceneCache<String>(10, backgroundScope, parallel = 2, onLoaded = {}) {
            running++
            peak = maxOf(peak, running)
            gate.await()
            running--
            "x"
        }
        cache.request(listOf(a, b, c))
        runCurrent()
        assertEquals(2, peak)
        gate.complete(Unit)
        runCurrent()
        assertEquals("x", cache.peek(c))
        assertEquals(2, peak)
    }

    @Test
    fun `a cancelled load is not remembered as missing`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val cache = TileSceneCache<String>(10, scope.backgroundScope, onLoaded = {}) { loads++; gate.await(); "x" }
        cache.request(listOf(a))
        scope.runCurrent()
        assertEquals(1, loads)
        assertFalse(cache.isSettled(a))
        scope.backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.runCurrent()
        assertFalse(cache.isSettled(a))
    }
}
