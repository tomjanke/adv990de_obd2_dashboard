package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.logbookCsvHeader
import de.adv990de.obd2.logbook.logbookCsvLine
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.toJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val files = ArrayList<File>()
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = TransferImporter(ctx, TripImporter(ctx, SessionRepository(ctx, db)), LogbookImporter(ctx, VehicleLogRepository(db.vehicleLogDao())), DtcHistoryRepository(db.dtcHistoryDao(), AppLogRepository(db.appLogDao())))
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
}
