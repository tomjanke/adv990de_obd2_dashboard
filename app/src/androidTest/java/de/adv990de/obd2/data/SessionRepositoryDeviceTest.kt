package de.adv990de.obd2.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.SessionMetaRow
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.transfer.TripMeta
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.dashboard.GearReading
import de.adv990de.obd2.ui.dashboard.OilBarColor
import de.adv990de.obd2.ui.dashboard.gearSensorValue
import de.adv990de.obd2.ui.dashboard.oilBarSensorValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SessionRepository] against a real (in-memory) Room database. The JVM tests use mocks, which
 * cannot show what Room's suspend DAOs do in a cancelled coroutine — this class can.
 *
 * Note: Room's generated `onOpen` enables `PRAGMA foreign_keys` on every connection — also for this in-memory
 * database (proven for the production builder by `AppDatabaseBootstrapTest`). Until 1.24.4 this comment claimed
 * the opposite (it assumed the callback in `AppModule` was what enabled it); the explicit readings delete of
 * [SessionRepository.deleteSessions] and the cascade therefore both apply here, as in production.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryDeviceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(ctx, db)
    }

    @After
    fun teardown() = db.close()

    private fun readingCount(sessionId: Long): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM sensor_readings WHERE sessionId = $sessionId")
        .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun exportableSessionIdsAreTheTripsWithReadingsWithoutTheRunningOne() = runBlocking {
        suspend fun trip(withReadings: Boolean): Long {
            val id = db.sessionDao().insert(SessionEntity(startTime = 1_000, endTime = 2_000))
            if (withReadings) db.sensorReadingDao().insert(SensorReadingEntity(sessionId = id, timestamp = 1_500, pidId = "010C", value = 1f, source = null))
            return id
        }
        val withRows = trip(true)
        trip(false)
        val other = trip(true)
        assertEquals(listOf(withRows, other), repo.exportableSessionIds())
        val running = repo.startSession()
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = running, timestamp = 1_600, pidId = "010C", value = 1f, source = null))
        assertEquals(listOf(withRows, other), repo.exportableSessionIds())
    }

    @Test
    fun exportableReadingCountIsTheRowsOfTheFinishedTripsWithoutTheRunningOne() = runBlocking {
        assertEquals("leere Datenbank", 0L, repo.exportableReadingCount())
        suspend fun trip(readings: Int): Long {
            val id = db.sessionDao().insert(SessionEntity(startTime = 1_000, endTime = 2_000))
            repeat(readings) { db.sensorReadingDao().insert(SensorReadingEntity(sessionId = id, timestamp = 1_500L + it, pidId = "010C", value = 1f, source = null)) }
            return id
        }
        trip(3)
        trip(0)
        trip(4)
        assertEquals(7L, repo.exportableReadingCount())
        val running = repo.startSession()
        repeat(5) { db.sensorReadingDao().insert(SensorReadingEntity(sessionId = running, timestamp = 1_600L + it, pidId = "010C", value = 1f, source = null)) }
        assertEquals("die laufende Aufzeichnung zaehlt nicht", 7L, repo.exportableReadingCount())
        repo.endSession(running)
        assertEquals("nach ihrem Ende schon", 12L, repo.exportableReadingCount())
    }

    @Test
    fun startSession_storesTheFaultCodesWithTheTrip_andTheyReadBackInAllThreeStates() = runBlocking {
        val withCodes = repo.startSession("VIN", listOf(DtcEntry("P0301", false), DtcEntry("P0420", true)))
        val noneFound = repo.startSession("VIN", emptyList())
        val notRecorded = repo.startSession("VIN")

        assertEquals("P0301,P0420?", db.sessionDao().getById(withCodes)!!.dtcCodes)
        assertEquals(listOf(DtcEntry("P0301", false), DtcEntry("P0420", true)), decodeSessionDtcs(db.sessionDao().getById(withCodes)!!.dtcCodes))
        assertEquals("gelesen, nichts gefunden: leer", "", db.sessionDao().getById(noneFound)!!.dtcCodes)
        assertEquals(emptyList<DtcEntry>(), decodeSessionDtcs(db.sessionDao().getById(noneFound)!!.dtcCodes))
        assertNull("nicht erfasst: null", db.sessionDao().getById(notRecorded)!!.dtcCodes)
        assertNull(decodeSessionDtcs(db.sessionDao().getById(notRecorded)!!.dtcCodes))
    }

    @Test
    fun metaByIds_readsTheVinAndTheCodesOfTheAskedTripsOnly_in500IdsAtOnce() = runBlocking {
        val withBoth = db.sessionDao().insert(SessionEntity(startTime = 1_000, vehicleVin = "TESTVIN0000000001", dtcCodes = "P0301,P0420?"))
        val noneFound = db.sessionDao().insert(SessionEntity(startTime = 2_000, dtcCodes = ""))
        val notRecorded = db.sessionDao().insert(SessionEntity(startTime = 3_000))
        db.sessionDao().insert(SessionEntity(startTime = 4_000, vehicleVin = "TESTVIN0000000002"))

        // Asked for 500 ids, among them the first two trips, but neither the third (not recorded) nor the fourth.
        val rows = db.sessionDao().metaByIds((1L..500L).filter { it <= noneFound || it > notRecorded + 1 })

        assertEquals(
            setOf(SessionMetaRow(withBoth, "TESTVIN0000000001", "P0301,P0420?"), SessionMetaRow(noneFound, null, "")),
            rows.toSet(),
        )
    }

    @Test
    fun tripMeta_keepsTheThreeStatesOfTheCodesApart_acrossMoreIdsThanOneQueryTakes() = runBlocking {
        val withBoth = db.sessionDao().insert(SessionEntity(startTime = 1_000, vehicleVin = "TESTVIN0000000001", dtcCodes = "P0301,P0420?"))
        val noneFound = db.sessionDao().insert(SessionEntity(startTime = 2_000, dtcCodes = ""))
        val notRecorded = db.sessionDao().insert(SessionEntity(startTime = 3_000))

        val meta = repo.tripMeta((1L..1_200L).toList())

        assertEquals(TripMeta("TESTVIN0000000001", "P0301,P0420?"), meta[withBoth])
        assertEquals(TripMeta(null, ""), meta[noneFound])
        assertEquals(TripMeta(null, null), meta[notRecorded])
        assertEquals(3, meta.size)
    }

    @Test
    fun writeTripMetaCsv_writesOnlyTheTripsWithADetail_withTheNumbersOfTheTripsFile() = runBlocking {
        val withBoth = db.sessionDao().insert(SessionEntity(startTime = 1_000, vehicleVin = "TESTVIN0000000001", dtcCodes = "P0301,P0420?"))
        val notRecorded = db.sessionDao().insert(SessionEntity(startTime = 3_000))
        val noneFound = db.sessionDao().insert(SessionEntity(startTime = 2_000, dtcCodes = ""))

        val out = java.io.StringWriter()
        repo.writeTripMetaCsv(out, listOf(withBoth, notRecorded, noneFound))

        assertEquals(
            "session,vin,dtcs_recorded,dtc_codes\n$withBoth,TESTVIN0000000001,1,\"P0301,P0420?\"\n$noneFound,,1,\n",
            out.toString(),
        )
    }

    @Test
    fun deletingATrip_takesItsFaultCodesWithIt() = runBlocking {
        val id = repo.startSession("VIN", listOf(DtcEntry("P0301", false)))
        repo.endSession(id) // die laufende Aufzeichnung ist unloeschbar (deleteSessions ueberspringt sie)

        repo.deleteSessions(listOf(id))

        assertNull("the codes live in the trip row: nothing is left behind", db.sessionDao().getById(id))
    }

    @Test
    fun endSession_inACancelledCoroutine_stillSetsTheEndTime() = runBlocking {
        val id = repo.startSession("VIN")

        // The pipeline ends the trip in a finally block of a coroutine that is being cancelled.
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                repo.endSession(id)
            }
        }
        caller.cancelAndJoin()

        assertNotNull("the trip must have an endTime", db.sessionDao().getById(id)!!.endTime)
        assertNull("and it is no longer the active trip", repo.activeSessionId.value)
    }

    @Test
    fun discardSession_inACancelledCoroutine_stillRemovesTheTrip() = runBlocking {
        val id = repo.startSession()
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = id, timestamp = 1L, pidId = "010C", value = 0f))

        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                repo.discardSession(id)
            }
        }
        caller.cancelAndJoin()

        assertNull(db.sessionDao().getById(id))
        assertEquals(0, readingCount(id))
    }

    @Test
    fun deleteSessions_keepsTheRecordingAndRemovesTheReadingsOfTheOthers() = runBlocking {
        val finished = db.sessionDao().insert(SessionEntity(startTime = 1000L, endTime = 2000L))
        val other = db.sessionDao().insert(SessionEntity(startTime = 3000L, endTime = 4000L))
        val recording = repo.startSession()
        listOf(finished, other, recording).forEach { sid ->
            db.sensorReadingDao().insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 0f))
        }

        repo.deleteSessions(listOf(finished, recording))

        assertEquals(listOf(other, recording), repo.sessionIds.first())
        assertEquals(0, readingCount(finished))
        assertEquals("the recording keeps its readings", 1, readingCount(recording))
        assertEquals(1, readingCount(other))
    }

    @Test
    fun deleteSessions_worksAcrossSeveralChunks() = runBlocking {
        val ids = (1..120).map { db.sessionDao().insert(SessionEntity(startTime = it.toLong())) }

        repo.deleteSessions(ids)

        assertEquals(emptyList<Long>(), repo.sessionIds.first())
    }

    // ── CSV export (QS C: DAT-06/07/09) ───────────────────────────────────────────────────────────────

    @Test
    fun exportQuery_needsNoSortStep_soMemoryStaysConstantOnLongTrips() {
        // DAT-09: ORDER BY timestamp made SQLite read and sort ALL rows of the trip before writing the first one.
        // ORDER BY id is satisfied by the sessionId index (it carries the rowid) — no temp b-tree.
        val plan = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $EXPORT_QUERY_SQL", arrayOf<Any>(1L))
            .use { c ->
                val detail = c.getColumnIndexOrThrow("detail")
                generateSequence { if (c.moveToNext()) c.getString(detail) else null }.toList()
            }
        val text = plan.joinToString(" | ")

        assertEquals("kein Sortierlauf: $text", false, text.contains("TEMP B-TREE", ignoreCase = true))
        assertEquals("nutzt den Index auf sessionId: $text", true, text.contains("index_sensor_readings_sessionId"))
    }

    // ── Min/Max je Fahrt (F11) ────────────────────────────────────────────────────────────────────────

    @Test
    fun statsQuery_needsNoSortStep_andUsesTheSessionIndex() {
        // Wie die Export-Abfrage: ORDER BY id wird vom Index auf sessionId erfuellt (er traegt die rowid) — kein Sortierlauf, auch bei langen Fahrten.
        val plan = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $SESSION_STATS_QUERY_SQL", arrayOf<Any>(1L))
            .use { c ->
                val detail = c.getColumnIndexOrThrow("detail")
                generateSequence { if (c.moveToNext()) c.getString(detail) else null }.toList()
            }
        val text = plan.joinToString(" | ")

        assertEquals("kein Sortierlauf: $text", false, text.contains("TEMP B-TREE", ignoreCase = true))
        assertEquals("nutzt den Index auf sessionId: $text", true, text.contains("index_sensor_readings_sessionId"))
    }

    @Test
    fun sessionStats_foldsTheRealRowsOfOneTrip_andNothingOfOtherTripsOrOtherValues() = runBlocking {
        val trip = db.sessionDao().insert(SessionEntity(startTime = 1_000L))
        val other = db.sessionDao().insert(SessionEntity(startTime = 2_000L))
        fun row(session: Long, seconds: Int, pid: String, value: Float, source: String? = "ECU") =
            SensorReadingEntity(sessionId = session, timestamp = seconds * 1_000L, pidId = pid, value = value, source = source)
        db.sensorReadingDao().insertAll(
            buildList {
                for (s in 0..40) {
                    add(row(trip, s, "010C", 1_200f))
                    add(row(trip, s, "0105", 20f + s))
                    add(row(trip, s, "010D", 250f)) // die Geschwindigkeit gehoert nicht dazu
                    add(row(trip, s, "GEAR", 6f, source = "DERIVED"))
                }
                add(row(trip, 3, "0142", 7.8f)) // Anlasser: zaehlt nicht
                add(row(trip, 20, "0142", 12.3f))
                add(row(trip, 40, "0142", 14.1f))
                // Eine andere Fahrt mit anderen Werten darf nicht einfliessen.
                for (s in 0..40) { add(row(other, s, "010C", 1_200f)); add(row(other, s, "0105", 200f)) }
                add(row(other, 30, "0142", 5.0f))
            }.sortedBy { it.timestamp }, // in der Reihenfolge der Zeit, wie der Recorder schreibt
        )

        val stats = repo.sessionStats(trip)

        assertEquals(MinMax(20f, 60f), stats.coolant)
        assertEquals(MinMax(12.3f, 14.1f), stats.voltage)
        assertEquals(SessionStats(MinMax(200f, 200f), MinMax(5.0f, 5.0f)), repo.sessionStats(other))
    }

    @Test
    fun sessionStats_ofATripWithoutReadings_hasNoValues() = runBlocking {
        val trip = db.sessionDao().insert(SessionEntity(startTime = 1_000L))

        assertEquals(SessionStats(coolant = null, voltage = null), repo.sessionStats(trip))
    }

    @Test
    fun exportToCsv_writesTheTripsRowsInRecordingOrder_andNothingOfOtherTrips() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exportDir = java.io.File(ctx.cacheDir, "exports").also { it.deleteRecursively() }
        val trip = repo.startSession()
        val other = repo.startSession()
        // Recording order differs from timestamp order (a clock jump during the trip): the file keeps the recording order.
        // The source column: ECU, GPS, and NULL for a row recorded before 1.25.0 (empty in the file, never guessed).
        for ((ts, pid, source) in listOf(Triple(300L, "010C", "ECU"), Triple(100L, "0105", "GPS"), Triple(200L, "01FF", null))) {
            db.sensorReadingDao().insert(SensorReadingEntity(sessionId = trip, timestamp = ts, pidId = pid, value = 1.5f, source = source))
        }
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = other, timestamp = 1L, pidId = "010C", value = 9f))

        repo.exportToCsv(trip)

        val file = exportDir.listFiles()!!.single()
        assertEquals(
            "timestamp,pid,value,name,source,session\n300,010C,1.5,RPM,ECU,$trip\n100,0105,1.5,Coolant Temp,GPS,$trip\n200,01FF,1.5,,,$trip\n",
            file.readText()
        )
    }

    @Test
    fun theDisplayedGear_isWrittenThroughTheRepository_andExportedWithItsNameAndSource() = runBlocking {
        // 2026-10-14: the gear box's gear is a row like any other — pid GEAR, 1-6 = gear, 0 = N, -1 = not determinable, source DERIVED.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exportDir = java.io.File(ctx.cacheDir, "exports").also { it.deleteRecursively() }
        val trip = repo.startSession()

        repo.writeReadings(trip, listOf(SensorValue(PidRegistry.RPM, 3000f, timestamp = 100L)))
        repo.writeReadings(trip, listOf(gearSensorValue(GearReading(gear = 3, isNeutral = false), timestamp = 100L)))
        repo.writeReadings(trip, listOf(gearSensorValue(GearReading(gear = null, isNeutral = true), timestamp = 200L)))
        repo.writeReadings(trip, listOf(gearSensorValue(GearReading.Unknown, timestamp = 300L)))
        repo.exportToCsv(trip)

        assertEquals(
            "timestamp,pid,value,name,source,session\n" +
                "100,010C,3000.0,RPM,ECU,$trip\n" +
                "100,GEAR,3.0,Gear,DERIVED,$trip\n" +
                "200,GEAR,0.0,Gear,DERIVED,$trip\n" +
                "300,GEAR,-1.0,Gear,DERIVED,$trip\n",
            exportDir.listFiles()!!.single().readText()
        )
    }

    @Test
    fun theDisplayedOilBarColour_isWrittenThroughTheRepository_andExportedWithItsNameAndSource() = runBlocking {
        // 2026-10-14: the oil bar's colour is a row like any other — pid OIL_BAR, 0 = red, 1 = yellow, 2 = green, -1 = grey, source DERIVED.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exportDir = java.io.File(ctx.cacheDir, "exports").also { it.deleteRecursively() }
        val trip = repo.startSession()

        repo.writeReadings(trip, listOf(oilBarSensorValue(null, timestamp = 100L)))
        repo.writeReadings(trip, listOf(oilBarSensorValue(OilBarColor.Red, timestamp = 200L)))
        repo.writeReadings(trip, listOf(oilBarSensorValue(OilBarColor.Yellow, timestamp = 300L)))
        repo.writeReadings(trip, listOf(oilBarSensorValue(OilBarColor.Green, timestamp = 400L)))
        repo.exportToCsv(trip)

        assertEquals(
            "timestamp,pid,value,name,source,session\n" +
                "100,OIL_BAR,-1.0,Oil bar,DERIVED,$trip\n" +
                "200,OIL_BAR,0.0,Oil bar,DERIVED,$trip\n" +
                "300,OIL_BAR,1.0,Oil bar,DERIVED,$trip\n" +
                "400,OIL_BAR,2.0,Oil bar,DERIVED,$trip\n",
            exportDir.listFiles()!!.single().readText()
        )
    }

    @Test
    fun exportSessionsToCsv_writesTheGivenTripsIntoOneFile_oneHeader_eachRowWithItsTripId() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exportDir = java.io.File(ctx.cacheDir, "exports").also { it.deleteRecursively() }
        val first = repo.startSession().also { repo.endSession(it) }
        val second = repo.startSession().also { repo.endSession(it) }
        val recording = repo.startSession() // laeuft noch und wird nicht uebergeben
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = first, timestamp = 100L, pidId = "010C", value = 900f, source = "ECU"))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = second, timestamp = 200L, pidId = "0142", value = 13.6f, source = "ECU"))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = second, timestamp = 300L, pidId = "0105", value = 88f, source = "ECU"))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = recording, timestamp = 400L, pidId = "010C", value = 1f, source = "ECU"))

        repo.exportSessionsToCsv(listOf(first, second))

        val file = exportDir.listFiles()!!.single()
        assertEquals(true, file.name.startsWith("session_all_"))
        assertEquals(
            "timestamp,pid,value,name,source,session\n" +
                "100,010C,900.0,RPM,ECU,$first\n" +
                "200,0142,13.6,Control Module Voltage,ECU,$second\n" +
                "300,0105,88.0,Coolant Temp,ECU,$second\n",
            file.readText(),
        )
    }

    @Test
    fun saveSessionsCsv_writesTheSameCsvIntoTheFileTheUserChose_andLeavesNothingInTheCache() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exportDir = java.io.File(ctx.cacheDir, "exports").also { it.deleteRecursively() }
        // Eine Datei-Uri steht fuer das Ergebnis des Dateidialogs (CreateDocument); der Systemdialog selbst laesst sich im Test nicht bedienen.
        val target = java.io.File(ctx.cacheDir, "saved-by-the-user.csv").also { it.delete() }
        val first = repo.startSession().also { repo.endSession(it) }
        val second = repo.startSession().also { repo.endSession(it) }
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = first, timestamp = 100L, pidId = "010C", value = 900f, source = "ECU"))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = second, timestamp = 200L, pidId = "010C", value = 950f, source = "ECU"))

        repo.saveSessionsCsv(listOf(first, second), android.net.Uri.fromFile(target))

        assertEquals(
            "timestamp,pid,value,name,source,session\n100,010C,900.0,RPM,ECU,$first\n200,010C,950.0,RPM,ECU,$second\n",
            target.readText(),
        )
        assertEquals("die Zwischendatei im Cache ist weg", emptyList<String>(), exportDir.listFiles().orEmpty().map { it.name })
        target.delete()
    }
}
