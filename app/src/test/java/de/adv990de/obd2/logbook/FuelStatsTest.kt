package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * Die Verbrauchsrechnung "Voll-zu-Voll" (Entwurf `2026-10-20-logbook-design.md`, Abschnitt "Rechnungen"): Intervalle, Teiltankungen, der Durchschnitt als Summe durch Summe,
 * Kosten. Alle Zahlen sind von Hand gerechnet.
 */
class FuelStatsTest {

    private fun fill(km: Int, ml: Int, full: Boolean? = true, cost: Int? = null, id: Long = km.toLong(), date: LocalDate = TEST_TODAY.minusDays((100_000 - km).toLong())) =
        logEntry(LogType.FUEL, date = date, km = km, amountMl = ml, fullTank = full, costCents = cost, id = id)

    @Test
    fun `a full fill-up closes an interval from the previous full one`() {
        val report = fuelReport(listOf(fill(1_000, 10_000), fill(1_200, 12_000), fill(1_500, 24_000)))

        assertEquals(2, report.intervals.size)
        with(report.intervals[0]) {
            assertEquals(1_000, startKm)
            assertEquals(1_200, endKm)
            assertEquals(200, km)
            assertEquals(12_000L, amountMl)
            assertEquals(6.0, litersPer100Km, 1e-9)
        }
        assertEquals(8.0, report.intervals[1].litersPer100Km, 1e-9)
    }

    @Test
    fun `the average is the sum of litres over the sum of kilometres, not the mean of the single values`() {
        val summary = fuelReport(listOf(fill(1_000, 10_000), fill(1_200, 12_000), fill(1_500, 24_000))).summary!!

        // (12 + 24) l / (200 + 300) km = 7,2 l/100 km; der Mittelwert der Einzelwerte waere (6 + 8) / 2 = 7,0
        assertEquals(7.2, summary.averageLitersPer100Km, 1e-9)
        assertEquals(500, summary.intervalKm)
        assertEquals(36_000L, summary.intervalMl)
    }

    @Test
    fun `partial fill-ups between two full ones add their litres to the interval`() {
        val report = fuelReport(listOf(fill(1_000, 10_000), fill(1_100, 5_000, full = false), fill(1_200, 10_000)))

        assertEquals(1, report.intervals.size)
        assertEquals(15_000L, report.intervals.single().amountMl)
        assertEquals(7.5, report.intervals.single().litersPer100Km, 1e-9)
    }

    @Test
    fun `a partial fill-up before the first full one does not count`() {
        val report = fuelReport(listOf(fill(900, 5_000, full = false), fill(1_000, 10_000), fill(1_200, 12_000)))

        assertEquals(1, report.intervals.size)
        assertEquals(12_000L, report.intervals.single().amountMl)
    }

    @Test
    fun `the first full fill-up is only a reference and gives no interval`() {
        val report = fuelReport(listOf(fill(1_000, 10_000)))

        assertTrue(report.intervals.isEmpty())
        assertNull(report.summary)
        assertEquals(0, report.skipped)
    }

    @Test
    fun `no fuel entries give an empty report`() {
        val report = fuelReport(listOf(logEntry(LogType.OIL, km = 1_000), logEntry(LogType.CHAIN_SMALL, km = 1_100)))

        assertTrue(report.intervals.isEmpty())
        assertNull(report.summary)
    }

    @Test
    fun `a full fill-up without the flag counts as full`() {
        val report = fuelReport(listOf(fill(1_000, 10_000, full = null), fill(1_200, 12_000, full = null)))

        assertEquals(1, report.intervals.size)
    }

    @Test
    fun `a full fill-up at the same odometer reading leaves that interval out but stays the reference for the next`() {
        val report = fuelReport(listOf(fill(1_000, 10_000, id = 1), fill(1_000, 8_000, id = 2), fill(1_200, 12_000, id = 3)))

        assertEquals(1, report.skipped)
        assertEquals(1, report.intervals.size)
        assertEquals(1_000, report.intervals.single().startKm)
        assertEquals(12_000L, report.intervals.single().amountMl)
    }

    @Test
    fun `other kinds of entry change nothing`() {
        val fuel = listOf(fill(1_000, 10_000), fill(1_200, 12_000))
        val withOthers = fuel + listOf(logEntry(LogType.OIL, km = 1_100, amountMl = 100), logEntry(LogType.SERVICE_LARGE, km = 1_150), logEntry(LogType.COOLANT, km = 1_190, amountMl = 50))

        assertEquals(fuelReport(fuel), fuelReport(withOthers))
    }

    @Test
    fun `the input order does not matter`() {
        val sorted = listOf(fill(1_000, 10_000), fill(1_100, 5_000, full = false), fill(1_200, 10_000), fill(1_500, 24_000))

        assertEquals(fuelReport(sorted), fuelReport(sorted.reversed()))
        assertEquals(fuelReport(sorted), fuelReport(listOf(sorted[2], sorted[0], sorted[3], sorted[1])))
    }

    @Test
    fun `a fill-up without an amount is ignored`() {
        val broken = logEntry(LogType.FUEL, km = 1_100, amountMl = null, fullTank = true)
        val report = fuelReport(listOf(fill(1_000, 10_000), broken, fill(1_200, 12_000)))

        assertEquals(12_000L, report.intervals.single().amountMl)
    }

    @Test
    fun `the interval carries the id and the date of the full fill-up that closes it`() {
        val closing = fill(1_200, 12_000, id = 42, date = LocalDate.of(2026, 9, 5))
        val interval = fuelReport(listOf(fill(1_000, 10_000, id = 41), closing)).intervals.single()

        assertEquals(42L, interval.endEntryId)
        assertEquals(LocalDate.of(2026, 9, 5), interval.date)
    }

    @Test
    fun `latest, best and worst interval`() {
        // 6,0 · 8,0 · 7,0 l/100 km
        val summary = fuelReport(listOf(fill(1_000, 1), fill(1_100, 6_000), fill(1_200, 8_000), fill(1_300, 7_000))).summary!!

        assertEquals(7.0, summary.latest.litersPer100Km, 1e-9)
        assertEquals(6.0, summary.best.litersPer100Km, 1e-9)
        assertEquals(8.0, summary.worst.litersPer100Km, 1e-9)
        assertEquals(3, summary.intervalCount)
    }

    @Test
    fun `with equal values the best and the worst are the more recent interval`() {
        val summary = fuelReport(listOf(fill(1_000, 1), fill(1_100, 7_000, id = 1), fill(1_200, 7_000, id = 2))).summary!!

        assertEquals(2L, summary.best.endEntryId)
        assertEquals(2L, summary.worst.endEntryId)
    }

    @Test
    fun `all litres, the mean distance between fill-ups`() {
        val summary = fuelReport(listOf(fill(1_000, 10_000), fill(1_100, 5_000, full = false), fill(1_200, 10_000), fill(1_500, 24_000))).summary!!

        // alle Tankungen: 10 + 5 + 10 + 24 = 49 l; zwei Intervalle ueber 200 + 300 km
        assertEquals(49_000L, summary.totalMl)
        assertEquals(250.0, summary.averageKmPerInterval, 1e-9)
    }

    @Test
    fun `the cost of an interval is the cost of every fill-up in it, and missing when one has none`() {
        val report = fuelReport(
            listOf(
                fill(1_000, 10_000, cost = 1_500),
                fill(1_100, 5_000, full = false, cost = 800),
                fill(1_200, 10_000, cost = 1_700),
                fill(1_300, 6_000, cost = null),
            ),
        )

        assertEquals(2_500L, report.intervals[0].costCents)
        assertNull(report.intervals[1].costCents)
    }

    @Test
    fun `a partial fill-up without cost makes its interval cost missing`() {
        val interval = fuelReport(
            listOf(fill(1_000, 10_000, cost = 1_500), fill(1_100, 5_000, full = false, cost = null), fill(1_200, 10_000, cost = 1_700)),
        ).intervals.single()

        assertNull(interval.costCents)
    }

    @Test
    fun `the cost per 100 km only uses intervals in which every fill-up has a cost`() {
        val summary = fuelReport(
            listOf(
                fill(1_000, 10_000, cost = 1_500),
                fill(1_200, 12_000, cost = 1_800),
                fill(1_400, 11_000, cost = null),
                fill(1_700, 18_000, cost = 3_000),
            ),
        ).summary!!

        // vollstaendig: 1_000 -> 1_200 (1_800 Cent, 200 km) und 1_400 -> 1_700 (3_000 Cent, 300 km): 4_800 Cent / 500 km * 100
        assertEquals(960.0, summary.costCentsPer100Km!!, 1e-9)
    }

    @Test
    fun `the average price per litre uses every fill-up that has a cost, including the first and partial ones`() {
        val summary = fuelReport(
            listOf(
                fill(1_000, 10_000, cost = 1_500),
                fill(1_100, 5_000, full = false, cost = 1_000),
                fill(1_200, 10_000, cost = null),
                fill(1_300, 10_000, cost = 1_700),
            ),
        ).summary!!

        // (1_500 + 1_000 + 1_700) Cent / (10 + 5 + 10) l = 168 Cent je Liter; ohne die Teiltankung waeren es 3_200 / 20 = 160
        assertEquals(168.0, summary.averagePriceCentsPerLiter!!, 1e-9)
    }

    @Test
    fun `without any cost there is no price and no cost per 100 km`() {
        val summary = fuelReport(listOf(fill(1_000, 10_000), fill(1_200, 12_000))).summary!!

        assertNull(summary.averagePriceCentsPerLiter)
        assertNull(summary.costCentsPer100Km)
    }

    @Test
    fun `a random series agrees with a slow reference calculation`() {
        val random = Random(7)
        repeat(50) { round ->
            var km = random.nextInt(0, 500)
            val entries = (0 until random.nextInt(2, 30)).map { i ->
                km += random.nextInt(0, 400)
                fill(km, random.nextInt(1_000, 20_000), full = random.nextInt(4) != 0, cost = if (random.nextInt(3) == 0) null else random.nextInt(100, 3_000), id = i.toLong() + 1)
            }
            val report = fuelReport(entries.shuffled(random))
            val expected = referenceIntervals(entries)
            assertEquals("round $round", expected.map { Triple(it.first, it.second, it.third) }, report.intervals.map { Triple(it.startKm, it.endKm, it.amountMl) })
            if (expected.isNotEmpty()) {
                val summary = report.summary!!
                assertEquals("round $round", expected.sumOf { it.third }.toDouble() / (10.0 * expected.sumOf { it.second - it.first }), summary.averageLitersPer100Km, 1e-9)
            }
        }
    }

    /** Eine zweite, bewusst plumpe Rechnung: je volle Tankung der Eintrag davor suchen, Teiltankungen dazwischen per Index addieren. Liefert (Start, Ende, Milliliter). */
    private fun referenceIntervals(entries: List<LogEntry>): List<Triple<Int, Int, Long>> {
        val sorted = entries.sortedWith(compareBy({ it.odometerKm }, { it.date }, { it.id }))
        val result = mutableListOf<Triple<Int, Int, Long>>()
        var previous = -1
        for (j in sorted.indices) {
            if (sorted[j].fullTank == false) continue
            if (previous >= 0 && sorted[j].odometerKm > sorted[previous].odometerKm) {
                val liters = (previous + 1..j).sumOf { (sorted[it].amountMl ?: 0).toLong() }
                result += Triple(sorted[previous].odometerKm, sorted[j].odometerKm, liters)
            }
            previous = j
        }
        return result
    }
}
