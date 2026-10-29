package de.adv990de.obd2.fuel

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Der Zaehler der Tankanzeige: Zustand, Drosselung des Speicherns, Zuruecksetzen und das Verhalten, solange der gespeicherte Wert noch
 * nicht gelesen ist. [AppPreferences] ist die strenge Attrappe (`fakeAppPreferences`); jeder Schreibvorgang landet in [written].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelRepositoryTest {

    private val written = mutableListOf<Float>()

    /** Jeder Schreibvorgang des Caches der gelernten Reichweite (`fuel_range_learned_km`). */
    private val cacheWrites = mutableListOf<Int>()

    private fun prefs(range: Int = 200, km: Float = 0f, reserve: Int = 0): AppPreferences =
        fakeAppPreferences(fuelRangeFullKm = range, fuelKmSinceFill = km, fuelReserveKm = reserve).also(::stubWrites)

    private fun stubWrites(prefs: AppPreferences) {
        coEvery { prefs.setFuelKmSinceFill(any()) } answers { written += firstArg<Float>() }
        coEvery { prefs.setFuelRangeLearnedKm(any()) } answers { cacheWrites += firstArg<Int>() }
    }

    /** Die Quelle ohne Logbuch: ohne Tankinhalt in den Einstellungen wird es ohnehin nie gelesen. */
    private fun rangeSource(prefs: AppPreferences, entries: kotlinx.coroutines.flow.Flow<List<LogEntry>> = MutableStateFlow(emptyList())) =
        FuelRangeSource(prefs, mockk<VehicleLogRepository> { every { this@mockk.entries } returns entries })

    private fun TestScope.repo(prefs: AppPreferences, source: FuelRangeSource = rangeSource(prefs)) =
        FuelRepository(prefs, source, backgroundScope).also { runCurrent() }

    @Test
    fun `with a range and a stored counter the state is available at once`() = runTest {
        val repo = repo(prefs(range = 320, km = 100f))

        assertEquals(FuelGaugeState(320, 100f), repo.state.value)
    }

    @Test
    fun `without a range there is no gauge`() = runTest {
        assertNull(repo(prefs(range = 0)).state.value)
    }

    @Test
    fun `the reserve from the settings is part of the state`() = runTest {
        val repo = repo(prefs(range = 320, km = 100f, reserve = 40))

        assertEquals(FuelGaugeState(320, 100f, reserveKm = 40), repo.state.value)
    }

    @Test
    fun `without a reserve the state carries 0`() = runTest {
        assertEquals(0, repo(prefs(range = 320)).state.value!!.reserveKm)
    }

    @Test
    fun `a reserve without a range is still no gauge`() = runTest {
        assertNull(repo(prefs(range = 0, reserve = 40)).state.value)
    }

    @Test
    fun `a changed reserve reaches the state without a restart`() = runTest {
        val reserve = MutableStateFlow(40)
        val prefs = prefs(range = 320)
        every { prefs.fuelReserveKm } returns reserve
        val repo = repo(prefs)
        assertEquals(40, repo.state.value!!.reserveKm)

        reserve.value = 60
        runCurrent()

        assertEquals(60, repo.state.value!!.reserveKm)
    }

    @Test
    fun `added distance moves the gauge`() = runTest {
        val repo = repo(prefs(range = 200, km = 10f))

        repo.addDistance(5f)
        runCurrent()

        assertEquals(15f, repo.state.value!!.kmSinceFill, 1e-4f)
    }

    @Test
    fun `non-positive and non-finite distances are ignored`() = runTest {
        val repo = repo(prefs(range = 200, km = 10f))

        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) repo.addDistance(bad)
        repo.flush()
        runCurrent()

        assertEquals(10f, repo.state.value!!.kmSinceFill, 0f)
        assertTrue("nichts zu schreiben: $written", written.isEmpty())
    }

    @Test
    fun `a new range applies at once - the counter stays`() = runTest {
        val range = MutableStateFlow(0)
        val prefs = fakeAppPreferences(fuelKmSinceFill = 50f).also(::stubWrites)
        every { prefs.fuelRangeFullKm } returns range
        val repo = repo(prefs)
        assertNull(repo.state.value)

        range.value = 400
        runCurrent()
        assertEquals(FuelGaugeState(400, 50f), repo.state.value)

        range.value = 0
        runCurrent()
        assertNull("Reichweite geloescht = Leiste aus", repo.state.value)
    }

    @Test
    fun `small steps are not written, the step that reaches 0,2 km is`() = runTest {
        val repo = repo(prefs(km = 10f))

        repo.addDistance(0.15f)
        runCurrent()
        assertTrue("noch nichts geschrieben: $written", written.isEmpty())

        repo.addDistance(0.1f)
        runCurrent()
        assertEquals(listOf(10.25f), written)
    }

    @Test
    fun `after a write the next step counts from the written value`() = runTest {
        val repo = repo(prefs(km = 0f))

        repo.addDistance(0.25f)
        runCurrent()
        repo.addDistance(0.15f)
        runCurrent()
        assertEquals(listOf(0.25f), written)

        repo.addDistance(0.1f)
        runCurrent()
        assertEquals(listOf(0.25f, 0.5f), written)
    }

    @Test
    fun `flush writes what has not been written yet, and only once`() = runTest {
        val repo = repo(prefs(km = 10f))
        repo.addDistance(0.05f)

        repo.flush()
        runCurrent()
        repo.flush()
        runCurrent()

        assertEquals(listOf(10.05f), written)
    }

    @Test
    fun `flush without a change writes nothing`() = runTest {
        val repo = repo(prefs(km = 10f))

        repo.flush()
        runCurrent()

        assertTrue(written.isEmpty())
    }

    @Test
    fun `a burst of steps writes in order and ends with the latest value`() = runTest {
        val repo = repo(prefs(km = 0f))

        repeat(5) { repo.addDistance(0.3f) }
        runCurrent()

        assertEquals("aufsteigend, nie ein aelterer Wert nach einem neueren: $written", written.sorted(), written)
        assertEquals(1.5f, written.last(), 1e-4f)
    }

    @Test
    fun `reset puts the gauge back to full and writes 0 at once`() = runTest {
        val repo = repo(prefs(range = 200, km = 150f))

        repo.reset()
        runCurrent()

        assertEquals(FuelGaugeState(200, 0f), repo.state.value)
        assertEquals(listOf(0f), written)
    }

    @Test
    fun `after a reset the next step counts from zero`() = runTest {
        val repo = repo(prefs(km = 150f))
        repo.reset()
        runCurrent()

        repo.addDistance(0.25f)
        runCurrent()

        assertEquals(listOf(0f, 0.25f), written)
    }

    @Test
    fun `a write failure does not stop later writes`() = runTest {
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200, fuelKmSinceFill = 0f)
        var calls = 0
        coEvery { prefs.setFuelKmSinceFill(any()) } answers {
            calls++
            if (calls == 1) throw IOException("disk full") else written += firstArg<Float>()
        }
        val repo = repo(prefs)

        repo.addDistance(0.25f)
        runCurrent()
        repo.addDistance(0.25f)
        runCurrent()

        assertEquals("der zweite Schreibversuch kommt durch", listOf(0.5f), written)
    }

    // ── Der gespeicherte Zaehler ist noch nicht gelesen (haengende Platte) ──────────────────────────────

    @Test
    fun `while the stored counter is not read there is no gauge and nothing is written`() = runTest {
        val stored = MutableSharedFlow<Float>() // antwortet erst, wenn der Test es sagt
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)

        repo.addDistance(5f)
        repo.flush()
        runCurrent()

        assertNull("nicht geladen = keine Anzeige", repo.state.value)
        assertTrue("nichts schreiben, solange der alte Wert unbekannt ist: $written", written.isEmpty())
    }

    @Test
    fun `distance counted before the stored counter arrives is added to it`() = runTest {
        val stored = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)
        repo.addDistance(5f)
        runCurrent()

        stored.emit(100f)
        runCurrent()

        assertEquals(FuelGaugeState(200, 105f), repo.state.value)
    }

    @Test
    fun `a reset before the stored counter arrives wins over the late value`() = runTest {
        val stored = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)
        runCurrent()

        repo.reset()
        runCurrent()
        stored.emit(100f)
        runCurrent()

        assertNotNull(repo.state.value)
        assertEquals(0f, repo.state.value!!.kmSinceFill, 0f)
    }

    // --- F5: die Reichweite aus dem Logbuch und der Cache fuer das erste Bild ---

    private val day = java.time.LocalDate.of(2026, 1, 1)

    private fun fill(id: Long, km: Int, ml: Int) = LogEntry(
        id = id, type = LogType.FUEL, date = day.plusDays(id), odometerKm = km, amountMl = ml, fullTank = true,
        costCents = null, note = null, brand = null,
    )

    /** Eine erste volle Tankung und [intervals] Intervalle zu je 100 km und 7,7 l = 7,7 l/100 km (mit 20,0 l Tankinhalt 260 km). */
    private fun steadyFills(intervals: Int) = listOf(fill(0, 1_000, 15_000)) + List(intervals) { fill(it + 1L, 1_100 + it * 100, 7_700) }

    private fun learningPrefs(manual: Int = 0, capacityDl: Int = 200, cache: Int = 0): AppPreferences =
        fakeAppPreferences(fuelRangeFullKm = manual, fuelTankCapacityDl = capacityDl, fuelRangeLearnedKm = cache, fuelKmSinceFill = 0f).also(::stubWrites)

    @Test
    fun `with a tank content the gauge uses the range learned from the logbook`() = runTest {
        val prefs = learningPrefs(manual = 320)
        val repo = repo(prefs, rangeSource(prefs, MutableStateFlow(steadyFills(3))))

        assertEquals(260, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `the learned range makes the gauge appear even without a manual range`() = runTest {
        val prefs = learningPrefs(manual = 0)
        val repo = repo(prefs, rangeSource(prefs, MutableStateFlow(steadyFills(3))))

        assertEquals(FuelGaugeState(260, 0f), repo.state.value)
    }

    @Test
    fun `a changed range moves only the remainder - the counter stays`() = runTest {
        val prefs = learningPrefs(manual = 320)
        every { prefs.fuelKmSinceFill } returns kotlinx.coroutines.flow.flowOf(100f)
        val entries = MutableStateFlow(steadyFills(3))
        val repo = repo(prefs, rangeSource(prefs, entries))
        assertEquals(160f, repo.state.value!!.remainingKm, 1e-3f) // 260 - 100

        entries.value = entries.value + fill(9, 1_400, 11_700) // letzte drei Intervalle 7,7 + 7,7 + 11,7 l auf 300 km = 9,033 l/100 km -> 221 km
        runCurrent()

        assertEquals(100f, repo.state.value!!.kmSinceFill, 1e-3f)
        assertEquals(221, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `until the logbook answers the first picture shows the cached learned range`() = runTest {
        val prefs = learningPrefs(manual = 320, cache = 260)
        val never = kotlinx.coroutines.flow.MutableSharedFlow<List<LogEntry>>() // das Logbuch antwortet nicht

        val repo = repo(prefs, rangeSource(prefs, never))

        assertEquals("der Cache, nicht die manuelle Zahl", 260, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `the cache is written when the learned range changes - and only then`() = runTest {
        val prefs = learningPrefs(capacityDl = 200, cache = 0)
        val entries = MutableStateFlow(steadyFills(3))
        repo(prefs, rangeSource(prefs, entries))

        assertEquals(listOf(260), cacheWrites)

        // Eine Aenderung, die die Reichweite nicht beruehrt, schreibt nichts.
        entries.value = entries.value + LogEntry(id = 99, type = LogType.OIL, date = day, odometerKm = 1_050, amountMl = 200, fullTank = null, costCents = null, note = null, brand = null)
        runCurrent()
        assertEquals(listOf(260), cacheWrites)

        entries.value = entries.value + fill(9, 1_400, 11_700) // 221 km (letzte drei Intervalle, 9,033 l/100 km)
        runCurrent()
        assertEquals(listOf(260, 221), cacheWrites)
    }

    @Test
    fun `an already correct cache is not written again`() = runTest {
        val prefs = learningPrefs(capacityDl = 200, cache = 260)

        repo(prefs, rangeSource(prefs, MutableStateFlow(steadyFills(3))))

        assertTrue("nichts geschrieben: $cacheWrites", cacheWrites.isEmpty())
    }

    @Test
    fun `clearing the tank content clears the cache`() = runTest {
        val capacity = MutableStateFlow(200)
        val prefs = learningPrefs(manual = 320, capacityDl = 200, cache = 260)
        every { prefs.fuelTankCapacityDl } returns capacity
        val repo = repo(prefs, rangeSource(prefs, MutableStateFlow(steadyFills(3))))
        assertEquals(260, repo.state.value!!.rangeFullKm)

        capacity.value = 0
        runCurrent()

        assertEquals(listOf(0), cacheWrites)
        assertEquals("wieder die manuelle Reichweite", 320, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `a failing cache write never breaks the gauge and is tried again with the next change`() = runTest {
        val prefs = learningPrefs(capacityDl = 200, cache = 0)
        var failNext = true
        coEvery { prefs.setFuelRangeLearnedKm(any()) } answers {
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            cacheWrites += firstArg<Int>()
        }
        val entries = MutableStateFlow(steadyFills(3))
        val repo = repo(prefs, rangeSource(prefs, entries))
        assertEquals("die Anzeige laeuft trotz Fehler", 260, repo.state.value!!.rangeFullKm)
        assertTrue(cacheWrites.isEmpty())

        entries.value = entries.value + fill(9, 1_400, 11_700) // 221 km (letzte drei Intervalle, 9,033 l/100 km)
        runCurrent()

        assertEquals(listOf(221), cacheWrites)
        assertEquals(221, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `the cache is no input - a changed cache value does not move the range`() = runTest {
        val cache = MutableStateFlow(0)
        val prefs = learningPrefs(manual = 320, capacityDl = 0)
        every { prefs.fuelRangeLearnedKm } returns cache
        val repo = repo(prefs)
        assertEquals(320, repo.state.value!!.rangeFullKm)

        cache.value = 777
        runCurrent()

        assertEquals(320, repo.state.value!!.rangeFullKm)
    }

    @Test
    fun `without a tank content nothing is read from the logbook and nothing is cached`() = runTest {
        val prefs = learningPrefs(manual = 320, capacityDl = 0, cache = 0)

        repo(prefs, rangeSource(prefs, MutableStateFlow(steadyFills(5))))

        assertTrue(cacheWrites.isEmpty())
    }

    // --- Teilbetankung: credit() hebt die Anzeige an ---

    @Test
    fun `credit lowers the counter and writes it at once`() = runTest {
        val repo = repo(prefs(range = 200, km = 100f))

        repo.credit(30f)
        runCurrent()

        assertEquals(70f, repo.state.value!!.kmSinceFill, 1e-4f)
        assertEquals(listOf(70f), written)
    }

    @Test
    fun `credit never takes the counter below zero - the gauge is full at most`() = runTest {
        val repo = repo(prefs(range = 200, km = 20f))

        repo.credit(50f)
        runCurrent()

        assertEquals(0f, repo.state.value!!.kmSinceFill, 0f)
        assertEquals(listOf(0f), written)
    }

    @Test
    fun `credit with nothing sensible changes nothing and writes nothing`() = runTest {
        val repo = repo(prefs(range = 200, km = 100f))

        for (bad in listOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) repo.credit(bad)
        runCurrent()

        assertEquals(100f, repo.state.value!!.kmSinceFill, 0f)
        assertTrue("nichts geschrieben: $written", written.isEmpty())
    }

    @Test
    fun `credit on a counter that is not read yet is ignored - the stored value arrives untouched`() = runTest {
        val stored = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)
        repo.addDistance(10f) // gezaehlt, bevor der gespeicherte Wert da ist: das ist der Teil, den eine voreilige Gutschrift anfassen wuerde

        repo.credit(30f)
        runCurrent()
        stored.emit(100f)
        runCurrent()

        assertEquals("die Gutschrift ging verloren, die gezaehlten 10 km nicht", FuelGaugeState(200, 110f), repo.state.value)
        assertTrue("nichts geschrieben: $written", written.isEmpty())
    }

    @Test
    fun `a credit after counted distance subtracts from the sum`() = runTest {
        val repo = repo(prefs(range = 200, km = 100f))
        repo.addDistance(10f)

        repo.credit(30f)
        runCurrent()

        assertEquals(80f, repo.state.value!!.kmSinceFill, 1e-4f)
    }
}
