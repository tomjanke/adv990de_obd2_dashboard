package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.readGpx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [TrackExporter] gegen eine echte (In-Memory-)Room-Datenbank, den echten FileProvider und den echten XML-Parser des Geraets: ein aufgezeichneter Track kommt als GPX-Datei heraus, die der
 * Leser der App (der Parser des Android-Systems) wieder einliest — gleicher Name, gleiche Punkte. Der Dateidialog und das Teilen-Menue lassen sich im Test nicht bedienen; eine Datei-Uri steht
 * fuer das Ergebnis des Dateidialogs. **Nur erfundene Koordinaten.**
 */
@RunWith(AndroidJUnit4::class)
class TrackExporterDeviceTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: TrackRepository
    private lateinit var exporter: TrackExporter
    private val exportDir get() = File(ctx.cacheDir, EXPORT_CACHE_DIR)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = TrackRepository(db)
        exporter = TrackExporter(ctx, repo)
        exportDir.deleteRecursively()
    }

    @After
    fun teardown() {
        db.close()
        exportDir.deleteRecursively()
    }

    private fun point(i: Int) = TrackPoint(timeMs = 1_700_000_000_000L + 1_000L * i, lat = 50.0 + i * 0.0001, lon = 8.0 + i * 0.0002, ele = 100f + i, accuracyM = 4f, speedKmh = 40f)

    private fun record(name: String, count: Int = 25): Long = runBlocking {
        val id = repo.create(name, TrackSource.RECORDED, startMs = 1_700_000_000_000L)
        repo.appendPoints(id, List(count) { point(it) })
        repo.finish(id, endMs = 1_700_000_000_000L + 1_000L * count, stats = de.adv990de.obd2.track.trackStats(List(count) { point(it) }))
        id
    }

    private fun readBack(file: File) = file.inputStream().use { readGpx(it) }

    @Test
    fun exportToGpx_writesTheRecordedTrack_andTheFileProviderUriReadsItBack() = runBlocking<Unit> {
        val id = record("Morgenrunde")

        val uri: Uri = exporter.exportToGpx(id)

        assertNotNull(uri)
        val file = exportDir.listFiles()!!.single()
        assertTrue("name: ${file.name}", file.name.startsWith("track_Morgenrunde_") && file.name.endsWith(".gpx"))
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("the uri of the file provider gives the same bytes as the file", file.readText(), bytes.toString(Charsets.UTF_8))
        val read = readBack(file)
        assertEquals("Morgenrunde", read.tracks.single().name)
        assertEquals(0, read.rejectedPoints)
        assertEquals((0 until 25).map { point(it).lat to point(it).lon }, read.tracks.single().points.map { it.lat to it.lon })
        assertEquals((0 until 25).map { point(it).timeMs }, read.tracks.single().points.map { it.timeMs })
    }

    @Test
    fun aNameWithSpecialCharacters_isEscapedInTheFile_andCleanedInTheFileName() = runBlocking<Unit> {
        val id = record("Ötztal <Süd> & \"Rückweg\" / ..")

        exporter.exportToGpx(id)

        val file = exportDir.listFiles()!!.single()
        assertTrue("name: ${file.name}", file.name.startsWith("track_Ötztal_Süd_Rückweg_") && !file.name.contains(".."))
        assertEquals("Ötztal <Süd> & \"Rückweg\" / ..", readBack(file).tracks.single().name)
    }

    @Test
    fun saveGpx_writesTheSameFileIntoTheChosenTarget_andLeavesNothingInTheCache() = runBlocking<Unit> {
        val id = record("Runde")
        val target = File(ctx.cacheDir, "chosen_by_the_dialog.gpx").also { it.delete() }

        exporter.saveGpx(Uri.fromFile(target), id)

        val read = readBack(target)
        assertEquals("Runde", read.tracks.single().name)
        assertEquals(25, read.tracks.single().points.size)
        assertTrue("the cache file is gone after saving", exportDir.listFiles().orEmpty().isEmpty())
        target.delete()
    }

    @Test
    fun aRunningRecording_isExportedWithThePointsSoFar() = runBlocking<Unit> {
        val id = runBlocking { repo.create("Laeuft", TrackSource.RECORDED, startMs = 1_700_000_000_000L) }
        runBlocking { repo.appendPoints(id, List(7) { point(it) }) }

        exporter.exportToGpx(id)

        assertEquals(7, readBack(exportDir.listFiles()!!.single()).tracks.single().points.size)
    }

    @Test
    fun aTrackWithoutPoints_andAnUnknownTrack_failAndLeaveNoFile() = runBlocking<Unit> {
        val empty = runBlocking { repo.create("Leer", TrackSource.RECORDED, startMs = 1L) }
        var failures = 0
        try { exporter.exportToGpx(empty) } catch (_: IllegalStateException) { failures++ }
        try { exporter.exportToGpx(9_999L) } catch (_: IllegalArgumentException) { failures++ }

        assertEquals(2, failures)
        assertTrue(exportDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anOldTrackExport_isRemovedBeforeTheNextOne() = runBlocking<Unit> {
        val id = record("Runde")
        exportDir.mkdirs()
        val old = File(exportDir, trackFileName("alt", 0L)).also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - EXPORT_MAX_AGE_MS - 60_000) }

        exporter.exportToGpx(id)

        assertTrue("old track export removed", !old.exists())
        assertEquals(1, exportDir.listFiles()!!.size)
    }
}
