package de.adv990de.obd2.fuel

import de.adv990de.obd2.logbook.FuelInterval
import de.adv990de.obd2.logbook.FuelReport
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.fuelReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Die Reichweite bei vollem Tank aus dem Logbuch (Entwurf `2026-10-25-range-design.md`, D4/D5): Tankinhalt geteilt durch den Verbrauch der letzten Voll-zu-Voll-Intervalle.
 * Alle Zahlen sind erfunden (nie echte Daten des Nutzers): Tankinhalt 20,0 l bei 7,7 l/100 km ergibt 260 km.
 */
class RangeLearningTest {

    private val day = LocalDate.of(2026, 1, 1)

    /** Ein Intervall: [km] Kilometer mit [ml] Millilitern (Verbrauch = ml / (10 * km) l/100 km). */
    private fun interval(km: Int, ml: Long, start: Int = 0) =
        FuelInterval(startKm = start, endKm = start + km, amountMl = ml, date = day, costCents = null, endEntryId = 0)

    private fun report(vararg intervals: FuelInterval) = FuelReport(intervals.toList(), skipped = 0, summary = null)

    /** Drei Intervalle zu je 100 km und 7,7 l = 7,7 l/100 km. */
    private fun steady(count: Int) = report(*Array(count) { interval(100, 7_700) })

    // --- recentLitersPer100Km ---

    @Test
    fun `fewer than three intervals are no basis`() {
        assertNull(recentLitersPer100Km(report()))
        assertNull(recentLitersPer100Km(steady(1)))
        assertNull(recentLitersPer100Km(steady(2)))
    }

    @Test
    fun `three intervals are enough`() {
        assertEquals(7.7, recentLitersPer100Km(steady(3))!!, 1e-9)
    }

    @Test
    fun `the consumption is the sum of liters over the sum of kilometers - not the mean of the single values`() {
        // 100 km mit 5,0 l (5,0) und 300 km mit 30,0 l (10,0): Summe 35 l auf 400 km = 8,75; der Mittelwert der Einzelwerte waere 7,5.
        val r = report(interval(100, 5_000), interval(300, 30_000), interval(100, 8_750))
        // 43,75 l auf 500 km = 8,75
        assertEquals(8.75, recentLitersPer100Km(r)!!, 1e-9)
        assertEquals(8.75, recentLitersPer100Km(report(interval(100, 5_000), interval(300, 30_000)), window = 2, minIntervals = 2)!!, 1e-9)
    }

    @Test
    fun `only the newest ten intervals count - the oldest comes last in the report`() {
        // Elf Intervalle: das aelteste (erste) mit einem ganz anderen Verbrauch darf nicht eingehen, das juengste (letzte) schon.
        val intervals = listOf(interval(100, 20_000)) + List(9) { interval(100, 7_700) } + listOf(interval(100, 9_700))
        val r = FuelReport(intervals, skipped = 0, summary = null)

        // Die letzten zehn: 9 x 7,7 l und 1 x 9,7 l auf 1000 km = 7,9 l/100 km.
        assertEquals((9 * 7_700 + 9_700) / (10.0 * 1000), recentLitersPer100Km(r)!!, 1e-9)
    }

    @Test
    fun `with exactly ten intervals all of them count`() {
        val r = report(*Array(10) { interval(100, 7_700 + it * 100L) })

        assertEquals(Array(10) { 7_700 + it * 100L }.sum() / (10.0 * 1000), recentLitersPer100Km(r)!!, 1e-9)
    }

    @Test
    fun `an interval without kilometers cannot enter the sum`() {
        // fuelReport laesst solche Intervalle gar nicht erst entstehen (skipped); die Funktion verlaesst sich darauf, faengt aber eine Summe von 0 km ab.
        val r = report(interval(0, 5_000), interval(0, 5_000), interval(0, 5_000))

        assertNull(recentLitersPer100Km(r))
    }

    @Test
    fun `the window and the minimum can be given`() {
        val r = report(interval(100, 5_000), interval(100, 6_000), interval(100, 9_000), interval(100, 9_000))

        assertEquals(9.0, recentLitersPer100Km(r, window = 2, minIntervals = 2)!!, 1e-9)
        assertNull(recentLitersPer100Km(r, window = 2, minIntervals = 5))
    }

    @Test
    fun `it works on a real fuel report - a part fill counts into the interval that closes it`() {
        fun fill(id: Long, km: Int, ml: Int, full: Boolean = true) = LogEntry(
            id = id, type = LogType.FUEL, date = day.plusDays(id), odometerKm = km, amountMl = ml, fullTank = full,
            costCents = null, note = null, brand = null,
        )
        val entries = listOf(
            fill(1, 1_000, 15_000),
            fill(2, 1_100, 7_000),
            fill(3, 1_150, 1_000, full = false), // Teiltankung: ihr Liter gehoert in das Intervall bis zur naechsten vollen Tankung
            fill(4, 1_200, 7_000),
            fill(5, 1_300, 7_700),
            fill(6, 1_400, 7_700),
        )

        // Intervalle: 100 km/7,0 l, 100 km/(1,0 + 7,0) l, 100 km/7,7 l, 100 km/7,7 l -> (7,0 + 8,0 + 7,7 + 7,7) l auf 400 km = 7,6 l/100 km
        assertEquals(7.6, recentLitersPer100Km(fuelReport(entries))!!, 1e-9)
    }

    // --- learnedRangeKm ---

    @Test
    fun `the tank content divided by the consumption is the range`() {
        assertEquals(260, learnedRangeKm(capacityDl = 200, litersPer100Km = 7.7)) // 20,0 l / 7,7 * 100 = 259,7
    }

    @Test
    fun `the range is rounded to the nearest kilometer`() {
        assertEquals(260, learnedRangeKm(capacityDl = 200, litersPer100Km = 7.7))
        assertEquals(256, learnedRangeKm(capacityDl = 200, litersPer100Km = 7.8)) // 256,4
        assertEquals(50, learnedRangeKm(capacityDl = 50, litersPer100Km = 10.1)) // 49,5 -> 50
    }

    @Test
    fun `no tank content or no consumption means nothing to learn`() {
        assertNull(learnedRangeKm(capacityDl = 0, litersPer100Km = 7.7))
        assertNull(learnedRangeKm(capacityDl = 200, litersPer100Km = null))
        assertNull(learnedRangeKm(capacityDl = -10, litersPer100Km = 7.7))
    }

    @Test
    fun `a non-positive or unknown consumption is no basis`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull("$bad", learnedRangeKm(capacityDl = 200, litersPer100Km = bad))
        }
    }

    @Test
    fun `a result outside 50 to 1000 km is not shown`() {
        assertNull(learnedRangeKm(capacityDl = 30, litersPer100Km = 8.0)) // 3,0 l -> 37,5 km
        assertNull(learnedRangeKm(capacityDl = 600, litersPer100Km = 3.0)) // 60 l -> 2000 km
    }

    @Test
    fun `the limits themselves are accepted`() {
        assertEquals(50, learnedRangeKm(capacityDl = 50, litersPer100Km = 10.0)) // 5,0 l -> 50 km
        assertEquals(1000, learnedRangeKm(capacityDl = 600, litersPer100Km = 6.0)) // 60,0 l -> 1000 km
    }

    @Test
    fun `the limits of the tank content are 3,0 to 60,0 liters`() {
        assertEquals(30, FuelCapacityLimits.MIN_DL)
        assertEquals(600, FuelCapacityLimits.MAX_DL)
    }

    // --- effectiveRangeKm ---

    @Test
    fun `the learned range wins over the manual one`() {
        assertEquals(260, effectiveRangeKm(manualKm = 320, learnedKm = 260))
    }

    @Test
    fun `without a learned range the manual one counts`() {
        assertEquals(320, effectiveRangeKm(manualKm = 320, learnedKm = null))
    }

    @Test
    fun `without either there is no range`() {
        assertEquals(0, effectiveRangeKm(manualKm = 0, learnedKm = null))
    }

    @Test
    fun `the learning constants follow the design`() {
        assertEquals(10, RANGE_LEARNING_WINDOW)
        assertEquals(3, RANGE_LEARNING_MIN_INTERVALS)
    }

    // --- creditKm (Teilbetankung) ---

    @Test
    fun `the liters filled divided by the consumption are the kilometers credited`() {
        assertEquals(100f, creditKm(liters = 7.7, litersPer100Km = 7.7)!!, 1e-3f)
        assertEquals(80.52f, creditKm(liters = 6.2, litersPer100Km = 7.7)!!, 1e-2f)
    }

    @Test
    fun `no consumption or a useless one means no credit`() {
        assertNull(creditKm(liters = 6.2, litersPer100Km = null))
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull("$bad", creditKm(liters = 6.2, litersPer100Km = bad))
        }
    }

    @Test
    fun `no liters or useless ones mean no credit`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull("$bad", creditKm(liters = bad, litersPer100Km = 7.7))
        }
    }
}
