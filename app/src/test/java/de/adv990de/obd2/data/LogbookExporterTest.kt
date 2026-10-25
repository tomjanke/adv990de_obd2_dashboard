package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import java.time.LocalDate

/**
 * [LogbookExporter]: das Schreiben des Logbuchs als CSV. Wie bei `AppLogExporterTest` laeuft der echte Rumpf gegen echte Dateien unter [tempFolder];
 * gestellt sind das Repository, `FileProvider.getUriForFile` (braucht Android) und der `ContentResolver` des Ziels.
 */
class LogbookExporterTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val repository = mockk<VehicleLogRepository>()
    private val context = mockk<Context>()
    private val exporter = LogbookExporter(context, repository)

    private val header = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n"

    private fun fuel(km: Int, day: Int = 2, note: String? = null, cost: Int? = 2_743) = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, day), odometerKm = km, amountMl = 14_370,
        fullTank = true, costCents = cost, note = note, brand = null,
    )

    private val tires = LogEntry(
        type = LogType.TIRE_CHANGE, date = LocalDate.of(2026, 9, 20), odometerKm = 39_800, amountMl = null,
        fullTank = null, costCents = null, note = null, brand = "Michelin Anakee Adventure 150/70-18",
    )

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

    private fun exportedFiles(): List<File> = File(tempFolder.root, "exports").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    // --- exportToCsv ---

    @Test
    fun `exportToCsv writes the header and every entry in the order the repository gives them`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(tires, fuel(40_000, day = 1), fuel(40_362, day = 2, cost = null))

        val uri = exporter.exportToCsv()

        assertNotNull(uri)
        val file = exportedFiles().single()
        assertTrue("name: ${file.name}", file.name.startsWith("logbook_") && file.name.endsWith(".csv"))
        assertEquals(
            header +
                "2026-09-20,TIRE_CHANGE,39800,,,,,Michelin Anakee Adventure 150/70-18\n" +
                "2026-10-01,FUEL,40000,14370,1,27.43,,\n" +
                "2026-10-02,FUEL,40362,14370,1,,,\n",
            file.readText(),
        )
        verify { FileProvider.getUriForFile(context, "de.adv990de.obd2.fileprovider", file) }
    }

    @Test
    fun `a note with a comma, a quote or a line break is quoted so the file stays readable`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(1, note = "Shell, \"Autobahn\"\nzweite Zeile"))

        exporter.exportToCsv()

        assertEquals(header + "2026-10-02,FUEL,1,14370,1,27.43,\"Shell, \"\"Autobahn\"\"\nzweite Zeile\",\n", exportedFiles().single().readText())
    }

    @Test
    fun `an empty logbook exports just the header`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns emptyList()

        exporter.exportToCsv()

        assertEquals(header, exportedFiles().single().readText())
    }

    @Test
    fun `every export gets its own file`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(1))

        exporter.exportToCsv()
        exporter.exportToCsv()

        assertEquals(2, exportedFiles().size)
    }

    @Test
    fun `exports older than a day are removed first - logbook, trip and app log exports alike - recent ones stay`() = runTest {
        stubEnvironment()
        val dir = File(tempFolder.root, "exports").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        fun file(name: String, ageMs: Long) = File(dir, name).also { it.writeText("x"); it.setLastModified(now - ageMs) }
        val oldLogbook = file("logbook_20200101T000000000Z.csv", EXPORT_MAX_AGE_MS + 60_000)
        val oldTrips = file("session_all_20200101T000000000Z.csv", EXPORT_MAX_AGE_MS + 60_000)
        val recent = file("logbook_20991231T000000000Z.csv", 60_000)
        val foreign = file("notes.csv", EXPORT_MAX_AGE_MS * 10)
        coEvery { repository.all() } returns emptyList()

        exporter.exportToCsv()

        assertTrue("alte Logbuch- und Fahrten-Exporte weg", !oldLogbook.exists() && !oldTrips.exists())
        assertTrue("junge und fremde Dateien bleiben", recent.exists() && foreign.exists())
    }

    @Test
    fun `a failure while reading leaves no file and is passed on`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } throws IllegalStateException("database is locked")

        try {
            exporter.exportToCsv()
            fail("a read error must reach the caller")
        } catch (_: IllegalStateException) { }

        assertTrue("no leftovers", exportedFiles().isEmpty())
    }

    @Test
    fun `a cancelled export stops while it writes and leaves no half-written file`() = runTest {
        stubEnvironment()
        lateinit var job: Job
        // 2500 entries: the cancellation is looked at every 2000 rows. The list cancels the job at the 100th entry, i.e. AFTER the file exists:
        // the export must stop at the 2000th row at the latest and remove what it wrote.
        val entries = object : AbstractList<LogEntry>() {
            override val size = 2_500
            override fun get(index: Int): LogEntry { if (index == 100) job.cancel(); return fuel(index) }
        }
        coEvery { repository.all() } returns entries

        job = launch { exporter.exportToCsv() }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("no leftovers", exportedFiles().isEmpty())
    }

    @Test
    fun `entries the user just deleted - still inside the undo window - stay out of the file`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(1).copy(id = 1), fuel(2).copy(id = 2), fuel(3).copy(id = 3))

        exporter.exportToCsv(excluding = setOf(2L))

        assertEquals(header + "2026-10-02,FUEL,1,14370,1,27.43,,\n" + "2026-10-02,FUEL,3,14370,1,27.43,,\n", exportedFiles().single().readText())
    }

    @Test
    fun `saveCsv leaves the hidden entries out too`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(1).copy(id = 1), fuel(2).copy(id = 2))
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        exporter.saveCsv(target, excluding = setOf(1L))

        assertEquals(header + "2026-10-02,FUEL,2,14370,1,27.43,,\n", out.toString(Charsets.UTF_8.name()))
    }

    // --- saveCsv ---

    @Test
    fun `saveCsv writes the same CSV into the chosen target and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(40_362))
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        exporter.saveCsv(target)

        assertEquals(header + "2026-10-02,FUEL,40362,14370,1,27.43,,\n", out.toString(Charsets.UTF_8.name()))
        assertTrue("the intermediate file in the cache is gone", exportedFiles().isEmpty())
    }

    @Test
    fun `saveCsv reports a target that cannot be opened and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        coEvery { repository.all() } returns listOf(fuel(1))
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
        coEvery { repository.all() } throws IllegalStateException("database is locked")
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        try { exporter.saveCsv(target) } catch (_: IllegalStateException) { }

        verify(exactly = 0) { resolver.openOutputStream(any<Uri>(), any<String>()) }
    }
}
