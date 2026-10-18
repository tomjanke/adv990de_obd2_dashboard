package de.adv990de.obd2.terrain

import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.location.LocationSpeedProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerrainSourceTest {

    private class FakeLocation(
        var permitted: Boolean = true,
        private val registrationFails: Boolean = false,
    ) : LocationSpeedProvider {
        var fixIntervalMs: Long? = null
        var onFix: ((GpsFix?) -> Unit)? = null
        var stopped = false

        override fun hasPermission() = permitted
        override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? = null
        override fun startFixUpdates(minIntervalMs: Long, onFix: (GpsFix?) -> Unit): (() -> Unit)? {
            fixIntervalMs = minIntervalMs
            if (registrationFails) return null
            this.onFix = onFix
            return { stopped = true }
        }
    }

    private class FakePressure(private val available: Boolean = true) : PressureProvider {
        var onHpa: ((Float) -> Unit)? = null
        var stopped = false
        var started = false

        override fun startUpdates(onHpa: (Float) -> Unit): (() -> Unit)? {
            started = true
            if (!available) return null
            this.onHpa = onHpa
            return { stopped = true }
        }
    }

    private fun TestScope.source(location: FakeLocation, pressure: FakePressure) =
        TerrainSource(location, pressure).also { it.nowMillis = { testScheduler.currentTime } }

    private fun fix(t: Long) = GpsFix(t, 50f, 100f, 5f, 90f, 10f)

    @Test
    fun `each fix becomes one input carrying the fix and the mean pressure`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()

        pressure.onHpa!!(1000f); pressure.onHpa!!(1002f)
        location.onFix!!(fix(1_000))
        runCurrent()

        assertEquals(listOf(TerrainInput(fix(1_000), 1001f)), received)
        assertEquals(1_000L, location.fixIntervalMs)
        job.cancel()
    }

    @Test
    fun `the pressure is averaged anew for every fix`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()

        pressure.onHpa!!(1000f); location.onFix!!(fix(1_000)); runCurrent()
        advanceTimeBy(1_000)
        pressure.onHpa!!(990f); location.onFix!!(fix(2_000)); runCurrent()

        assertEquals(listOf(1000f, 990f), received.map { it.pressureHpa })
        job.cancel()
    }

    @Test
    fun `without a barometer the pressure is null and the fixes still arrive`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure(available = false)
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()

        location.onFix!!(fix(1_000)); runCurrent()

        assertEquals(listOf(TerrainInput(fix(1_000), null)), received)
        job.cancel()
    }

    @Test
    fun `a provider that was switched off counts as a missing fix`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()

        location.onFix!!(null); runCurrent()

        assertEquals(listOf(TerrainInput(null, null)), received)
        job.cancel()
    }

    @Test
    fun `three seconds without a fix the input goes on every second without one`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()
        pressure.onHpa!!(1000f)
        location.onFix!!(fix(0)); runCurrent()
        assertEquals(1, received.size)

        advanceTimeBy(2_999); runCurrent()
        assertEquals("noch nicht veraltet", 1, received.size)

        advanceTimeBy(1 + 2_000); runCurrent()   // 3 s, 4 s, 5 s
        assertEquals(4, received.size)
        assertTrue(received.drop(1).all { it.fix == null })
        job.cancel()
    }

    @Test
    fun `a new fix ends the stream of inputs without one`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()
        location.onFix!!(fix(0)); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        val withoutFix = received.size

        location.onFix!!(fix(4_000)); runCurrent()
        advanceTimeBy(2_000); runCurrent()   // noch nicht wieder veraltet

        assertEquals(withoutFix + 1, received.size)
        assertEquals(fix(4_000), received.last().fix)
        job.cancel()
    }

    @Test
    fun `cancelling the collector unregisters both sources`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        val job = launch { source(location, pressure).inputs.toList(mutableListOf()) }
        runCurrent()
        location.onFix!!(fix(0)); runCurrent()
        assertFalse(location.stopped); assertFalse(pressure.stopped)

        job.cancel(); runCurrent()

        assertTrue(location.stopped)
        assertTrue(pressure.stopped)
    }

    @Test
    fun `nothing is registered while nobody collects`() = runTest {
        val location = FakeLocation(); val pressure = FakePressure()
        source(location, pressure)
        runCurrent()

        assertNull(location.fixIntervalMs)
        assertFalse(pressure.started)
    }

    @Test
    fun `without a permission nothing is registered until it is granted`() = runTest {
        val location = FakeLocation(permitted = false); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        advanceTimeBy(GpsSpeedSource.PERMISSION_RECHECK_MS * 3); runCurrent()
        assertNull(location.fixIntervalMs)
        assertFalse(pressure.started)

        location.permitted = true
        advanceTimeBy(GpsSpeedSource.PERMISSION_RECHECK_MS + 1); runCurrent()
        location.onFix!!(fix(0)); runCurrent()

        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `a failed fix registration yields nothing and still releases the barometer`() = runTest {
        val location = FakeLocation(registrationFails = true); val pressure = FakePressure()
        val received = mutableListOf<TerrainInput>()
        val job = launch { source(location, pressure).inputs.toList(received) }
        runCurrent()

        assertTrue(received.isEmpty())
        job.cancel(); runCurrent()
        assertTrue(pressure.stopped)
    }
}
