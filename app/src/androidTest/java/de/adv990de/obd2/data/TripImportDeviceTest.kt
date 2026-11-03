package de.adv990de.obd2.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.tripimport.SessionSpan
import de.adv990de.obd2.tripimport.readTripCsv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringReader

/**
 * Der Fahrten-Import gegen eine echte (In-Memory-)Room-Datenbank und echte Dateien: Export → Import ergibt dieselben Messwerte in derselben Reihenfolge, ein Fehler mitten im
 * Schreiben laesst nichts zurueck, ein zweiter Import derselben Datei ist leer. Die JVM-Tests (`TripImporterTest`, `SessionRepositoryImportTest`) nehmen Attrappen und koennen das nicht zeigen.
 */
@RunWith(AndroidJUnit4::class)
class TripImportDeviceTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var importer: TripImporter
    private val files = ArrayList<File>()

    // Zwei Tage zurueck: weit genug von "jetzt" (Zukunftsgrenze) und innerhalb der 90 Tage der Aufbewahrung.
    private val start = System.currentTimeMillis() - 2L * 24 * 3_600_000

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(ctx, db)
        importer = TripImporter(ctx, repo)
        File(ctx.cacheDir, "exports").deleteRecursively()
    }

    @After
    fun teardown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun tempFile(name: String) = File(ctx.cacheDir, name).also { it.delete(); files += it }

    private fun countOf(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    /**
     * Eine beendete Fahrt mit den gegebenen Zeilen (Zeitstempel, PID, Wert, Herkunft) in dieser Reihenfolge. Beginn und Ende liegen wie bei einer echten Aufzeichnung eine Sekunde um
     * die Messwerte herum (die Fahrt beginnt vor dem ersten Zyklus und endet nach dem letzten).
     */
    private suspend fun recordedTrip(vararg rows: Array<Any?>): Long {
        val times = rows.map { it[0] as Long }
        val id = db.sessionDao().insert(SessionEntity(startTime = times.min() - 1_000, endTime = times.max() + 1_000))
        for (row in rows) {
            db.sensorReadingDao().insert(
                SensorReadingEntity(sessionId = id, timestamp = row[0] as Long, pidId = row[1] as String, value = (row[2] as Number).toFloat(), source = row[3] as String?),
            )
        }
        return id
    }

    private fun readingsOf(sessionId: Long): List<List<Any?>> = db.openHelper.readableDatabase
        .query("SELECT timestamp, pidId, value, source FROM sensor_readings WHERE sessionId = $sessionId ORDER BY id")
        .use { c -> generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getString(1), c.getFloat(2), if (c.isNull(3)) null else c.getString(3)) else null }.toList() }

    private fun sessions(): List<SessionEntity> = db.openHelper.readableDatabase
        .query("SELECT id, startTime, endTime, vehicleVin, dtcCodes FROM sessions ORDER BY id")
        .use { c -> generateSequence { if (c.moveToNext()) SessionEntity(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), c.getString(4)) else null }.toList() }

    private fun parse(csv: String) = readTripCsv(StringReader(csv), System.currentTimeMillis())

    // ── Spannen der vorhandenen Fahrten ───────────────────────────────────────

    @Test
    fun sessionSpans_aFinishedTripEndsAtItsEndTime_anUnfinishedOneAtItsLastReading_anEmptyOneAtItsStart() = runBlocking {
        val finished = db.sessionDao().insert(SessionEntity(startTime = 1_000, endTime = 5_000))
        val killed = db.sessionDao().insert(SessionEntity(startTime = 10_000, endTime = null))
        val empty = db.sessionDao().insert(SessionEntity(startTime = 20_000, endTime = null))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = finished, timestamp = 9_000, pidId = "010C", value = 1f))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = killed, timestamp = 12_000, pidId = "010C", value = 1f))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = killed, timestamp = 15_000, pidId = "010C", value = 1f))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = killed, timestamp = 13_000, pidId = "010C", value = 1f))

        val spans = repo.sessionSpans()

        assertEquals(
            "the end time wins over the readings; the maximum of an unfinished trip, whatever the order",
            listOf(SessionSpan(1_000, 5_000), SessionSpan(10_000, 15_000), SessionSpan(20_000, 20_000)),
            spans,
        )
        assertTrue(empty > 0)
    }

    @Test
    fun sessionSpans_withoutTripsIsEmpty() = runBlocking {
        assertEquals(emptyList<SessionSpan>(), repo.sessionSpans())
    }

    // ── Export → Import ───────────────────────────────────────────────────────

    @Test
    fun whatTheExportWritesTheImportReadsBack_sameReadingsSameOrder_andTheExportOfTheCopyIsTheSameFile() = runBlocking {
        val first = recordedTrip(
            arrayOf(start, "010C", 1612.5f, "ECU"), arrayOf(start + 1_000, "010D", 52.25f, "GPS"), arrayOf(start + 2_000, "GEAR", 3, "DERIVED"),
            arrayOf(start + 500, "0105", 79, null), // a clock jump backwards and a row from before 1.25.0: order and missing source stay
        )
        val second = recordedTrip(arrayOf(start + 3_600_000, "0142", 13.7f, "ECU"), arrayOf(start + 3_601_000, "VALUES_STATE", -1, "DERIVED"))
        val file = tempFile("trips-original.csv")
        repo.saveSessionsCsv(listOf(first, second), Uri.fromFile(file))
        repo.deleteSessions(listOf(first, second))
        assertEquals(0, countOf("sessions"))

        val plan = importer.read(Uri.fromFile(file), System.currentTimeMillis())
        assertEquals(2, plan.trips.size)
        assertEquals(6, plan.readingCount)
        assertEquals(2, importer.apply(plan))

        val imported = sessions()
        assertEquals(2, imported.size)
        assertEquals("start and end are the smallest and the largest timestamp, VIN and codes stay unknown", SessionEntity(imported[0].id, start, start + 2_000, null, null), imported[0])
        assertEquals(SessionEntity(imported[1].id, start + 3_600_000, start + 3_601_000, null, null), imported[1])
        assertEquals(
            listOf(listOf(start, "010C", 1612.5f, "ECU"), listOf(start + 1_000, "010D", 52.25f, "GPS"), listOf(start + 2_000, "GEAR", 3f, "DERIVED"), listOf(start + 500, "0105", 79f, null)),
            readingsOf(imported[0].id),
        )
        assertNull("a row without source stays without, it is not turned into ECU", readingsOf(imported[0].id).last()[3])

        val again = tempFile("trips-copy.csv")
        repo.saveSessionsCsv(imported.map { it.id }, Uri.fromFile(again))
        fun withoutSession(text: String) = text.lines().filter { it.isNotEmpty() }.map { it.substringBeforeLast(',') }
        assertEquals("the export of the imported copy is the original file but for the trip ids", withoutSession(file.readText()), withoutSession(again.readText()))
    }

    @Test
    fun theSameFileImportedAgainIsEmpty_everyTripIsAlreadyThere() = runBlocking {
        val a = recordedTrip(arrayOf(start, "010C", 1f, "ECU"), arrayOf(start + 60_000, "010C", 2f, "ECU"))
        val b = recordedTrip(arrayOf(start + 3_600_000, "010C", 3f, "ECU"))
        val file = tempFile("trips-twice.csv")
        repo.saveSessionsCsv(listOf(a, b), Uri.fromFile(file))
        repo.deleteSessions(listOf(a, b))
        importer.apply(importer.read(Uri.fromFile(file), System.currentTimeMillis()))

        val second = importer.read(Uri.fromFile(file), System.currentTimeMillis())

        assertTrue(second.trips.isEmpty())
        assertEquals(2, second.duplicateTrips)
        assertEquals("nothing was written by reading", 2, countOf("sessions"))
    }

    @Test
    fun theTripsOfTheAppItselfAreRecognisedAsDuplicates_theFileOfAnExportThatWasNeverDeleted() = runBlocking {
        val a = recordedTrip(arrayOf(start, "010C", 1f, "ECU"), arrayOf(start + 60_000, "010C", 2f, "ECU"))
        val file = tempFile("trips-never-deleted.csv")
        repo.saveSessionsCsv(listOf(a), Uri.fromFile(file))

        val plan = importer.read(Uri.fromFile(file), System.currentTimeMillis())

        assertTrue(plan.trips.isEmpty())
        assertEquals(1, plan.duplicateTrips)
    }

    @Test
    fun anImportNextToExistingTrips_leavesThemAloneAndAddsOnlyTheNewOnes() = runBlocking {
        val kept = recordedTrip(arrayOf(start, "010C", 7f, "ECU"))
        val csv = "timestamp,pid,value,name,source,session\n" +
            "${start + 10_000_000},010C,1.0,RPM,ECU,5\n${start + 10_001_000},010C,2.0,RPM,ECU,5\n" +
            "$start,010C,9.0,RPM,ECU,6\n" // touches the trip that is already there

        val plan = importer.read(fileOf("mixed.csv", csv), System.currentTimeMillis())
        importer.apply(plan)

        assertEquals(1, plan.duplicateTrips)
        assertEquals(2, sessions().size)
        assertEquals(listOf(listOf(start, "010C", 7f, "ECU")), readingsOf(kept))
        assertEquals(3, countOf("sensor_readings"))
    }

    private fun fileOf(name: String, text: String): Uri = Uri.fromFile(tempFile(name).also { it.writeText(text) })

    // ── Alles oder nichts ─────────────────────────────────────────────────────

    @Test
    fun aFailureInTheMiddleOfTheWriting_leavesNeitherTripsNorReadings() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_on_boom BEFORE INSERT ON sensor_readings WHEN NEW.pidId = 'BOOM' BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        val untouched = recordedTrip(arrayOf(start - 100_000_000, "010C", 1f, "ECU"))
        val csv = "timestamp,pid,value,name,source,session\n" +
            "$start,010C,1.0,RPM,ECU,1\n${start + 1_000},010C,2.0,RPM,ECU,1\n" +
            "${start + 5_000_000},010C,3.0,RPM,ECU,2\n${start + 5_001_000},BOOM,4.0,RPM,ECU,2\n${start + 5_002_000},010C,5.0,RPM,ECU,2\n"
        val plan = importer.read(fileOf("boom.csv", csv), System.currentTimeMillis())
        assertEquals(2, plan.trips.size)

        assertThrows(SQLiteException::class.java) { runBlocking { importer.apply(plan) } }

        assertEquals("the first trip, written before the failure, is rolled back too", listOf(untouched), sessions().map { it.id })
        assertEquals(1, countOf("sensor_readings"))
    }

    // ── Groesse ───────────────────────────────────────────────────────────────

    @Test
    fun aHundredAndFiftyThousandRowsAreImportedInOneTransactionInReasonableTime() = runBlocking {
        val rows = 150_000
        val csv = StringBuilder("timestamp,pid,value,name,source,session\n")
        for (i in 0 until rows) csv.append(start + i * 100L).append(if (i % 2 == 0) ",010C," else ",0105,").append(i % 5000).append(".0,n,ECU,").append(i / 50_000).append('\n')
        val plan = importer.read(fileOf("big.csv", csv.toString()), System.currentTimeMillis())
        assertEquals(3, plan.trips.size)

        val began = System.nanoTime()
        importer.apply(plan)
        val seconds = (System.nanoTime() - began) / 1e9

        assertEquals(rows, countOf("sensor_readings"))
        assertEquals(3, countOf("sessions"))
        android.util.Log.i("TripImportDeviceTest", "$rows rows imported in %.1f s".format(seconds))
        assertTrue("the import took $seconds s", seconds < 60)
    }

    @Test
    fun theLargestFileOfTheRetention_rowsAndMemory_isReadWithoutTroubleEvenAsTextOfTheExportFormat() {
        val csv = StringBuilder("timestamp,pid,value,name,source,session\n")
        for (i in 0 until 200_000) csv.append(start + i * 10L).append(",010C,").append(i).append(".0,Engine RPM,ECU,1\n")

        val parsed = parse(csv.toString())

        assertEquals(200_000, parsed.rowCount)
        assertEquals(1, parsed.trips.size)
    }
}
