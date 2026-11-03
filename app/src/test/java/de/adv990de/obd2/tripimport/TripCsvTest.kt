package de.adv990de.obd2.tripimport

import de.adv990de.obd2.data.CANCELLATION_CHECK_ROWS
import de.adv990de.obd2.data.EXPORT_COLUMNS
import de.adv990de.obd2.data.csvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** [readTripCsv]: die Datei des Exports lesen — Fahrten nach Spaltennamen, jede Zeile geprueft, grosse Dateien im Strom. */
class TripCsvTest {

    private val now = 1_800_000_000_000L
    private val t0 = 1_790_000_000_000L

    private val header = csvRow(*EXPORT_COLUMNS)

    /** Eine Zeile im Format des Exports (`timestamp,pid,value,name,source,session`). */
    private fun row(timestamp: Long, pid: String, value: String, source: String, session: String) =
        csvRow(timestamp.toString(), pid, value, "name", source, session)

    private fun read(text: String, maxRows: Int = MAX_TRIP_IMPORT_ROWS, maxPids: Int = MAX_TRIP_IMPORT_PIDS, check: () -> Unit = {}) =
        readTripCsv(StringReader(text), now, maxRows, maxPids, check)

    private fun kindOf(text: String, maxRows: Int = MAX_TRIP_IMPORT_ROWS, maxPids: Int = MAX_TRIP_IMPORT_PIDS): TripFileException.Kind =
        assertThrows(TripFileException::class.java) { read(text, maxRows, maxPids) }.kind

    @Test
    fun `the columns the import reads are the ones the export writes`() {
        val written = EXPORT_COLUMNS.toList()

        for (column in listOf(TRIP_COLUMN_TIMESTAMP, TRIP_COLUMN_PID, TRIP_COLUMN_VALUE, TRIP_COLUMN_SOURCE, TRIP_COLUMN_SESSION)) {
            assertTrue("the export no longer writes the column $column the import reads", column in written)
        }
    }

    // ── Fahrten und Zeilen ────────────────────────────────────────────────────

    @Test
    fun `rows are grouped into trips by the session column, in file order`() {
        val file = read(
            header +
                row(t0, "010C", "1612.0", "ECU", "7") + row(t0 + 1_000, "0105", "79.0", "ECU", "7") +
                row(t0 + 5_000, "010C", "800.0", "ECU", "9"),
        )

        assertEquals(listOf("7", "9"), file.trips.map { it.sourceId })
        assertEquals(listOf(2, 1), file.trips.map { it.rows.size })
        assertEquals(3, file.rowCount)
        assertEquals(0, file.rejectedCount)
    }

    @Test
    fun `a trip begins at its smallest and ends at its largest timestamp - the order of the rows does not matter`() {
        val file = read(header + row(t0 + 4_000, "010C", "1.0", "ECU", "1") + row(t0, "010C", "2.0", "ECU", "1") + row(t0 + 9_000, "010C", "3.0", "ECU", "1"))

        val trip = file.trips.single()
        assertEquals(t0, trip.startMs)
        assertEquals(t0 + 9_000, trip.endMs)
    }

    @Test
    fun `every row keeps its timestamp, pid, value and source in the order of the file`() {
        val file = read(
            header +
                row(t0, "010C", "1612.0", "ECU", "1") + row(t0 + 1, "010D", "52.5", "GPS", "1") +
                row(t0 + 2, "GEAR", "-1.0", "DERIVED", "1") + row(t0 + 3, "0105", "79.0", "", "1"),
        )

        val rows = file.trips.single().rows
        assertEquals(listOf(t0, t0 + 1, t0 + 2, t0 + 3), (0 until rows.size).map { rows.timestamp(it) })
        assertEquals(listOf("010C", "010D", "GEAR", "0105"), (0 until rows.size).map { rows.pid(it) })
        assertEquals(listOf(1612.0f, 52.5f, -1.0f, 79.0f), (0 until rows.size).map { rows.value(it) })
        assertEquals("an empty source is unknown (null), not a guess", listOf("ECU", "GPS", "DERIVED", null), (0 until rows.size).map { rows.source(it) })
    }

    @Test
    fun `values as Float toString writes them are read back, also the exponent form`() {
        val file = read(header + row(t0, "A", "1.0E-4", "ECU", "1") + row(t0 + 1, "A", "1.2345678E7", "ECU", "1") + row(t0 + 2, "A", "-0.0", "ECU", "1"))

        val rows = file.trips.single().rows
        assertEquals(1.0E-4f, rows.value(0), 0f)
        assertEquals(1.2345678E7f, rows.value(1), 0f)
        assertEquals(-0.0f, rows.value(2), 0f)
    }

    @Test
    fun `the same pid in several trips is one name in the table`() {
        val file = read(header + row(t0, "010C", "1.0", "ECU", "1") + row(t0 + 99_000, "010C", "2.0", "ECU", "2"))

        assertEquals("010C", file.trips[0].rows.pid(0))
        assertEquals("010C", file.trips[1].rows.pid(0))
    }

    // ── Aeltere und freie Formate ─────────────────────────────────────────────

    @Test
    fun `a file without the session column - the format before 1_34_0 - is one trip`() {
        val file = read("timestamp,pid,value,name,source\n$t0,010C,1612.0,Engine RPM,ECU\n${t0 + 1000},0105,79.0,Coolant,ECU\n")

        assertEquals(1, file.trips.size)
        assertEquals("", file.trips.single().sourceId)
        assertEquals(2, file.trips.single().rows.size)
    }

    @Test
    fun `a file without session and source - the format before 1_25_0 - reads with unknown sources`() {
        val file = read("timestamp,pid,value\n$t0,010C,1612.0\n")

        assertNull(file.trips.single().rows.source(0))
    }

    @Test
    fun `columns are found by name in any order, extra columns are ignored, names are trimmed`() {
        val file = read(" session , value,extra,pid ,timestamp\n3,1612.0,x,010C,$t0\n")

        val trip = file.trips.single()
        assertEquals("3", trip.sourceId)
        assertEquals("010C", trip.rows.pid(0))
        assertEquals(1612.0f, trip.rows.value(0), 0f)
        assertEquals(t0, trip.rows.timestamp(0))
    }

    @Test
    fun `the first of two columns with the same name counts`() {
        val file = read("timestamp,pid,value,value\n$t0,010C,1.0,2.0\n")

        assertEquals(1.0f, file.trips.single().rows.value(0), 0f)
    }

    @Test
    fun `quoted fields, a byte order mark, CRLF and blank lines are read`() {
        val file = read("﻿" + header.replace("\n", "\r\n") + "\r\n" + "$t0,\"010C\",\"1612.0\",\"Engine, RPM\",ECU,1\r\n\r\n")

        assertEquals(1, file.trips.single().rows.size)
        assertEquals(0, file.rejectedCount)
    }

    // ── Zeilen ablehnen ───────────────────────────────────────────────────────

    private fun rejectionOf(vararg fields: String, columns: String = "timestamp,pid,value,name,source,session"): RejectedRow? {
        val file = read(columns + "\n" + fields.joinToString(",") + "\n")
        assertEquals("the row is rejected, so no trip", emptyList<ParsedTrip>(), file.trips)
        return file.rejected.singleOrNull()
    }

    @Test
    fun `a timestamp that is not a number, too early or more than a day in the future rejects the row`() {
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf("abc", "010C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf("", "010C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf("0", "010C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf("946684799999", "010C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf((now + 24L * 60 * 60 * 1000 + 1).toString(), "010C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "timestamp"), rejectionOf("1790000000000.5", "010C", "1.0", "n", "ECU", "1"))
    }

    @Test
    fun `the first and the last plausible timestamp are accepted`() {
        val file = read(header + row(946_684_800_000L, "A", "1.0", "ECU", "1") + row(now + 24L * 60 * 60 * 1000, "A", "1.0", "ECU", "1"))

        assertEquals(2, file.rowCount)
        assertEquals(0, file.rejectedCount)
    }

    @Test
    fun `a pid that is empty, too long or has special characters rejects the row`() {
        assertEquals(RejectedRow(2, "pid"), rejectionOf("$t0", "", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "pid"), rejectionOf("$t0", "a".repeat(33), "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "pid"), rejectionOf("$t0", "01 0C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "pid"), rejectionOf("$t0", "01-0C", "1.0", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "pid"), rejectionOf("$t0", "Ü", "1.0", "n", "ECU", "1"))
    }

    @Test
    fun `a pid of 32 characters, with letters digits and underscore, is accepted`() {
        val file = read(header + row(t0, "A".repeat(32), "1.0", "ECU", "1") + row(t0 + 1, "Oil_bar_1", "1.0", "ECU", "1"))

        assertEquals(2, file.rowCount)
    }

    @Test
    fun `a value that is not a finite number rejects the row`() {
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", "abc", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", "", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", "NaN", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", "Infinity", "n", "ECU", "1"))
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", "-Infinity", "n", "ECU", "1"))
    }

    @Test
    fun `a source that is none of ECU, GPS, DERIVED or empty rejects the row`() {
        assertEquals(RejectedRow(2, "source"), rejectionOf("$t0", "010C", "1.0", "n", "ecu", "1"))
        assertEquals(RejectedRow(2, "source"), rejectionOf("$t0", "010C", "1.0", "n", "OBD", "1"))
    }

    @Test
    fun `a blank session rejects the row when the file has the column`() {
        assertEquals(RejectedRow(2, "session"), rejectionOf("$t0", "010C", "1.0", "n", "ECU", ""))
        assertEquals(RejectedRow(2, "session"), rejectionOf("$t0", "010C", "1.0", "n", "ECU", "  "))
    }

    @Test
    fun `a row with fewer fields than the header is rejected at the first missing column`() {
        assertEquals(RejectedRow(2, "value"), rejectionOf("$t0", "010C", columns = "timestamp,pid,value"))
        assertEquals(RejectedRow(2, "session"), rejectionOf("$t0", "010C", "1.0", columns = "timestamp,pid,value,session"))
    }

    @Test
    fun `only the first bad column is named and the line is the line of the file`() {
        val file = read(header + row(t0, "010C", "1.0", "ECU", "1") + row(t0 + 1, "", "abc", "BAD", "") + row(t0 + 2, "010C", "2.0", "ECU", "1"))

        assertEquals(listOf(RejectedRow(3, "pid")), file.rejected)
        assertEquals("the good rows around the bad one stay", 2, file.trips.single().rows.size)
    }

    @Test
    fun `the line of a rejected row counts line breaks inside quoted fields`() {
        val text = header + "$t0,010C,1.0,\"two\nlines\",ECU,1\n" + "x,010C,1.0,n,ECU,1\n"

        assertEquals(RejectedRow(4, "timestamp"), read(text).rejected.single())
    }

    @Test
    fun `a rejected row does not start a trip`() {
        val file = read(header + row(t0, "010C", "abc", "ECU", "5") + row(t0, "010C", "1.0", "ECU", "6"))

        assertEquals(listOf("6"), file.trips.map { it.sourceId })
    }

    @Test
    fun `blank lines are skipped and neither counted as rows nor rejected`() {
        val file = read(header + "\n" + row(t0, "A", "1.0", "ECU", "1") + ",,,,,\n" + "   \n")

        assertEquals(1, file.rowCount)
        assertEquals(0, file.rejectedCount)
    }

    @Test
    fun `all rejected rows are counted, but only the first hundred are kept with their lines`() {
        val bad = (1..150).joinToString("") { "x,010C,1.0,n,ECU,1\n" }

        val file = read(header + bad)

        assertEquals(150, file.rejectedCount)
        assertEquals(MAX_KEPT_REJECTED_ROWS, file.rejected.size)
        assertEquals(RejectedRow(2, "timestamp"), file.rejected.first())
        assertEquals(RejectedRow(101, "timestamp"), file.rejected.last())
        assertTrue(file.trips.isEmpty())
    }

    @Test
    fun `a file whose rows are all rejected is a valid file without trips - the preview shows why`() {
        val file = read(header + "x,010C,1.0,n,ECU,1\n")

        assertTrue(file.trips.isEmpty())
        assertEquals(1, file.rejectedCount)
        assertEquals(0, file.rowCount)
    }

    @Test
    fun `a header without rows is a valid file without trips`() {
        val file = read(header)

        assertTrue(file.trips.isEmpty())
        assertEquals(0, file.rejectedCount)
    }

    // ── Dateien, die kein Export sind ─────────────────────────────────────────

    @Test
    fun `an empty file is not an export`() {
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf(""))
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf("\n\n  \n"))
    }

    @Test
    fun `a missing required column is not an export - each of the three`() {
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf("pid,value\n010C,1.0\n"))
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf("timestamp,value\n$t0,1.0\n"))
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf("timestamp,pid\n$t0,010C\n"))
    }

    @Test
    fun `the logbook export is not a trip export`() {
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf("date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n2026-10-02,FUEL,40362,14370,1,27.43,,\n"))
    }

    @Test
    fun `a quote that is never closed is an error`() {
        assertThrows(IllegalArgumentException::class.java) { read(header + "$t0,\"010C,1.0,n,ECU,1\n") }
    }

    @Test
    fun `more rows than the limit are refused, the limit itself is fine`() {
        val three = header + row(t0, "A", "1.0", "ECU", "1") + row(t0 + 1, "A", "1.0", "ECU", "1") + row(t0 + 2, "A", "1.0", "ECU", "1")

        assertEquals(3, read(three, maxRows = 3).rowCount)
        assertEquals(TripFileException.Kind.TOO_MANY_ROWS, kindOf(three, maxRows = 2))
    }

    @Test
    fun `rejected rows count against the row limit, blank lines do not`() {
        val text = header + "x,A,1.0,n,ECU,1\n" + "\n" + row(t0, "A", "1.0", "ECU", "1")

        assertEquals(1, read(text, maxRows = 2).rowCount)
        assertEquals(TripFileException.Kind.TOO_MANY_ROWS, kindOf(text, maxRows = 1))
    }

    @Test
    fun `more different pids than the limit are not an export`() {
        val text = header + row(t0, "A", "1.0", "ECU", "1") + row(t0 + 1, "B", "1.0", "ECU", "1") + row(t0 + 2, "C", "1.0", "ECU", "1")

        assertEquals(3, read(text, maxPids = 3).rowCount)
        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, kindOf(text, maxPids = 2))
    }

    @Test
    fun `a pid of a rejected row does not use up the table`() {
        val text = header + row(t0, "A", "abc", "ECU", "1") + row(t0 + 1, "B", "1.0", "ECU", "1")

        assertEquals(1, read(text, maxPids = 1).rowCount)
    }

    @Test
    fun `a trip whose rows are not together is refused - the export never writes it so`() {
        val text = header + row(t0, "A", "1.0", "ECU", "1") + row(t0 + 1, "A", "1.0", "ECU", "2") + row(t0 + 2, "A", "1.0", "ECU", "1")

        assertEquals(TripFileException.Kind.SPLIT_TRIP, kindOf(text))
    }

    @Test
    fun `a rejected row between the rows of a trip does not split it`() {
        val file = read(header + row(t0, "A", "1.0", "ECU", "1") + row(t0 + 1, "A", "abc", "ECU", "2") + row(t0 + 2, "A", "1.0", "ECU", "1"))

        assertEquals(listOf("1"), file.trips.map { it.sourceId })
        assertEquals(2, file.trips.single().rows.size)
    }

    // ── Abbruch und Groesse ───────────────────────────────────────────────────

    @Test
    fun `the check for cancellation runs every few thousand rows and its exception ends the reading`() {
        val rows = CANCELLATION_CHECK_ROWS * 3 + 5
        val text = header + (0 until rows).joinToString("") { row(t0 + it, "A", "1.0", "ECU", "1") }
        var calls = 0

        read(text) { calls++ }

        assertEquals(3, calls)
        class Stop : RuntimeException()
        assertThrows(Stop::class.java) { read(text) { throw Stop() } }
    }

    @Test
    fun `blank lines count for the check, so a file of line breaks can be cancelled`() {
        val text = header + "\n".repeat(CANCELLATION_CHECK_ROWS * 2 + 1)
        var calls = 0

        read(text) { calls++ }

        assertEquals(2, calls)
    }

    @Test
    fun `a field that is far too long is an error and not a giant string in memory`() {
        assertThrows(IllegalArgumentException::class.java) { read(header + "x".repeat(1_000_000)) }
    }

    @Test
    fun `a file of many rows is read and keeps its order and values`() {
        val rows = 100_000
        val text = header + (0 until rows).joinToString("") { row(t0 + it, if (it % 2 == 0) "010C" else "0105", it.toString() + ".0", "ECU", (it / 40_000).toString()) }

        val file = read(text)

        assertEquals(listOf("0", "1", "2"), file.trips.map { it.sourceId })
        assertEquals(listOf(40_000, 40_000, 20_000), file.trips.map { it.rows.size })
        assertEquals(rows, file.rowCount)
        val last = file.trips.last().rows
        assertEquals(t0 + rows - 1, last.timestamp(last.size - 1))
        assertEquals((rows - 1).toFloat(), last.value(last.size - 1), 0f)
        assertEquals("0105", last.pid(last.size - 1))
    }
}
