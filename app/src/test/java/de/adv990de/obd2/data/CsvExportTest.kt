package de.adv990de.obd2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * QS C (DAT-06/07/09, CSV-Spalte): die reinen Teile des CSV-Exports — Quoting, Dateiname, Anlegen einer eindeutigen
 * Datei und das Aufraeumen alter Exporte. `exportToCsv` selbst steht in `SessionRepositoryTest`.
 */
class CsvExportTest {

    @get:Rule val tempFolder = TemporaryFolder()

    // ── csvField / csvRow (RFC 4180) ──────────────────────────────────────

    @Test
    fun `a plain field is written as it is`() {
        assertEquals("010C", csvField("010C"))
        assertEquals("RPM", csvField("RPM"))
        assertEquals("", csvField(""))
        assertEquals("Fuel Tank Level", csvField("Fuel Tank Level"))   // Leerzeichen allein verlangen kein Quoting
    }

    @Test
    fun `a field with a comma, a quote or a line break is quoted, quotes doubled`() {
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
        assertEquals("\"line1\nline2\"", csvField("line1\nline2"))
        assertEquals("\"cr\rhere\"", csvField("cr\rhere"))
    }

    @Test
    fun `csvRow joins the fields with commas and ends with a newline`() {
        assertEquals("1000,010C,1200.0,RPM\n", csvRow("1000", "010C", "1200.0", "RPM"))
        assertEquals("1,\"a,b\",x\n", csvRow("1", "a,b", "x"))
    }

    // ── exportFileName ────────────────────────────────────────────────────

    @Test
    fun `the file name carries the trip id and the export time in UTC, down to the millisecond`() {
        // 2026-10-05T14:30:05.123Z
        assertEquals("session_7_20261005T143005123Z.csv", exportFileName(sessionId = 7L, nowMillis = 1_791_210_605_123L))
    }

    @Test
    fun `two exports of the same trip at different times have different names`() {
        assertNotEquals(exportFileName(7L, 1_000L), exportFileName(7L, 1_001L))
    }

    // ── createExportFile ──────────────────────────────────────────────────

    @Test
    fun `createExportFile creates the file and the directory`() {
        val dir = File(tempFolder.root, "exports")   // existiert noch nicht

        val file = createExportFile(dir, sessionId = 7L, nowMillis = 1_000L)

        assertTrue(file.isFile)
        assertEquals(dir, file.parentFile)
    }

    @Test
    fun `two exports in the same millisecond never share a file (a double tap must not overwrite what is being shared)`() {
        val dir = File(tempFolder.root, "exports")

        val first = createExportFile(dir, 7L, 1_000L).also { it.writeText("first") }
        val second = createExportFile(dir, 7L, 1_000L)

        assertNotEquals(first, second)
        assertTrue(second.isFile)
        assertEquals("die erste Datei bleibt unberuehrt", "first", first.readText())
    }

    // ── deleteStaleExports ────────────────────────────────────────────────

    private fun exportFile(dir: File, name: String, ageMs: Long, now: Long): File =
        File(dir, name).also {
            dir.mkdirs()
            it.writeText("x")
            assertTrue(it.setLastModified(now - ageMs))
        }

    @Test
    fun `old exports are deleted, recent ones kept`() {
        val dir = File(tempFolder.root, "exports")
        val now = 10_000_000_000L
        val old = exportFile(dir, "session_1_old.csv", ageMs = EXPORT_MAX_AGE_MS + 1, now = now)
        val recent = exportFile(dir, "session_2_recent.csv", ageMs = EXPORT_MAX_AGE_MS - 1, now = now)

        val deleted = deleteStaleExports(dir, now)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `only our own export files are touched`() {
        val dir = File(tempFolder.root, "exports")
        val now = 10_000_000_000L
        val foreign = exportFile(dir, "notes.txt", ageMs = EXPORT_MAX_AGE_MS * 10, now = now)
        val otherCsv = exportFile(dir, "report.csv", ageMs = EXPORT_MAX_AGE_MS * 10, now = now)
        val subDir = File(dir, "session_9_dir.csv").also { it.mkdirs(); it.setLastModified(now - EXPORT_MAX_AGE_MS * 10) }

        assertEquals(0, deleteStaleExports(dir, now))

        assertTrue(foreign.exists())
        assertTrue(otherCsv.exists())
        assertTrue("ein Verzeichnis, das wie ein Export heisst, bleibt", subDir.isDirectory)
    }

    @Test
    fun `a missing export directory is not an error`() {
        assertEquals(0, deleteStaleExports(File(tempFolder.root, "never-created"), nowMillis = 1L))
    }

    @Test
    fun `the cut-off is exactly the maximum age - a file of exactly that age is kept`() {
        val dir = File(tempFolder.root, "exports")
        val now = 10_000_000_000L
        val edge = exportFile(dir, "session_3_edge.csv", ageMs = EXPORT_MAX_AGE_MS, now = now)

        assertEquals(0, deleteStaleExports(dir, now))
        assertTrue(edge.exists())
    }

    @Test
    fun `the default age is 24 hours`() {
        assertEquals(24L * 60 * 60 * 1000, EXPORT_MAX_AGE_MS)
    }

    // ── csvPidName ────────────────────────────────────────────────────────

    @Test
    fun `an OBD PID is named by the registry`() {
        assertEquals("RPM", csvPidName("010C"))
        assertEquals("Speed", csvPidName("010D"))
        assertEquals("Coolant Temp", csvPidName("0105"))
    }

    @Test
    fun `the derived gear is named Gear`() {
        assertEquals("Gear", csvPidName("GEAR"))
    }

    @Test
    fun `the derived oil bar is named Oil bar`() {
        assertEquals("Oil bar", csvPidName("OIL_BAR"))
    }

    @Test
    fun `an unknown PID has an empty name`() {
        assertEquals("", csvPidName("01FF"))
        assertEquals("", csvPidName(""))
    }

    // ── EXPORT_QUERY_SQL ──────────────────────────────────────────────────

    @Test
    fun `the export query reads one trip in recording order, without a sort by timestamp`() {
        assertTrue(EXPORT_QUERY_SQL.contains("WHERE sessionId = ?"))
        assertTrue("die Herkunft gehoert zum Export: $EXPORT_QUERY_SQL", EXPORT_QUERY_SQL.contains("source"))
        assertTrue("ORDER BY id (Index-Reihenfolge, kein Sortierlauf): $EXPORT_QUERY_SQL", EXPORT_QUERY_SQL.contains("ORDER BY id"))
        assertFalse("ORDER BY timestamp zwaenge SQLite zum Sortieren aller Zeilen (DAT-09)", EXPORT_QUERY_SQL.contains("ORDER BY timestamp"))
    }
}

