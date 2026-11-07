package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * [CsvExportFiles], der gemeinsame Rahmen der drei Exporte, direkt und ohne einen der Verbraucher: das Schreiben in den Cache, die Herausgabe als [Uri] des FileProviders oder in ein
 * gewaehltes Ziel und was bei einem Fehler uebrig bleibt. Die Exporter-Tests pruefen dieselben Regeln noch einmal durch ihre Zeilen; hier stehen sie, ohne dass eine Zeile dazu noetig ist —
 * und der Name des FileProvider-Eintrags, den dort keiner prueft (`getUriForFile(any(), any(), any())`) und der in `AndroidManifest.xml` als `<applicationId>.fileprovider` steht.
 */
class CsvExportFilesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val files = CsvExportFiles(context)

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(FileProvider::class)
    }

    private fun stubEnvironment(shared: Uri = mockk()) {
        every { context.cacheDir } returns tempFolder.root
        every { context.packageName } returns "de.adv990de.obd2"
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns shared
    }

    private fun exportedFiles(): List<File> = File(tempFolder.root, EXPORT_CACHE_DIR).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    private fun stubTarget(target: Uri, out: ByteArrayOutputStream?): ContentResolver {
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out
        return resolver
    }

    // --- share ---

    @Test
    fun `share writes into a new file under the export folder, named by the caller, and hands out the FileProvider uri`() = runTest {
        val shared = mockk<Uri>()
        stubEnvironment(shared)
        val file = slot<File>()
        every { FileProvider.getUriForFile(any(), any(), capture(file)) } returns shared

        val uri = files.share({ "applog_$it.csv" }) { it.write("a,b\n1,2\n") }

        assertSame(shared, uri)
        assertEquals("exports", file.captured.parentFile!!.name)
        assertTrue(file.captured.name.matches(Regex("""applog_\d+\.csv""")))
        assertEquals("a,b\n1,2\n", file.captured.readText())
    }

    @Test
    fun `the FileProvider is asked for the authority the manifest declares - the package name plus fileprovider`() = runTest {
        stubEnvironment()

        files.share({ "applog_$it.csv" }) { it.write("x") }

        verify(exactly = 1) { FileProvider.getUriForFile(context, "de.adv990de.obd2.fileprovider", any()) }
    }

    @Test
    fun `every share gets its own file, even for the same name`() = runTest {
        stubEnvironment()

        repeat(2) { files.share({ "applog_fixed.csv" }) { it.write("x") } }

        assertEquals(listOf("applog_fixed-1.csv", "applog_fixed.csv"), exportedFiles().map { it.name })
    }

    @Test
    fun `exports older than a day are removed before the new one is written - recent ones and foreign files stay`() = runTest {
        stubEnvironment()
        val dir = File(tempFolder.root, EXPORT_CACHE_DIR).also { it.mkdirs() }
        val now = System.currentTimeMillis()
        fun file(name: String, ageMs: Long) = File(dir, name).also { it.writeText("x"); it.setLastModified(now - ageMs) }
        val old = file("logbook_20200101T000000000Z.csv", EXPORT_MAX_AGE_MS + 60_000)
        val recent = file("logbook_20991231T000000000Z.csv", 60_000)
        val foreign = file("notes.txt", EXPORT_MAX_AGE_MS + 60_000)

        files.share({ "applog_$it.csv" }) { it.write("x") }

        assertTrue(!old.exists())
        assertTrue(recent.exists() && foreign.exists())
    }

    @Test
    fun `a failing write leaves no file, is passed on, and nothing is handed out`() = runTest {
        stubEnvironment()

        try {
            files.share({ "applog_$it.csv" }) { it.write("half"); throw IllegalStateException("disk full") }
            fail("a write error must reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }

        assertTrue("no leftovers", exportedFiles().isEmpty())
        verify(exactly = 0) { FileProvider.getUriForFile(any(), any(), any()) }
    }

    @Test
    fun `a cancelled write leaves no file`() = runTest {
        stubEnvironment()
        lateinit var job: Job

        job = launch { files.share({ "applog_$it.csv" }) { it.write("half"); job.cancel(); kotlinx.coroutines.currentCoroutineContext().ensureActive() } }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("no leftovers", exportedFiles().isEmpty())
    }

    // --- save ---

    @Test
    fun `save copies the finished file into the target and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        stubTarget(target, out)

        files.save(target, { "logbook_$it.csv" }) { it.write("date,type\n2026-11-02,FUEL\n") }

        assertEquals("date,type\n2026-11-02,FUEL\n", out.toString(Charsets.UTF_8.name()))
        assertTrue("the intermediate file in the cache is gone", exportedFiles().isEmpty())
        verify(exactly = 0) { FileProvider.getUriForFile(any(), any(), any()) }
    }

    @Test
    fun `save reports a target that cannot be opened and leaves nothing in the cache`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        stubTarget(target, null)

        try {
            files.save(target, { "logbook_$it.csv" }) { it.write("x") }
            fail("a target without an output stream must fail")
        } catch (_: IOException) { }

        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a failing write never touches the chosen target`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val resolver = stubTarget(target, ByteArrayOutputStream())

        try {
            files.save(target, { "logbook_$it.csv" }) { throw IllegalStateException("database is locked") }
        } catch (_: IllegalStateException) { }

        verify(exactly = 0) { resolver.openOutputStream(any<Uri>(), any<String>()) }
        assertTrue(exportedFiles().isEmpty())
    }

    // --- Strom-Varianten (Pakete) ---

    @Test
    fun `shareStream writes the bytes untouched into a new file and hands out the uri`() = runTest {
        val shared = mockk<Uri>()
        stubEnvironment(shared)
        val file = slot<File>()
        every { FileProvider.getUriForFile(any(), any(), capture(file)) } returns shared
        val bytes = byteArrayOf(0x50, 0x4B, 3, 4, 0, -1, 127)

        val uri = files.shareStream({ "obd-dashboard_$it.zip" }) { it.write(bytes) }

        assertSame(shared, uri)
        assertTrue(file.captured.name.matches(Regex("""obd-dashboard_\d+\.zip""")))
        assertTrue(bytes.contentEquals(file.captured.readBytes()))
    }

    @Test
    fun `a failing stream write leaves no file and hands nothing out`() = runTest {
        stubEnvironment()

        try {
            files.shareStream({ "obd-dashboard_$it.zip" }) { it.write(1); throw IllegalStateException("disk full") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }

        assertTrue(exportedFiles().isEmpty())
        verify(exactly = 0) { FileProvider.getUriForFile(any(), any(), any()) }
    }

    @Test
    fun `a cancelled stream write leaves no file`() = runTest {
        stubEnvironment()
        lateinit var job: Job

        job = launch { files.shareStream({ "obd-dashboard_$it.zip" }) { it.write(1); job.cancel(); kotlinx.coroutines.currentCoroutineContext().ensureActive() } }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `saveStream copies the finished bytes into the target and always removes the cache file`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        stubTarget(target, out)

        files.saveStream(target, { "obd-dashboard_$it.zip" }) { it.write(byteArrayOf(9, 8, 7)) }

        assertTrue(byteArrayOf(9, 8, 7).contentEquals(out.toByteArray()))
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `saveStream reports a target that cannot be opened and leaves no cache file`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        stubTarget(target, null)

        try {
            files.saveStream(target, { "obd-dashboard_$it.zip" }) { it.write(1) }
            fail()
        } catch (_: IOException) { }

        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a failing stream write never touches the chosen target`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val resolver = stubTarget(target, ByteArrayOutputStream())

        try {
            files.saveStream(target, { "x_$it.zip" }) { throw IllegalStateException("locked") }
        } catch (_: IllegalStateException) { }

        verify(exactly = 0) { resolver.openOutputStream(any<Uri>(), any<String>()) }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `the writer wrapper closes the writer so the whole text reaches the target`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        stubTarget(target, out)

        files.save(target, { "logbook_$it.csv" }) { it.write("small") }

        assertEquals("small", out.toString(Charsets.UTF_8.name()))
    }
}
