package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import de.adv990de.obd2.data.db.AppLogDao
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * [AppLogExporter]: das Schreiben des App-Logs als CSV. Wie bei `SessionRepositoryTest` laeuft der echte Rumpf gegen echte Dateien unter [tempFolder];
 * gestellt sind nur der Cursor des DAOs, `FileProvider.getUriForFile` (braucht Android) und der `ContentResolver` des Ziels.
 */
class AppLogExporterTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val dao = mockk<AppLogDao>()
    private val context = mockk<Context>()
    private val exporter = AppLogExporter(context, dao)

    private data class Row(val timestamp: Long, val level: String, val message: String?)

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(FileProvider::class)
    }

    private fun stubEnvironment() {
        every { context.cacheDir } returns tempFolder.root
        every { context.packageName } returns "de.adv990de.obd2"
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk<Uri>()
    }

    private fun cursor(rows: List<Row>, onRow: (Int) -> Unit = {}): Cursor {
        var index = -1
        return mockk<Cursor> {
            every { getColumnIndexOrThrow("timestamp") } returns 0
            every { getColumnIndexOrThrow("level") } returns 1
            every { getColumnIndexOrThrow("message") } returns 2
            every { moveToNext() } answers { index++; onRow(index); index < rows.size }
            every { getLong(0) } answers { rows[index].timestamp }
            every { getString(1) } answers { rows[index].level }
            every { getString(2) } answers { rows[index].message }
            every { close() } just Runs
        }
    }

    private fun exportedFiles(): List<File> = File(tempFolder.root, "exports").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    /** The id the query is bound to (the first and only `?`). */
    private fun boundAfterId(query: SupportSQLiteQuery): Long {
        val program = mockk<SupportSQLiteProgram>(relaxed = true)
        query.bindTo(program)
        val id = slot<Long>()
        verify { program.bindLong(1, capture(id)) }
        return id.captured
    }

    // ── exportToCsv ───────────────────────────────────────────────────────────

    @Test
    fun `exportToCsv writes the header and the rows in the order of the query`() = runTest {
        stubEnvironment()
        val query = slot<SupportSQLiteQuery>()
        every { dao.getExportCursor(capture(query)) } returns cursor(
            listOf(Row(1_000L, "INFO", "Connecting to MWT_OBD2…"), Row(2_000L, "ERROR", "Connection error: boom")),
        )

        val uri = exporter.exportToCsv()

        assertNotNull(uri)
        assertEquals("the export uses the shared query constant", APP_LOG_EXPORT_QUERY_SQL, query.captured.sql)
        val file = exportedFiles().single()
        assertTrue("name: ${file.name}", file.name.startsWith("applog_") && file.name.endsWith(".csv"))
        assertEquals("timestamp,level,message\n1000,INFO,Connecting to MWT_OBD2…\n2000,ERROR,Connection error: boom\n", file.readText())
        verify { FileProvider.getUriForFile(context, "de.adv990de.obd2.fileprovider", file) }
    }

    @Test
    fun `a message with a comma, a quote or a line break is quoted, an empty or missing one stays empty`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } returns cursor(
            listOf(
                Row(1L, "WARN", "values frozen: unchanged for 20 cycles, raw: 010C=41 0C 19 F0"),
                Row(2L, "INFO", "say \"hi\""),
                Row(3L, "INFO", "line1\nline2"),
                Row(4L, "INFO", ""),
                Row(5L, "INFO", null),
            ),
        )

        exporter.exportToCsv()

        assertEquals(
            "timestamp,level,message\n" +
                "1,WARN,\"values frozen: unchanged for 20 cycles, raw: 010C=41 0C 19 F0\"\n" +
                "2,INFO,\"say \"\"hi\"\"\"\n" +
                "3,INFO,\"line1\nline2\"\n" +
                "4,INFO,\n" +
                "5,INFO,\n",
            exportedFiles().single().readText(),
        )
    }

    @Test
    fun `the query is bound to the id after which the rows count - zero by default, the given id otherwise`() = runTest {
        stubEnvironment()
        val queries = mutableListOf<SupportSQLiteQuery>()
        every { dao.getExportCursor(capture(queries)) } answers { cursor(emptyList()) }

        exporter.exportToCsv()
        exporter.exportToCsv(afterId = 12L)

        assertEquals(listOf(0L, 12L), queries.map { boundAfterId(it) })
    }

    @Test
    fun `with no rows only the header is written`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } returns cursor(emptyList())

        exporter.exportToCsv()

        assertEquals("timestamp,level,message\n", exportedFiles().single().readText())
    }

    @Test
    fun `every export gets its own file`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } answers { cursor(listOf(Row(1L, "INFO", "x"))) }

        exporter.exportToCsv()
        exporter.exportToCsv()

        assertEquals(2, exportedFiles().size)
    }

    @Test
    fun `exports older than a day are removed first, trip exports included - recent ones stay`() = runTest {
        stubEnvironment()
        val dir = File(tempFolder.root, "exports").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        fun file(name: String, ageMs: Long) = File(dir, name).also { it.writeText("x"); it.setLastModified(now - ageMs) }
        val oldLog = file("applog_20200101T000000000Z.csv", EXPORT_MAX_AGE_MS + 60_000)
        val oldTrips = file("session_all_20200101T000000000Z.csv", EXPORT_MAX_AGE_MS + 60_000)
        val recent = file("applog_20991231T000000000Z.csv", 60_000)
        every { dao.getExportCursor(any()) } returns cursor(emptyList())

        exporter.exportToCsv()

        assertTrue(!oldLog.exists() && !oldTrips.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `a failure while reading leaves no half-written file and is passed on`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } throws IllegalStateException("database is locked")

        try {
            exporter.exportToCsv()
            fail("a read error must reach the caller")
        } catch (_: IllegalStateException) { }

        assertTrue("no leftovers", exportedFiles().isEmpty())
    }

    @Test
    fun `a failure in the middle of the rows leaves no half-written file either`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } returns cursor(List(10) { Row(it.toLong(), "INFO", "m$it") }) { i ->
            if (i == 5) throw IllegalStateException("disk I/O error")
        }

        try { exporter.exportToCsv() } catch (_: IllegalStateException) { }

        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a cancelled export stops reading and leaves no file`() = runTest {
        stubEnvironment()
        lateinit var job: Job
        // 2500 rows: the cancellation is looked at every 2000 rows, so the export must stop at the 2000th at the latest.
        every { dao.getExportCursor(any()) } returns cursor(List(2_500) { Row(it.toLong(), "INFO", "m") }) { i -> if (i == 100) job.cancel() }

        job = launch { exporter.exportToCsv() }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("no leftovers", exportedFiles().isEmpty())
    }

    // ── saveCsv ───────────────────────────────────────────────────────────────

    @Test
    fun `saveCsv writes the same CSV into the chosen target and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } returns cursor(listOf(Row(1_000L, "INFO", "a"), Row(2_000L, "WARN", "b,c")))
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        exporter.saveCsv(target)

        assertEquals("timestamp,level,message\n1000,INFO,a\n2000,WARN,\"b,c\"\n", out.toString(Charsets.UTF_8.name()))
        assertTrue("the intermediate file in the cache is gone", exportedFiles().isEmpty())
    }

    @Test
    fun `saveCsv passes the id after which the rows count on to the query`() = runTest {
        stubEnvironment()
        val query = slot<SupportSQLiteQuery>()
        every { dao.getExportCursor(capture(query)) } returns cursor(emptyList())
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns ByteArrayOutputStream()

        exporter.saveCsv(target, afterId = 7L)

        assertEquals(7L, boundAfterId(query.captured))
    }

    @Test
    fun `saveCsv reports a target that cannot be opened and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } returns cursor(listOf(Row(1L, "INFO", "a")))
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns null

        try {
            exporter.saveCsv(target)
            fail("a target without an output stream must fail")
        } catch (_: IOException) { }

        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a failing read never touches the chosen target`() = runTest {
        stubEnvironment()
        every { dao.getExportCursor(any()) } throws IllegalStateException("database is locked")
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        try { exporter.saveCsv(target) } catch (_: IllegalStateException) { }

        verify(exactly = 0) { resolver.openOutputStream(any<Uri>(), any<String>()) }
    }
}
