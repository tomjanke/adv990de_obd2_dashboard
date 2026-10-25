package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * Der Verbrauch von Oel und Kuehlfluessigkeit (Entwurf `2026-10-20-logbook-design.md`): ein Intervall liegt zwischen zwei Eintraegen DERSELBEN Art, der Verbrauch ist die Menge des
 * spaeteren Eintrags je 1000 km. Der erste Eintrag ist nur Bezug.
 */
class FluidStatsTest {

    private fun oil(km: Int, ml: Int, id: Long = km.toLong(), date: LocalDate = TEST_TODAY.minusDays((100_000 - km).toLong())) =
        logEntry(LogType.OIL, date = date, km = km, amountMl = ml, id = id)

    private fun coolant(km: Int, ml: Int, id: Long = km.toLong()) = logEntry(LogType.COOLANT, km = km, amountMl = ml, id = id, date = TEST_TODAY.minusDays((100_000 - km).toLong()))

    @Test
    fun `the amount of the later entry over the distance gives the consumption per 1000 km`() {
        val report = fluidReport(listOf(oil(10_000, 100), oil(10_500, 50), oil(11_500, 150)), LogType.OIL)

        assertEquals(2, report.intervals.size)
        with(report.intervals[0]) {
            assertEquals(10_000, startKm)
            assertEquals(10_500, endKm)
            assertEquals(500, km)
            assertEquals(50, amountMl)
            assertEquals(100.0, mlPer1000Km, 1e-9)
        }
        assertEquals(150.0, report.intervals[1].mlPer1000Km, 1e-9)
    }

    @Test
    fun `the average is the sum of millilitres over the sum of kilometres`() {
        val summary = fluidReport(listOf(oil(10_000, 100), oil(10_500, 50), oil(11_500, 150)), LogType.OIL).summary!!

        // (50 + 150) ml / 1500 km * 1000 = 133,33 ml/1000 km; der Mittelwert der Einzelwerte waere 125
        assertEquals(200.0 / 1.5, summary.averageMlPer1000Km, 1e-9)
        assertEquals(200L, summary.totalMl)
        assertEquals(1_500, summary.totalKm)
        assertEquals(2, summary.intervalCount)
        assertEquals(11_500, summary.latest.endKm)
    }

    @Test
    fun `the first entry is only a reference`() {
        val report = fluidReport(listOf(oil(10_000, 100)), LogType.OIL)

        assertTrue(report.intervals.isEmpty())
        assertNull(report.summary)
    }

    @Test
    fun `oil and coolant are separate series`() {
        val entries = listOf(oil(10_000, 100), coolant(10_100, 200), oil(10_500, 50), coolant(12_100, 100))

        assertEquals(50, fluidReport(entries, LogType.OIL).intervals.single().amountMl)
        assertEquals(500, fluidReport(entries, LogType.OIL).intervals.single().km)
        assertEquals(100, fluidReport(entries, LogType.COOLANT).intervals.single().amountMl)
        assertEquals(2_000, fluidReport(entries, LogType.COOLANT).intervals.single().km)
    }

    @Test
    fun `other kinds of entry change nothing`() {
        val oils = listOf(oil(10_000, 100), oil(10_500, 50))
        val others = oils + listOf(logEntry(LogType.FUEL, km = 10_200), logEntry(LogType.SERVICE_SMALL, km = 10_300))

        assertEquals(fluidReport(oils, LogType.OIL), fluidReport(others, LogType.OIL))
    }

    @Test
    fun `entries at the same odometer reading leave that interval out, the later one stays the reference`() {
        val report = fluidReport(listOf(oil(10_000, 100, id = 1), oil(10_000, 50, id = 2), oil(10_500, 25, id = 3)), LogType.OIL)

        assertEquals(1, report.skipped)
        assertEquals(1, report.intervals.size)
        assertEquals(10_000, report.intervals.single().startKm)
        assertEquals(25, report.intervals.single().amountMl)
    }

    @Test
    fun `the input order does not matter`() {
        val sorted = listOf(oil(10_000, 100), oil(10_500, 50), oil(11_500, 150))

        assertEquals(fluidReport(sorted, LogType.OIL), fluidReport(sorted.reversed(), LogType.OIL))
    }

    @Test
    fun `an entry without an amount is ignored`() {
        val broken = logEntry(LogType.OIL, km = 10_200, amountMl = null)

        assertEquals(50, fluidReport(listOf(oil(10_000, 100), broken, oil(10_500, 50)), LogType.OIL).intervals.single().amountMl)
    }

    @Test
    fun `the interval carries the id and the date of the entry that closes it`() {
        val interval = fluidReport(listOf(oil(10_000, 100, id = 7), oil(10_500, 50, id = 8, date = LocalDate.of(2026, 9, 5))), LogType.OIL).intervals.single()

        assertEquals(8L, interval.endEntryId)
        assertEquals(LocalDate.of(2026, 9, 5), interval.date)
    }

    @Test
    fun `only oil and coolant have a fluid consumption`() {
        try {
            fluidReport(emptyList(), LogType.FUEL)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            fluidReport(emptyList(), LogType.CHAIN_SMALL)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
