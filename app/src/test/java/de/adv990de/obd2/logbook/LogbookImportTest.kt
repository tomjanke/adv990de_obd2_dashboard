package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogbookImportTest {

    private val d = LocalDate.of(2026, 10, 1)

    private fun parsedOf(vararg entries: LogEntry, errors: List<RowError> = emptyList()) =
        ParsedLogbook(entries.mapIndexed { i, e -> ParsedRow(i + 2, e, validate(e, emptyList(), TEST_TODAY).filterIsInstance<LogIssue.Warning>()) }, errors)

    @Test
    fun `new entries are all inserted, the rejected rows are passed on`() {
        val rejected = RowError(7, listOf(ImportReason.BadValue("date")))
        val plan = planImport(parsedOf(logEntry(date = d, km = 1_000), logEntry(date = d.plusDays(1), km = 1_100), errors = listOf(rejected)), existing = emptyList())
        assertEquals(2, plan.toInsert.size)
        assertEquals(0, plan.duplicates)
        assertEquals(listOf(rejected), plan.rejected)
        assertEquals(0, plan.warnings)
    }

    @Test
    fun `an entry like one that exists is skipped - same kind, date, odometer and amount`() {
        val existing = logEntry(LogType.OIL, d, 1_000, 150, id = 5)
        val same = logEntry(LogType.OIL, d, 1_000, 150)
        val otherAmount = logEntry(LogType.OIL, d, 1_000, 200)
        val otherKm = logEntry(LogType.OIL, d, 1_001, 150)
        val otherDate = logEntry(LogType.OIL, d.plusDays(1), 1_000, 150)
        val plan = planImport(parsedOf(same, otherAmount, otherKm, otherDate), listOf(existing))
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(otherAmount, otherKm, otherDate), plan.toInsert)
    }

    @Test
    fun `chain care and service without an amount are duplicates by kind, date and odometer`() {
        val existing = logEntry(LogType.CHAIN_SMALL, d, 1_000, id = 1)
        val plan = planImport(parsedOf(logEntry(LogType.CHAIN_SMALL, d, 1_000), logEntry(LogType.CHAIN_LARGE, d, 1_000)), listOf(existing))
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(LogType.CHAIN_LARGE), plan.toInsert.map { it.type })
    }

    @Test
    fun `two tire changes on the same day and odometer with different brands are two entries`() {
        val front = logEntry(LogType.TIRE_CHANGE, d, 1_000, brand = "Michelin Anakee Wild")
        val rear = logEntry(LogType.TIRE_CHANGE, d, 1_000, brand = "Michelin Anakee Adventure")
        val plan = planImport(parsedOf(front, rear), emptyList())
        assertEquals(listOf(front, rear), plan.toInsert)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun `a tire change with the same brand as an existing one is a duplicate`() {
        val existing = logEntry(LogType.TIRE_CHANGE, d, 1_000, brand = "Michelin Anakee Wild", id = 3)
        val plan = planImport(parsedOf(logEntry(LogType.TIRE_CHANGE, d, 1_000, brand = "Michelin Anakee Wild")), listOf(existing))
        assertEquals(0, plan.toInsert.size)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `oil and coolant at the same stop and odometer are two entries, not duplicates`() {
        val plan = planImport(parsedOf(logEntry(LogType.OIL, d, 1_000, 150), logEntry(LogType.COOLANT, d, 1_000, 150)), emptyList())
        assertEquals(2, plan.toInsert.size)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun `a repeat inside the file is skipped, the first one is kept`() {
        val first = logEntry(LogType.OIL, d, 1_000, 150, note = "erste")
        val repeat = logEntry(LogType.OIL, d, 1_000, 150, note = "zweite")
        val plan = planImport(parsedOf(first, repeat), emptyList())
        assertEquals(listOf(first), plan.toInsert)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `importing a file again changes nothing`() {
        val entries = listOf(
            logEntry(LogType.FUEL, d, 1_000, 14_000, true, 2_700),
            logEntry(LogType.OIL, d.plusDays(3), 1_300, 150),
            logEntry(LogType.SERVICE_LARGE, d.plusDays(9), 1_800),
        )
        val file = logbookCsvHeader() + entries.joinToString("") { logbookCsvLine(it) }

        val first = planImport(parseLogbookCsv(file, TEST_TODAY), existing = emptyList())
        assertEquals(entries, first.toInsert)

        val again = planImport(parseLogbookCsv(file, TEST_TODAY), existing = first.toInsert.mapIndexed { i, e -> e.copy(id = i + 1L) })
        assertEquals(emptyList<LogEntry>(), again.toInsert)
        assertEquals(3, again.duplicates)
    }

    @Test
    fun `an entry with a price warning counts as a warning and is still imported`() {
        val cheap = logEntry(date = d, km = 1_000, costCents = 490)
        val plan = planImport(parsedOf(cheap, logEntry(date = d.plusDays(1), km = 1_200, costCents = 2_700)), emptyList())
        assertEquals(2, plan.toInsert.size)
        assertEquals(1, plan.warnings)
    }

    @Test
    fun `an entry whose odometer does not fit the existing ones counts as a warning`() {
        val existing = logEntry(LogType.CHAIN_SMALL, d, 5_000, id = 1)
        val backwards = logEntry(LogType.OIL, d.plusDays(2), 4_000, 150)
        val fine = logEntry(LogType.OIL, d.plusDays(3), 5_100, 150)
        val plan = planImport(parsedOf(backwards, fine), listOf(existing))
        assertEquals(2, plan.toInsert.size)
        assertEquals(1, plan.warnings)
    }

    @Test
    fun `an entry with two warnings counts once`() {
        val existing = logEntry(LogType.CHAIN_SMALL, d, 5_000, id = 1)
        val both = logEntry(LogType.FUEL, d.plusDays(2), 4_000, 10_000, true, 490)
        assertEquals(1, planImport(parsedOf(both), listOf(existing)).warnings)
    }

    @Test
    fun `an entry in conflict with an earlier line of the same file counts as a warning`() {
        val a = logEntry(LogType.CHAIN_SMALL, d, 5_000)
        val b = logEntry(LogType.CHAIN_LARGE, d.plusDays(2), 4_000)
        val plan = planImport(parsedOf(a, b), emptyList())
        assertTrue(plan.warnings >= 1)
    }

    @Test
    fun `an existing entry that conflicts is not counted, only what is imported`() {
        val badExisting = logEntry(LogType.CHAIN_SMALL, d, 9_000, id = 1)
        val laterExisting = logEntry(LogType.CHAIN_LARGE, d.plusDays(5), 1_000, id = 2)
        val plan = planImport(parsedOf(logEntry(LogType.SERVICE_SMALL, d.plusDays(10), 9_500)), listOf(badExisting, laterExisting))
        assertEquals(0, plan.warnings)
    }
}
