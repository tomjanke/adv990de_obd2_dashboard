package de.adv990de.obd2.fuel

import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Die wirksame Reichweite der Tankanzeige (Entwurf `2026-10-25-range-design.md`, D4/D5/D6): die manuelle Zahl, oder — mit Tankinhalt und mindestens drei Voll-zu-Voll-Intervallen im
 * Logbuch — Tankinhalt / Verbrauch. Alle Zahlen sind erfunden: 20,0 l bei 7,7 l/100 km sind 260 km.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelRangeSourceTest {

    private val day = LocalDate.of(2026, 1, 1)

    private fun fill(id: Long, km: Int, ml: Int, full: Boolean = true) = LogEntry(
        id = id, type = LogType.FUEL, date = day.plusDays(id), odometerKm = km, amountMl = ml, fullTank = full,
        costCents = null, note = null, brand = null,
    )

    /** Eine erste volle Tankung und [intervals] Intervalle zu je 100 km und 7,7 l = 7,7 l/100 km. */
    private fun steadyFills(intervals: Int): List<LogEntry> =
        listOf(fill(0, 1_000, 15_000)) + List(intervals) { fill(it + 1L, 1_100 + it * 100, 7_700) }

    private class Logbook(initial: List<LogEntry>) {
        val entries = MutableStateFlow(initial)
        var subscriptions = 0
        val repository: VehicleLogRepository = mockk {
            every { this@mockk.entries } returns this@Logbook.entries.onStart { subscriptions++ }
        }
    }

    private fun source(
        manual: Int = 0,
        capacityDl: Int = 0,
        learnedCache: Int = 0,
        logbook: Logbook = Logbook(emptyList()),
    ) = FuelRangeSource(fakeAppPreferences(fuelRangeFullKm = manual, fuelTankCapacityDl = capacityDl, fuelRangeLearnedKm = learnedCache), logbook.repository)

    private suspend fun FuelRangeSource.current(): EffectiveRange = range.first()

    @Test
    fun `without a tank content the manual range counts and the logbook is not even read`() = runTest {
        val logbook = Logbook(steadyFills(5))

        val range = source(manual = 320, logbook = logbook).current()

        assertEquals(EffectiveRange(320, RangeOrigin.Manual), range)
        assertEquals("keine Abfrage des Logbuchs ohne Tankinhalt", 0, logbook.subscriptions)
    }

    @Test
    fun `with a tank content and enough intervals the range is learned from the logbook`() = runTest {
        val range = source(manual = 320, capacityDl = 200, logbook = Logbook(steadyFills(3))).current()

        assertEquals(260, range.km)
        val origin = range.origin as RangeOrigin.Learned
        assertEquals(7.7, origin.litersPer100Km, 1e-9)
    }

    @Test
    fun `with fewer than three intervals the manual range counts`() = runTest {
        for (intervals in 0..2) {
            val range = source(manual = 320, capacityDl = 200, logbook = Logbook(steadyFills(intervals))).current()

            assertEquals("$intervals Intervalle", EffectiveRange(320, RangeOrigin.Manual), range)
        }
    }

    @Test
    fun `an implausible learned range is not used - the manual one counts`() = runTest {
        // 60,0 l bei 3,0 l/100 km waeren 2000 km.
        val fills = listOf(fill(0, 1_000, 15_000)) + List(3) { fill(it + 1L, 1_100 + it * 100, 3_000) }

        val range = source(manual = 320, capacityDl = 600, logbook = Logbook(fills)).current()

        assertEquals(EffectiveRange(320, RangeOrigin.Manual), range)
    }

    @Test
    fun `the learned range also makes the gauge available without a manual range`() = runTest {
        val range = source(manual = 0, capacityDl = 200, logbook = Logbook(steadyFills(4))).current()

        assertEquals(260, range.km)
        assertTrue(range.origin is RangeOrigin.Learned)
    }

    @Test
    fun `without any basis there is no range`() = runTest {
        assertEquals(EffectiveRange(0, RangeOrigin.Manual), source().current())
    }

    @Test
    fun `a part fill is counted into the interval that closes it`() = runTest {
        val fills = listOf(
            fill(0, 1_000, 15_000),
            fill(1, 1_100, 7_000),
            fill(2, 1_150, 1_000, full = false),
            fill(3, 1_200, 7_000),
            fill(4, 1_300, 7_700),
            fill(5, 1_400, 7_700),
        )

        val range = source(capacityDl = 200, logbook = Logbook(fills)).current()

        // 7,6 l/100 km -> 20,0 l / 7,6 * 100 = 263,2
        assertEquals(263, range.km)
    }

    @Test
    fun `a new fill in the logbook changes the range without a restart`() = runTest {
        val logbook = Logbook(steadyFills(3))
        val source = source(capacityDl = 200, logbook = logbook)
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch { source.range.collect { seen += it.km } }
        runCurrent()
        assertEquals(260, seen.last())

        // Ein drittes Intervall mit hohem Verbrauch: 100 km mit 11,7 l.
        logbook.entries.value = logbook.entries.value + fill(9, 1_100 + 3 * 100, 11_700)
        runCurrent()

        // Die letzten vier Intervalle: 3 x 7,7 l und 11,7 l auf 400 km = 8,7 l/100 km -> 20,0 l / 8,7 * 100 = 229,9
        assertEquals(230, seen.last())
        job.cancel()
    }

    @Test
    fun `a changed tank content and a cleared one both reach the range`() = runTest {
        val capacity = MutableStateFlow(200)
        val prefs = fakeAppPreferences(fuelRangeFullKm = 320)
        every { prefs.fuelTankCapacityDl } returns capacity
        val logbook = Logbook(steadyFills(5))
        val source = FuelRangeSource(prefs, logbook.repository)
        val seen = mutableListOf<EffectiveRange>()
        val job = backgroundScope.launch { source.range.collect { seen += it } }
        runCurrent()
        assertEquals(260, seen.last().km)

        capacity.value = 150 // 15,0 l -> 194,8
        runCurrent()
        assertEquals(195, seen.last().km)

        capacity.value = 0
        runCurrent()
        assertEquals(EffectiveRange(320, RangeOrigin.Manual), seen.last())
        job.cancel()
    }

    @Test
    fun `a changed manual range reaches the range`() = runTest {
        val manual = MutableStateFlow(320)
        val prefs = fakeAppPreferences()
        every { prefs.fuelRangeFullKm } returns manual
        val source = FuelRangeSource(prefs, Logbook(emptyList()).repository)
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch { source.range.collect { seen += it.km } }
        runCurrent()

        manual.value = 400
        runCurrent()

        assertEquals(listOf(320, 400), seen)
        job.cancel()
    }

    @Test
    fun `an unchanged result is not emitted twice`() = runTest {
        val logbook = Logbook(steadyFills(5))
        val source = source(capacityDl = 200, logbook = logbook)
        val seen = mutableListOf<EffectiveRange>()
        val job = backgroundScope.launch { source.range.collect { seen += it } }
        runCurrent()

        // Eine Aenderung, die den Verbrauch nicht beruehrt (eine Notiz an einem Oeleintrag, hier: ein Eintrag anderer Art).
        logbook.entries.value = logbook.entries.value + LogEntry(
            id = 99, type = LogType.OIL, date = day, odometerKm = 1_050, amountMl = 200, fullTank = null, costCents = null, note = null, brand = null,
        )
        runCurrent()

        assertEquals("nur der erste Wert: $seen", 1, seen.size)
        job.cancel()
    }

    @Test
    fun `the logbook is only read while a tank content is set`() = runTest {
        val capacity = MutableStateFlow(0)
        val prefs = fakeAppPreferences()
        every { prefs.fuelTankCapacityDl } returns capacity
        val logbook = Logbook(steadyFills(5))
        val source = FuelRangeSource(prefs, logbook.repository)
        val job = backgroundScope.launch { source.range.collect { } }
        runCurrent()
        assertEquals(0, logbook.subscriptions)

        capacity.value = 200
        runCurrent()
        assertEquals(1, logbook.subscriptions)

        capacity.value = 0
        runCurrent()
        capacity.value = 200
        runCurrent()
        assertEquals("nach dem Loeschen und erneutem Setzen wieder genau eine neue Abfrage", 2, logbook.subscriptions)
        job.cancel()
    }

    @Test
    fun `the learned value alone is exposed for the cache - null without a tank content or a basis`() = runTest {
        assertNull(source(manual = 320).learned.first())
        assertNull(source(capacityDl = 200, logbook = Logbook(steadyFills(2))).learned.first())
        assertEquals(LearnedRange(260, 7.7), source(capacityDl = 200, logbook = Logbook(steadyFills(3))).learned.first())
    }

    // --- Startwert (D6) ---

    @Test
    fun `the first picture takes the cache when a tank content is set`() {
        assertEquals(260, source(manual = 320, capacityDl = 200, learnedCache = 260).seedRangeKm())
    }

    @Test
    fun `the first picture takes the manual range when the cache is empty`() {
        assertEquals(320, source(manual = 320, capacityDl = 200, learnedCache = 0).seedRangeKm())
    }

    @Test
    fun `a stale cache without a tank content is ignored`() {
        // Der Tankinhalt wurde geloescht, der Cache blieb stehen: der Startwert darf nicht die alte gelernte Zahl zeigen.
        assertEquals(320, source(manual = 320, capacityDl = 0, learnedCache = 260).seedRangeKm())
        assertEquals(0, source(manual = 0, capacityDl = 0, learnedCache = 260).seedRangeKm())
    }

    @Test
    fun `the seed is also zero without any information`() {
        assertEquals(0, source().seedRangeKm())
    }
}
