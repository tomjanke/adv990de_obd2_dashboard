package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
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
 * [TrackExporter]: ein Track als GPX-Datei. Der echte Rumpf laeuft gegen echte Dateien unter [tempFolder]; gestellt sind das Repository, `FileProvider.getUriForFile` (braucht Android)
 * und der `ContentResolver` des Ziels. **Nur erfundene Koordinaten.**
 */
class TrackExporterTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val repository = mockk<TrackRepository>()

    private fun summary(id: Long, name: String, pointCount: Int = 2) = TrackSummary(
        id = id, name = name, source = TrackSource.RECORDED, startMs = 1_000L, endMs = 61_000L, sessionId = null,
        distanceM = 100, ascentM = null, descentM = null, pointCount = pointCount, createdMs = 1_000L, running = false,
    )

    private val points = listOf(
        TrackPoint(1_700_000_000_000L, 50.0, 10.0, 120.5f, 5f, 30f),
        TrackPoint(1_700_000_010_000L, 50.0001, 10.0002, null, 5f, 31f),
    )

    private fun exporter(tracks: List<TrackSummary>, pointsOf: suspend (Long) -> List<TrackPoint> = { points }): TrackExporter {
        every { repository.tracks } returns MutableStateFlow(tracks)
        coEvery { repository.points(any()) } coAnswers { pointsOf(firstArg()) }
        return TrackExporter(context, repository)
    }

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

    @Test
    fun `exportToGpx writes the track as GPX with its name and every point`() = runTest {
        stubEnvironment()

        val uri = exporter(listOf(summary(7, "Morgenrunde"))).exportToGpx(7)

        assertNotNull(uri)
        val file = exportedFiles().single()
        assertTrue("name: ${file.name}", file.name.startsWith("track_Morgenrunde_") && file.name.endsWith(".gpx"))
        val text = file.readText()
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\""))
        assertTrue(text.contains("<name>Morgenrunde</name>"))
        assertTrue(text.contains("<trkpt lat=\"50.0000000\" lon=\"10.0000000\"><ele>120.5</ele><time>2023-11-14T22:13:20Z</time></trkpt>"))
        assertEquals("both points are written", 2, Regex("<trkpt ").findAll(text).count())
        io.mockk.verify { FileProvider.getUriForFile(context, "de.adv990de.obd2.fileprovider", file) }
    }

    @Test
    fun `the file reads back with the reader of the app - same name, same points`() = runTest {
        stubEnvironment()

        exporter(listOf(summary(7, "Runde & Rückweg"))).exportToGpx(7)

        val read = de.adv990de.obd2.track.readGpx(
            exportedFiles().single().inputStream(),
            newParser = { org.kxml2.io.KXmlParser() },
        )
        assertEquals("Runde & Rückweg", read.tracks.single().name)
        assertEquals(points.map { it.lat to it.lon }, read.tracks.single().points.map { it.lat to it.lon })
    }

    @Test
    fun `the points of the asked track are read, not another one`() = runTest {
        stubEnvironment()
        val asked = mutableListOf<Long>()
        val e = exporter(listOf(summary(1, "eins"), summary(2, "zwei"))) { id -> asked += id; points }

        e.exportToGpx(2)

        assertEquals(listOf(2L), asked)
        assertTrue(exportedFiles().single().name.startsWith("track_zwei_"))
    }

    @Test
    fun `every export gets its own file`() = runTest {
        stubEnvironment()
        val e = exporter(listOf(summary(7, "x")))

        e.exportToGpx(7)
        Thread.sleep(2) // the name carries the time to the millisecond; a second export in the same millisecond gets -1
        e.exportToGpx(7)

        assertEquals(2, exportedFiles().size)
    }

    @Test
    fun `a track that no longer exists is an error and leaves no file`() = runTest {
        stubEnvironment()
        try {
            exporter(listOf(summary(1, "eins"))).exportToGpx(99)
            fail("an unknown id must not export")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a track without points is an error and leaves no file - an empty GPX would be a useless file`() = runTest {
        stubEnvironment()
        try {
            exporter(listOf(summary(7, "leer", pointCount = 0))) { emptyList() }.exportToGpx(7)
            fail("an empty track must not export")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a failure while reading the points is passed on and leaves no file`() = runTest {
        stubEnvironment()
        try {
            exporter(listOf(summary(7, "x"))) { throw IOException("disk I/O error") }.exportToGpx(7)
            fail("a read error must reach the caller")
        } catch (_: IOException) { }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a cancelled export is not an error of the exporter - the cancellation passes through and leaves no file`() = runTest {
        stubEnvironment()
        val e = exporter(listOf(summary(7, "x"))) { kotlinx.coroutines.awaitCancellation() }
        val job = launch { e.exportToGpx(7) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `saveGpx writes the same GPX into the chosen target and removes the cache file`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val sink = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, any()) } returns sink

        exporter(listOf(summary(7, "Morgenrunde"))).saveGpx(target, 7)

        val text = sink.toString(Charsets.UTF_8)
        assertTrue(text.contains("<name>Morgenrunde</name>") && text.trimEnd().endsWith("</gpx>"))
        assertTrue("the cache file is gone after saving", exportedFiles().isEmpty())
    }

    @Test
    fun `a target that cannot be opened is an IOException and the cache file is still removed`() = runTest {
        stubEnvironment()
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, any()) } returns null

        try {
            exporter(listOf(summary(7, "x"))).saveGpx(target, 7)
            fail("a closed target must fail")
        } catch (_: IOException) { }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `the name suggested for the file dialog is the same pattern as the cache file`() {
        val name = trackFileName("Morgenrunde", 1_800_000_000_123L)
        assertEquals("track_Morgenrunde_20270115T080000123Z.gpx", name)
    }
}
