package de.adv990de.obd2.data

import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.GpsOdometer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Die Huelle um den GPS-Zaehler des Kilometerstands ([LogbookOdometer]): liest den gespeicherten Zaehler und den hoechsten Stand des Logbuchs, zaehlt die Kilometer der Sitzung, schreibt
 * gedrosselt und am Sitzungsende und verliert beim Neuanker nichts. Die Entscheidungen selbst stehen in `OdometerCounter` (eigener Test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogbookOdometerTest {

    private class Fixture(
        val maxKm: MutableStateFlow<Int?>,
        val prefs: AppPreferences,
        val odometer: LogbookOdometer,
    )

    private fun TestScope.fixture(
        stored: GpsOdometer? = null,
        maxKm: Int? = 40_000,
        onWrite: (Int, Float) -> Unit = { _, _ -> },
    ): Fixture {
        val max = MutableStateFlow(maxKm)
        val prefs = fakeAppPreferences(gpsOdometer = stored)
        coEvery { prefs.setGpsOdometer(any(), any()) } coAnswers { onWrite(firstArg(), secondArg()) }
        val log = mockk<VehicleLogRepository> { every { maxOdometerKm } returns max }
        val odometer = LogbookOdometer(prefs, log, backgroundScope)
        runCurrent()
        return Fixture(max, prefs, odometer)
    }

    @Test
    fun `the stored counter of the same reading is the start`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(stored = GpsOdometer(40_000, 12.5f))

        assertEquals(GpsOdometer(40_000, 12.5f), f.odometer.counter.first())
    }

    @Test
    fun `kilometres are counted on top of it, and the estimate follows`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(stored = GpsOdometer(40_000, 12.5f))

        f.odometer.addDistance(0.25f)
        f.odometer.addDistance(0.25f)

        assertEquals(GpsOdometer(40_000, 13f), f.odometer.counter.first())
    }

    @Test
    fun `a new highest reading starts the counter over and writes the new anchor at once`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 12.5f)) { a, k -> writes += a to k }

        f.maxKm.value = 40_300

        assertEquals(GpsOdometer(40_300, 0f), f.odometer.counter.first())
        assertEquals(listOf(40_300 to 0f), writes)
    }

    @Test
    fun `it writes once a step of half a kilometre is reached, not on every kilometre report`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        f.odometer.addDistance(0.25f)
        assertEquals("noch unter dem Schritt", emptyList<Pair<Int, Float>>(), writes)
        f.odometer.addDistance(0.25f)

        assertEquals(1, writes.size)
        assertEquals(40_000, writes.single().first)
        assertEquals(10.5f, writes.single().second, 0f)
    }

    @Test
    fun `flush writes what is still unwritten, and nothing twice`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }
        f.odometer.addDistance(0.2f)

        f.odometer.flush()
        f.odometer.flush()

        assertEquals(listOf(40_000 to 10.2f), writes)
    }

    @Test
    fun `flush without a change writes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        f.odometer.flush()

        assertEquals(emptyList<Pair<Int, Float>>(), writes)
    }

    @Test
    fun `without any entry nothing is counted and nothing is written`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = null, maxKm = null) { a, k -> writes += a to k }

        f.odometer.addDistance(5f)
        f.odometer.flush()

        assertNull(f.odometer.counter.first())
        assertEquals(emptyList<Pair<Int, Float>>(), writes)
    }

    @Test
    fun `kilometres before the stored value is read are not lost and nothing is written before`() = runTest {
        val writes = mutableListOf<Pair<Int, Float>>()
        val gate = kotlinx.coroutines.CompletableDeferred<GpsOdometer?>()
        val prefs = fakeAppPreferences()
        every { prefs.gpsOdometer } returns flow { emit(gate.await()) }
        coEvery { prefs.setGpsOdometer(any(), any()) } coAnswers { writes += firstArg<Int>() to secondArg<Float>() }
        val log = mockk<VehicleLogRepository> { every { maxOdometerKm } returns flowOf(40_000) }
        val odometer = LogbookOdometer(prefs, log, backgroundScope)
        runCurrent()

        odometer.addDistance(0.75f)
        odometer.flush()
        assertEquals("noch nichts gelesen: nichts geschrieben", emptyList<Pair<Int, Float>>(), writes)
        assertNull(odometer.counter.first { true })

        gate.complete(GpsOdometer(40_000, 10f))
        runCurrent()

        assertEquals(GpsOdometer(40_000, 10.75f), odometer.counter.first())
    }

    @Test
    fun `a failing write is not fatal, the counter stays in memory`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = fakeAppPreferences(gpsOdometer = GpsOdometer(40_000, 10f))
        coEvery { prefs.setGpsOdometer(any(), any()) } throws IOException("disk")
        val log = mockk<VehicleLogRepository> { every { maxOdometerKm } returns MutableStateFlow<Int?>(40_000) }
        val odometer = LogbookOdometer(prefs, log, backgroundScope)

        odometer.addDistance(1f)
        odometer.addDistance(1f)

        assertEquals(GpsOdometer(40_000, 12f), odometer.counter.first())
        coVerify(atLeast = 1) { prefs.setGpsOdometer(40_000, any()) }
    }

    @Test
    fun `unusable distances are ignored`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(stored = GpsOdometer(40_000, 10f))

        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) f.odometer.addDistance(bad)

        assertEquals(GpsOdometer(40_000, 10f), f.odometer.counter.first())
    }

    // --- restore / current (Daten wiederherstellen) ---

    @Test
    fun `restore sets the state and writes at once, current reads the memory`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        f.odometer.restore(GpsOdometer(40_000, 3.25f))

        assertEquals(GpsOdometer(40_000, 3.25f), f.odometer.current())
        assertEquals(GpsOdometer(40_000, 3.25f), f.odometer.counter.first())
        assertEquals(listOf(40_000 to 3.25f), writes)
    }

    @Test
    fun `current is null while nothing is ready`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(stored = null, maxKm = null)

        assertNull(f.odometer.current())
    }

    @Test
    fun `unusable values never reach the writer`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        for (bad in listOf(GpsOdometer(-1, 1f), GpsOdometer(1_000_000, 1f), GpsOdometer(40_000, -1f), GpsOdometer(40_000, Float.NaN), GpsOdometer(40_000, Float.POSITIVE_INFINITY))) {
            f.odometer.restore(bad)
        }

        assertEquals(GpsOdometer(40_000, 10f), f.odometer.current())
        assertEquals(emptyList<Pair<Int, Float>>(), writes)
        coVerify(exactly = 0) { f.prefs.setGpsOdometer(any(), any()) }
    }

    @Test
    fun `restore before the log reports the new highest reading ends at the restored counter`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        f.odometer.restore(GpsOdometer(40_300, 7f))
        f.maxKm.value = 40_300

        assertEquals(GpsOdometer(40_300, 7f), f.odometer.current())
        assertEquals("zuletzt geschrieben: der wiederhergestellte Stand", 40_300 to 7f, writes.last())
    }

    @Test
    fun `restore after the log reported the new highest reading ends at the restored counter`() = runTest(UnconfinedTestDispatcher()) {
        val writes = mutableListOf<Pair<Int, Float>>()
        val f = fixture(stored = GpsOdometer(40_000, 10f)) { a, k -> writes += a to k }

        f.maxKm.value = 40_300
        f.odometer.restore(GpsOdometer(40_300, 7f))

        assertEquals(GpsOdometer(40_300, 7f), f.odometer.current())
        assertEquals(40_300 to 7f, writes.last())
    }
}
