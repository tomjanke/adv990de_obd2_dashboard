package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.logbookCsvHeader
import de.adv990de.obd2.logbook.logbookCsvLine
import de.adv990de.obd2.transfer.DataPart
import kotlinx.coroutines.flow.first
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.track.trackStats
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.GpxFileException
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.toJson
import kotlinx.coroutines.runBlocking
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Der Importer von „Daten wiederherstellen“ gegen echte Dateien und echte `file:`-Uris (der Systemdialog zur Dateiwahl laesst sich im Test nicht bedienen): jede CSV-Art, Binaermuell, die
 * App-Log-CSV, eine alte Fahrten-Datei ohne `source` und `session`, ein Paket — und dass die Kopie im Cache danach weg ist, auch nach einem Fehler.
 */
@RunWith(AndroidJUnit4::class)
class TransferImporterDeviceTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var importer: TransferImporter
    private lateinit var trackRepo: TrackRepository
    private val files = ArrayList<File>()
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        trackRepo = TrackRepository(db)
        importer = TransferImporter(ctx, TripImporter(ctx, SessionRepository(ctx, db)), LogbookImporter(ctx, VehicleLogRepository(db.vehicleLogDao())), DtcHistoryRepository(db.dtcHistoryDao(), AppLogRepository(db.appLogDao())), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), VehicleLogRepository(db.vehicleLogDao()), TrackImporter(ctx, trackRepo))
        File(ctx.cacheDir, TRANSFER_IMPORT_CACHE_DIR).deleteRecursively()
    }

    @After
    fun teardown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun file(name: String, bytes: ByteArray): Uri {
        val f = File(ctx.cacheDir, name).also { it.writeBytes(bytes); files += it }
        return Uri.fromFile(f)
    }

    private fun file(name: String, text: String) = file(name, text.toByteArray())

    private val twoDaysAgo = now - 2L * 24 * 3_600_000
    private val tripsCsv = "timestamp,pid,value,name,source,session\n$twoDaysAgo,010C,1500.0,Engine RPM,ECU,1\n${twoDaysAgo + 1_000},010C,1600.0,Engine RPM,ECU,1\n"
    private val oldTripsCsv = "timestamp,pid,value\n$twoDaysAgo,010C,1500.0\n${twoDaysAgo + 1_000},010C,1600.0\n"
    private val logbookCsv = logbookCsvHeader() + logbookCsvLine(
        LogEntry(type = LogType.FUEL, date = today.minusDays(3), odometerKm = 1000, amountMl = 12_000, fullTank = true, costCents = null, note = null, brand = null),
    )

    private fun packageFile(name: String, withManifest: Boolean = true, vararg entries: Pair<String, String>): Uri {
        val f = File(ctx.cacheDir, name).also { files += it }
        ZipOutputStream(FileOutputStream(f)).use { z ->
            if (withManifest) {
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(PackageManifest(1, "de.adv990de.obd2", "1.0", now, emptyMap()).toJson().toByteArray())
                z.closeEntry()
            }
            for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return Uri.fromFile(f)
    }

    private fun copies(): List<File> = File(ctx.cacheDir, TRANSFER_IMPORT_CACHE_DIR).listFiles().orEmpty().toList()

    @Test
    fun aTripsCsvIsRead() = runBlocking {
        val preview = importer.inspect(file("transfer_trips.csv", tripsCsv), now, today)
        assertEquals(FileKind.TRIPS_CSV, preview.kind)
        assertEquals(1, (preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.trips.size)
    }

    @Test
    fun aLogbookCsvIsRead() = runBlocking {
        val preview = importer.inspect(file("transfer_logbook.csv", logbookCsv), now, today)
        assertEquals(FileKind.LOGBOOK_CSV, preview.kind)
        assertEquals(1, (preview.parts.getValue(DataPart.LOGBOOK) as PartPlan.Logbook).plan.toInsert.size)
    }

    @Test
    fun anOldTripsFileWithoutSourceAndSessionIsStillRead() = runBlocking {
        val preview = importer.inspect(file("transfer_old_trips.csv", oldTripsCsv), now, today)
        val trips = (preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.trips
        assertEquals(1, trips.size)
        assertEquals(2, trips.single().rows.size)
    }

    @Test
    fun binaryGarbageIsNotRecognized() = runBlocking {
        try {
            importer.inspect(file("transfer_garbage.bin", ByteArray(400) { (it * 13 + 5).toByte() }), now, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.UNRECOGNIZED, e.kind)
        }
    }

    @Test
    fun anAppLogCsvCannotBeImported() = runBlocking {
        try {
            importer.inspect(file("transfer_applog.csv", "timestamp,level,message\n1,INFO,x\n"), now, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.APP_LOG_NOT_IMPORTABLE, e.kind)
        }
    }

    @Test
    fun aPackageIsReadAndTheCacheCopyIsGone() = runBlocking {
        val uri = packageFile("transfer_ok.zip", true, "logbook.csv" to logbookCsv, "trips.csv" to tripsCsv)
        val preview = importer.inspect(uri, now, today)
        assertEquals(FileKind.PACKAGE, preview.kind)
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS), preview.parts.keys.toList())
        assertTrue(copies().isEmpty())
    }

    @Test
    fun theCacheCopyIsGoneAfterAFailureToo() = runBlocking {
        try {
            importer.inspect(packageFile("transfer_nomanifest.zip", false, "trips.csv" to tripsCsv), now, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
        assertTrue(copies().isEmpty())
    }

    // ── Der Name der gewaehlten Datei ────────────────────────────────────────

    /** Eine Datei hinter einer `content://`-Uri, so wie sie der Dateidialog liefert: ueber den FileProvider der App (Ordner `exports/` im Cache). */
    private fun contentFile(name: String): Uri {
        val f = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, name).also { it.writeText("x"); files += it }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    @Test
    fun theDisplayNameOfAContentUriIsTheNameOfTheFile() = runBlocking {
        assertEquals("obd-dashboard_test.zip", importer.displayName(contentFile("obd-dashboard_test.zip")))
    }

    @Test
    fun aNameWithSpacesAndUmlautsComesBackAsItIs() = runBlocking {
        assertEquals("Sicherung Müller (1).zip", importer.displayName(contentFile("Sicherung Müller (1).zip")))
    }

    @Test
    fun aFileUriHasNoDisplayNameAndThatIsNotAnError() = runBlocking {
        assertNull(importer.displayName(file("plain_file.csv", "x")))
    }

    @Test
    fun aContentUriOfAnUnknownProviderGivesNullAndNeverThrows() = runBlocking {
        assertNull(importer.displayName(Uri.parse("content://de.adv990de.obd2.does.not.exist/some/file.zip")))
    }

    // --- GPX (Tracks), mit dem XML-Leser des Systems; alle Koordinaten sind erfunden ---

    private val gpxTwoTracks = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
<wpt lat="10.5" lon="20.5"><name>Hut</name></wpt>
<trk><name>Ridden</name><trkseg>
<trkpt lat="10.000" lon="20.000"><ele>100</ele><time>2026-09-01T10:00:00Z</time></trkpt>
<trkpt lat="10.001" lon="20.000"><ele>105</ele><time>2026-09-01T10:00:10Z</time></trkpt>
<trkpt lat="10.002" lon="20.000"><ele>110</ele><time>2026-09-01T10:00:20Z</time></trkpt>
</trkseg></trk>
<trk><name>Planned</name><trkseg>
<trkpt lat="11.000" lon="21.000"></trkpt>
<trkpt lat="91.0" lon="21.001"></trkpt>
<trkpt lat="11.001" lon="21.001"></trkpt>
</trkseg></trk>
</gpx>"""

    @Test
    fun aGpxFileIsReadWithTheParserOfTheSystem() = runBlocking {
        val preview = importer.inspect(file("transfer_two.gpx", gpxTwoTracks), now, today)

        assertEquals(FileKind.GPX, preview.kind)
        assertEquals(listOf(DataPart.TRACKS), preview.parts.keys.toList())
        val plan = (preview.parts.getValue(DataPart.TRACKS) as PartPlan.Tracks).plan
        assertEquals(listOf("Ridden", "Planned"), plan.items.map { it.name })
        assertEquals(listOf(3, 2), plan.items.map { it.pointCount })
        assertEquals(1, plan.rejectedPoints)
        assertEquals(1, plan.ignoredWaypoints)
        assertEquals(1, plan.withoutTime)
        assertTrue(plan.items.none { it.duplicate })
    }

    @Test
    fun theTracksOfAGpxFileAreWrittenAndFoundAgainAsDuplicates() = runBlocking {
        val uri = file("transfer_again.gpx", gpxTwoTracks)
        val first = importer.inspect(uri, now, today)

        val result = importer.apply(first, setOf(DataPart.TRACKS))

        assertEquals(PartOutcome.Done(count = 2, skipped = 0), result.outcomes[DataPart.TRACKS])
        val saved = trackRepo.tracks.first().sortedBy { it.name }
        assertEquals(listOf("Planned", "Ridden"), saved.map { it.name })
        assertTrue(saved.all { it.source == TrackSource.IMPORTED })
        assertEquals(listOf(2, 3), saved.map { it.pointCount })
        val again = (importer.inspect(uri, now, today).parts.getValue(DataPart.TRACKS) as PartPlan.Tracks).plan
        assertEquals(listOf(true, true), again.items.map { it.duplicate })
        assertTrue(again.toInsert.isEmpty())
    }

    @Test
    fun anExportedTrackIsRecognizedWhenItComesBack() = runBlocking {
        val id = trackRepo.create("Own ride", TrackSource.IMPORTED)
        val points = (0 until 5).map { TrackPoint(1_790_000_000_000L + it * 5_000, 48.1234567891 + it * 0.0012345678, 11.9876543219 + it * 0.0009876543, 500f + it, null, null) }
        trackRepo.appendPoints(id, points)
        trackRepo.finish(id, points.last().timeMs, trackStats(points))
        val uri = TrackExporter(ctx, trackRepo).exportToGpx(id)

        val plan = (importer.inspect(uri, now, today).parts.getValue(DataPart.TRACKS) as PartPlan.Tracks).plan

        assertEquals(listOf(true), plan.items.map { it.duplicate })
        trackRepo.delete(listOf(id))
        val afterDelete = (importer.inspect(uri, now, today).parts.getValue(DataPart.TRACKS) as PartPlan.Tracks).plan
        assertEquals(listOf(false), afterDelete.items.map { it.duplicate })
    }

    @Test
    fun aPackageWithTracksGpxGivesTheTracksPart() = runBlocking {
        val preview = importer.inspect(packageFile("transfer_tracks.zip", true, "logbook.csv" to logbookCsv, "tracks.gpx" to gpxTwoTracks), now, today)

        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRACKS), preview.parts.keys.toList())
        assertEquals(2, (preview.parts.getValue(DataPart.TRACKS) as PartPlan.Tracks).plan.items.size)
        assertTrue(copies().isEmpty())
    }

    @Test
    fun aBrokenGpxInAPackageMakesOnlyTheTracksUnreadable() = runBlocking {
        val preview = importer.inspect(packageFile("transfer_broken_tracks.zip", true, "logbook.csv" to logbookCsv, "tracks.gpx" to "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\">"), now, today)

        assertEquals(PartPlan.Unreadable(UnreadableReason.FORMAT), preview.parts.getValue(DataPart.TRACKS))
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
    }

    @Test
    fun aGpxFileThatIsNoGpxIsRefused() = runBlocking {
        try {
            importer.inspect(file("transfer_broken.gpx", "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\">"), now, today)
            fail("expected GpxFileException")
        } catch (e: GpxFileException) {
            assertEquals(GpxFileException.Kind.NOT_GPX, e.kind)
        }
    }

    @Test
    fun aKmlFileIsNotRecognized() = runBlocking {
        try {
            importer.inspect(file("transfer_other.kml", "<?xml version=\"1.0\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"></kml>"), now, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.UNRECOGNIZED, e.kind)
        }
    }
}
