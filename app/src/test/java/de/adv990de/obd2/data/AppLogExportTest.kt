package de.adv990de.obd2.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Die reinen Teile des App-Log-Exports (Spalten, Abfrage, Dateiname) und dass die Aufraeumregel fuer alte Exporte auch seine Dateien trifft.
 * Das Schreiben selbst steht in [AppLogExporterTest].
 */
class AppLogExportTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `the columns are timestamp, level and message in that order`() {
        assertArrayEquals(arrayOf("timestamp", "level", "message"), APP_LOG_EXPORT_COLUMNS)
    }

    @Test
    fun `the query reads the rows after the given id in the order they were written, without a sort`() {
        // `ORDER BY id` ist die Reihenfolge des Primaerschluessels — SQLite braucht keinen Sortierlauf (der Geraete-Test prueft den Abfrageplan).
        assertEquals("SELECT timestamp, level, message FROM app_log WHERE id > ? ORDER BY id ASC", APP_LOG_EXPORT_QUERY_SQL)
    }

    @Test
    fun `the file name carries the export time in UTC down to the millisecond`() {
        // 2026-10-05T14:30:05.123Z
        assertEquals("applog_20261005T143005123Z.csv", appLogExportFileName(nowMillis = 1_791_210_605_123L))
    }

    @Test
    fun `two exports at different times have different names, and the name differs from a trip export`() {
        assertNotEquals(appLogExportFileName(1_000L), appLogExportFileName(1_001L))
        assertNotEquals(exportAllFileName(1_000L), appLogExportFileName(1_000L))
    }

    @Test
    fun `an old app log export is cleaned up like a trip export, a recent one and foreign files are kept`() {
        val dir = File(tempFolder.root, "exports").also { it.mkdirs() }
        val now = 10_000_000_000L
        fun file(name: String, ageMs: Long) = File(dir, name).also { it.writeText("x"); assertTrue(it.setLastModified(now - ageMs)) }
        val old = file("applog_20260101T000000000Z.csv", EXPORT_MAX_AGE_MS + 1)
        val recent = file("applog_20260102T000000000Z.csv", EXPORT_MAX_AGE_MS - 1)
        val foreign = file("notes.csv", EXPORT_MAX_AGE_MS * 10)

        assertEquals(1, deleteStaleExports(dir, now))

        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(foreign.exists())
    }
}
