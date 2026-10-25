package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class LogValidationTest {

    private fun issues(entry: LogEntry, others: List<LogEntry> = emptyList()) = validate(entry, others, TEST_TODAY)
    private fun errors(entry: LogEntry, others: List<LogEntry> = emptyList()) = issues(entry, others).filterIsInstance<LogIssue.Error>()
    private fun warnings(entry: LogEntry, others: List<LogEntry> = emptyList()) = issues(entry, others).filterIsInstance<LogIssue.Warning>()

    // ── Fehler ────────────────────────────────────────────────────────────────

    @Test
    fun `a plain valid entry has no issues`() {
        assertEquals(emptyList<LogIssue>(), issues(logEntry()))
        assertEquals(emptyList<LogIssue>(), issues(logEntry(type = LogType.OIL, amountMl = 150)))
        assertEquals(emptyList<LogIssue>(), issues(logEntry(type = LogType.SERVICE_LARGE)))
        assertEquals(emptyList<LogIssue>(), issues(logEntry(type = LogType.TIRE_CHANGE, brand = "Metzeler Karoo 4")))
        assertEquals(emptyList<LogIssue>(), issues(logEntry(type = LogType.CHAIN_KIT_CHANGE)))
    }

    @Test
    fun `the odometer must be 0 to 999999`() {
        assertEquals(listOf(LogIssue.OdometerRange), errors(logEntry(km = -1)))
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(km = 0)))
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(km = 999_999)))
        assertEquals(listOf(LogIssue.OdometerRange), errors(logEntry(km = 1_000_000)))
    }

    @Test
    fun `fuel, oil and coolant need an amount`() {
        for (type in listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT)) {
            assertEquals("$type", listOf(LogIssue.AmountMissing), errors(logEntry(type = type, amountMl = null)))
        }
    }

    @Test
    fun `the amount must be above zero and at most the maximum of its type`() {
        for (type in listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT)) {
            assertEquals("$type 0", listOf(LogIssue.AmountRange), errors(logEntry(type = type, amountMl = 0)))
            assertEquals("$type -5", listOf(LogIssue.AmountRange), errors(logEntry(type = type, amountMl = -5)))
            assertEquals("$type 1", emptyList<LogIssue.Error>(), errors(logEntry(type = type, amountMl = 1)))
            assertEquals("$type max", emptyList<LogIssue.Error>(), errors(logEntry(type = type, amountMl = type.maxAmountMl)))
            assertEquals("$type max+1", listOf(LogIssue.AmountRange), errors(logEntry(type = type, amountMl = type.maxAmountMl + 1)))
        }
    }

    @Test
    fun `chain care, service and the two changes ignore the amount`() {
        for (type in LogType.entries.filter { !it.hasAmount }) {
            assertEquals("$type none", emptyList<LogIssue>(), issues(logEntry(type = type, amountMl = null)))
            assertEquals("$type some", emptyList<LogIssue>(), issues(logEntry(type = type, amountMl = 999_999)))
        }
    }

    @Test
    fun `today is allowed, tomorrow is not`() {
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(date = TEST_TODAY)))
        assertEquals(listOf(LogIssue.DateInFuture), errors(logEntry(date = TEST_TODAY.plusDays(1))))
    }

    @Test
    fun `the cost of a fill must be between 0,01 and 999,99 euros`() {
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(costCents = 0)))
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(costCents = -1)))
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(costCents = 1)))
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(costCents = 99_999)))
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(costCents = 100_000)))
    }

    @Test
    fun `only a fill has a cost`() {
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(type = LogType.OIL, amountMl = 150, costCents = 500)))
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(type = LogType.SERVICE_SMALL, costCents = 500)))
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(type = LogType.TIRE_CHANGE, costCents = 500)))
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(type = LogType.CHAIN_KIT_CHANGE, costCents = 500)))
    }

    @Test
    fun `a tire change needs a brand, blank does not count`() {
        assertEquals(listOf(LogIssue.BrandMissing), errors(logEntry(type = LogType.TIRE_CHANGE, brand = null)))
        assertEquals(listOf(LogIssue.BrandMissing), errors(logEntry(type = LogType.TIRE_CHANGE, brand = "")))
        assertEquals(listOf(LogIssue.BrandMissing), errors(logEntry(type = LogType.TIRE_CHANGE, brand = "   ")))
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(type = LogType.TIRE_CHANGE, brand = "x")))
    }

    @Test
    fun `the brand may have 80 characters`() {
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(type = LogType.TIRE_CHANGE, brand = "x".repeat(80))))
        assertEquals(listOf(LogIssue.BrandTooLong), errors(logEntry(type = LogType.TIRE_CHANGE, brand = "x".repeat(81))))
    }

    @Test
    fun `only a tire change has a brand`() {
        for (type in LogType.entries.filter { !it.hasBrand }) {
            val amount = if (type.hasAmount) 150 else null
            assertEquals("$type", listOf(LogIssue.BrandNotAllowed), errors(logEntry(type = type, amountMl = amount, brand = "Michelin")))
            assertEquals("$type none", emptyList<LogIssue.Error>(), errors(logEntry(type = type, amountMl = amount, brand = null)))
        }
    }

    @Test
    fun `the note may have 200 characters`() {
        assertEquals(emptyList<LogIssue.Error>(), errors(logEntry(note = "x".repeat(200))))
        assertEquals(listOf(LogIssue.NoteTooLong), errors(logEntry(note = "x".repeat(201))))
    }

    @Test
    fun `several errors are all reported, and with an error there are no warnings`() {
        val earlier = logEntry(date = TEST_TODAY.minusDays(5), km = 9_000)
        val bad = logEntry(km = 1_000_000, amountMl = null, note = "x".repeat(201))
        val found = issues(bad, listOf(earlier))
        assertEquals(setOf<LogIssue>(LogIssue.OdometerRange, LogIssue.AmountMissing, LogIssue.NoteTooLong), found.toSet())
        assertTrue(found.none { it is LogIssue.Warning })
    }

    // ── Warnung: Kilometerstand und Datum ─────────────────────────────────────

    @Test
    fun `an earlier entry with a higher odometer is a warning`() {
        val earlier = logEntry(type = LogType.OIL, date = TEST_TODAY.minusDays(10), km = 5_000, amountMl = 100)
        val found = warnings(logEntry(date = TEST_TODAY.minusDays(8), km = 4_000), listOf(earlier))
        assertEquals(listOf<LogIssue>(LogIssue.OdometerOutOfOrder(earlier)), found)
    }

    @Test
    fun `a later entry with a lower odometer is a warning`() {
        val later = logEntry(type = LogType.CHAIN_SMALL, date = TEST_TODAY.minusDays(2), km = 3_000)
        val found = warnings(logEntry(date = TEST_TODAY.minusDays(8), km = 4_000), listOf(later))
        assertEquals(listOf<LogIssue>(LogIssue.OdometerOutOfOrder(later)), found)
    }

    @Test
    fun `entries of the same day are not compared, equal odometers are fine`() {
        val sameDay = logEntry(date = TEST_TODAY, km = 5_000)
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(date = TEST_TODAY, km = 4_000), listOf(sameDay)))
        val equal = logEntry(date = TEST_TODAY.minusDays(3), km = 4_000)
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(date = TEST_TODAY, km = 4_000), listOf(equal)))
    }

    @Test
    fun `a consistent history gives no warning`() {
        val others = listOf(
            logEntry(type = LogType.SERVICE_SMALL, date = TEST_TODAY.minusDays(20), km = 1_000),
            logEntry(type = LogType.CHAIN_LARGE, date = TEST_TODAY.minusDays(1), km = 3_000),
        )
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(date = TEST_TODAY.minusDays(10), km = 2_000), others))
    }

    // ── Warnung: Preis je Liter ───────────────────────────────────────────────

    @Test
    fun `a price per litre outside 0,50 to 5,00 euros is a warning, the limits themselves are fine`() {
        // 10 l: 4,90 EUR = 0,49 EUR/l (zu billig), 5,00 = 0,50 (Grenze), 50,00 = 5,00 (Grenze), 50,10 = 5,01 (zu teuer)
        assertEquals(listOf<LogIssue>(LogIssue.PriceImplausible(49)), warnings(logEntry(costCents = 490)))
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(costCents = 500)))
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(costCents = 5_000)))
        assertEquals(listOf<LogIssue>(LogIssue.PriceImplausible(501)), warnings(logEntry(costCents = 5_010)))
    }

    @Test
    fun `a missing comma in the cost is caught as a cost error`() {
        // 12,80 l fuer 29440 EUR statt 29,44 EUR (fehlendes Komma im Export): ueber 999,99 EUR, also ein Fehler
        assertEquals(listOf(LogIssue.CostRange), errors(logEntry(amountMl = 12_800, costCents = 2_944_000)))
    }

    @Test
    fun `no cost, no price warning`() {
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(costCents = null)))
    }

    // ── Warnung: Verbrauch ────────────────────────────────────────────────────

    private fun previousFull(km: Int = 1_000) = logEntry(date = TEST_TODAY.minusDays(7), km = km)

    @Test
    fun `a consumption below 2 or above 20 l per 100 km is a warning, the limits themselves are fine`() {
        val prev = listOf(previousFull())
        // 100 km seit der letzten vollen Tankung
        assertEquals(listOf<LogIssue>(LogIssue.ConsumptionImplausible(1.999)), warnings(logEntry(km = 1_100, amountMl = 1_999), prev).map(::rounded))
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 2_000), prev))
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 20_000), prev))
        assertEquals(listOf<LogIssue>(LogIssue.ConsumptionImplausible(20.1)), warnings(logEntry(km = 1_100, amountMl = 20_100), prev).map(::rounded))
    }

    private fun rounded(issue: LogIssue.Warning): LogIssue =
        if (issue is LogIssue.ConsumptionImplausible) LogIssue.ConsumptionImplausible(Math.round(issue.litersPer100Km * 1000) / 1000.0) else issue

    @Test
    fun `partial fills in between add their litres to the interval`() {
        val partial = logEntry(date = TEST_TODAY.minusDays(3), km = 1_050, amountMl = 15_000, fullTank = false)
        // 6 l allein waeren 6,0 l/100 km, mit den 15 l der Teiltankung sind es 21,0
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 6_000), listOf(previousFull())))
        val found = warnings(logEntry(km = 1_100, amountMl = 6_000), listOf(previousFull(), partial))
        assertEquals(listOf<LogIssue>(LogIssue.ConsumptionImplausible(21.0)), found.map(::rounded))
    }

    @Test
    fun `a partial fill before the previous full fill does not count, nor does an entry at the same odometer`() {
        val oldPartial = logEntry(date = TEST_TODAY.minusDays(9), km = 900, amountMl = 15_000, fullTank = false)
        // die 15 l der alten Teiltankung gehoeren zum Intervall davor: 6 l auf 100 km = 6,0
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 6_000), listOf(oldPartial, previousFull())))
        // eine zweite Tankung beim selben Stand ist kein Bezug (0 km Differenz)
        val sameStand = logEntry(date = TEST_TODAY.minusDays(1), km = 1_100, amountMl = 7_000)
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 6_000), listOf(previousFull(), sameStand)))
    }

    @Test
    fun `a partial fill is never judged, and without a full predecessor there is nothing to judge`() {
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 90_000, fullTank = false), listOf(previousFull())))
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 90_000), emptyList()))
        val onlyPartial = logEntry(date = TEST_TODAY.minusDays(3), km = 1_000, amountMl = 10_000, fullTank = false)
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 90_000), listOf(onlyPartial)))
    }

    @Test
    fun `oil entries and entries at a higher odometer are not the predecessor`() {
        val oil = logEntry(type = LogType.OIL, date = TEST_TODAY.minusDays(3), km = 1_050, amountMl = 200)
        val higher = logEntry(date = TEST_TODAY.minusDays(1), km = 1_500)
        // Vorgaenger ist nur die volle Tankung bei 1000 km: 6 l auf 100 km = 6,0 (keine Warnung)
        assertEquals(emptyList<LogIssue.Warning>(), warnings(logEntry(km = 1_100, amountMl = 6_000), listOf(previousFull(), oil, higher)).filterNot { it is LogIssue.OdometerOutOfOrder })
    }

    @Test
    fun `a fill without the full flag counts as a full one`() {
        val unknown = logEntry(km = 1_100, amountMl = 90_000, fullTank = null)
        val found = warnings(unknown, listOf(previousFull()))
        assertTrue(found.any { it is LogIssue.ConsumptionImplausible })
    }

    // ── Gegenprobe der beiden Wege zur Reihenfolge-Warnung ────────────────────

    @Test
    fun `the bulk check agrees with the check of a single entry`() {
        val random = Random(42)
        repeat(200) {
            val n = random.nextInt(1, 25)
            val entries = List(n) {
                logEntry(date = LocalDate.of(2026, 1, 1).plusDays(random.nextLong(0, 12)), km = random.nextInt(0, 30) * 100, id = it.toLong() + 1)
            }
            val bulk = findOutOfOrder(entries)
            entries.forEachIndexed { i, e ->
                val single = validate(e, entries - e, TEST_TODAY).any { it is LogIssue.OdometerOutOfOrder }
                assertEquals("round $it entry $i", single, bulk[i])
            }
        }
    }

    @Test
    fun `the bulk check marks nothing in an ordered history and the odd one out otherwise`() {
        val ordered = List(5) { logEntry(date = TEST_TODAY.minusDays(10L - it), km = 1_000 + it * 100) }
        assertFalse(findOutOfOrder(ordered).any { it })
        val broken = ordered + logEntry(date = TEST_TODAY.minusDays(7), km = 9_000)
        assertTrue(findOutOfOrder(broken)[5])
    }
}
