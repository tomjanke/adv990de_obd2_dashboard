package de.adv990de.obd2.tripimport

import de.adv990de.obd2.data.EXPORT_COLUMNS
import de.adv990de.obd2.data.SESSION_RETENTION_MS
import de.adv990de.obd2.data.csvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** [planTripImport]: was ein Import tun wuerde — neue Fahrten, schon vorhandene (Zeit beruehrt sich), abgelaufene (aelter als die Aufbewahrung). */
class TripImportPlanTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    /** Eine Fahrt der Datei: [rows] Messwerte im Abstand von einer Sekunde ab [start]. */
    private fun file(vararg trips: Triple<String, Long, Int>): ParsedTripFile {
        val text = csvRow(*EXPORT_COLUMNS) + trips.joinToString("") { (id, start, rows) ->
            (0 until rows).joinToString("") { csvRow((start + it * 1_000L).toString(), "010C", "1.0", "n", "ECU", id) }
        }
        return readTripCsv(StringReader(text), now)
    }

    private fun plan(parsed: ParsedTripFile, existing: List<SessionSpan> = emptyList()) = planTripImport(parsed, existing, now)

    @Test
    fun `trips that touch no trip in the app are new, in file order`() {
        val plan = plan(file(Triple("1", now - 10 * day, 5), Triple("2", now - 5 * day, 3)))

        assertEquals(listOf("1", "2"), plan.trips.map { it.sourceId })
        assertEquals(0, plan.duplicateTrips)
        assertEquals(0, plan.expiredTrips)
        assertEquals(8, plan.readingCount)
    }

    @Test
    fun `a trip inside an existing trip is a duplicate - a second import of the same file changes nothing`() {
        val start = now - 10 * day
        val existing = SessionSpan(start - 3_000, start + 4_000 + 2_000)

        val plan = plan(file(Triple("1", start, 5)), listOf(existing))

        assertTrue(plan.trips.isEmpty())
        assertEquals(1, plan.duplicateTrips)
    }

    @Test
    fun `a trip that only partly overlaps an existing one is a duplicate, on either side`() {
        val start = now - 10 * day // the trip runs from start to start + 4 s
        assertEquals("existing one ends inside", 1, plan(file(Triple("1", start, 5)), listOf(SessionSpan(start - hour, start + 1_000))).duplicateTrips)
        assertEquals("existing one starts inside", 1, plan(file(Triple("1", start, 5)), listOf(SessionSpan(start + 3_000, start + hour))).duplicateTrips)
        assertEquals("existing one lies inside", 1, plan(file(Triple("1", start, 5)), listOf(SessionSpan(start + 1_000, start + 2_000))).duplicateTrips)
    }

    @Test
    fun `touching at a single millisecond counts, one millisecond apart does not`() {
        val start = now - 10 * day
        val end = start + 4_000

        assertEquals(1, plan(file(Triple("1", start, 5)), listOf(SessionSpan(end, end + hour))).duplicateTrips)
        assertEquals(1, plan(file(Triple("1", start, 5)), listOf(SessionSpan(start - hour, start))).duplicateTrips)
        assertEquals(0, plan(file(Triple("1", start, 5)), listOf(SessionSpan(end + 1, end + hour))).duplicateTrips)
        assertEquals(0, plan(file(Triple("1", start, 5)), listOf(SessionSpan(start - hour, start - 1))).duplicateTrips)
    }

    @Test
    fun `only the trips that touch an existing one are skipped`() {
        val a = now - 20 * day
        val b = now - 10 * day
        val plan = plan(file(Triple("1", a, 5), Triple("2", b, 5)), listOf(SessionSpan(a, a + 4_000)))

        assertEquals(listOf("2"), plan.trips.map { it.sourceId })
        assertEquals(1, plan.duplicateTrips)
    }

    @Test
    fun `a trip that touches an earlier trip of the same file is a duplicate`() {
        val start = now - 10 * day
        val plan = plan(file(Triple("1", start, 5), Triple("2", start + 2_000, 5)))

        assertEquals(listOf("1"), plan.trips.map { it.sourceId })
        assertEquals("the first one stays", 1, plan.duplicateTrips)
    }

    @Test
    fun `a trip that touches only a skipped trip of the file is not a duplicate of it`() {
        val start = now - 10 * day
        // Trip 1 is new; 2 touches 1 and is skipped; 3 touches only 2's time (after 1 ended) and so is new.
        val plan = plan(file(Triple("1", start, 3), Triple("2", start + 1_000, 5), Triple("3", start + 4_500, 3)))

        assertEquals(listOf("1", "3"), plan.trips.map { it.sourceId })
        assertEquals(1, plan.duplicateTrips)
    }

    @Test
    fun `a trip older than the retention is expired and not imported - the app would delete it at the next start`() {
        val plan = plan(file(Triple("1", now - SESSION_RETENTION_MS - 1, 3), Triple("2", now - SESSION_RETENTION_MS, 3)))

        assertEquals("exactly at the limit is still kept", listOf("2"), plan.trips.map { it.sourceId })
        assertEquals(1, plan.expiredTrips)
    }

    @Test
    fun `the age of a trip is the age of its beginning`() {
        // begins 91 days ago and runs into the retention window: it would be deleted all the same
        val text = csvRow(*EXPORT_COLUMNS) +
            csvRow((now - 91 * day).toString(), "010C", "1.0", "n", "ECU", "1") + csvRow((now - day).toString(), "010C", "1.0", "n", "ECU", "1")

        val plan = plan(readTripCsv(StringReader(text), now))

        assertTrue(plan.trips.isEmpty())
        assertEquals(1, plan.expiredTrips)
    }

    @Test
    fun `an expired trip counts as expired even when it also overlaps an existing one`() {
        val start = now - 100 * day
        val plan = plan(file(Triple("1", start, 3)), listOf(SessionSpan(start, start + hour)))

        assertEquals(1, plan.expiredTrips)
        assertEquals(0, plan.duplicateTrips)
    }

    @Test
    fun `the retention is a parameter, the default is the one the app deletes by`() {
        val parsed = file(Triple("1", now - 10 * day, 3))

        assertEquals(1, planTripImport(parsed, emptyList(), now, retentionMs = 5 * day).expiredTrips)
        assertEquals(90L * day, SESSION_RETENTION_MS)
    }

    @Test
    fun `the rejected rows of the file go into the plan unchanged`() {
        val text = csvRow(*EXPORT_COLUMNS) + csvRow("x", "010C", "1.0", "n", "ECU", "1") + csvRow((now - day).toString(), "010C", "1.0", "n", "ECU", "1")
        val parsed = readTripCsv(StringReader(text), now)

        val plan = plan(parsed)

        assertEquals(listOf(RejectedRow(2, "timestamp")), plan.rejected)
        assertEquals(1, plan.rejectedCount)
        assertEquals(1, plan.trips.size)
    }

    @Test
    fun `an empty file gives an empty plan`() {
        val plan = plan(readTripCsv(StringReader(csvRow(*EXPORT_COLUMNS)), now), listOf(SessionSpan(0, 1)))

        assertTrue(plan.trips.isEmpty())
        assertEquals(0, plan.duplicateTrips)
        assertEquals(0, plan.expiredTrips)
        assertEquals(0, plan.readingCount)
    }
}
