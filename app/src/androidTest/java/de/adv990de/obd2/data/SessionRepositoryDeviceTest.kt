package de.adv990de.obd2.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.PidRegistry
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
            "timestamp,pid,value,name,source\n300,010C,1.5,RPM,ECU\n100,0105,1.5,Coolant Temp,GPS\n200,01FF,1.5,,\n",
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
            "timestamp,pid,value,name,source\n" +
                "100,010C,3000.0,RPM,ECU\n" +
                "100,GEAR,3.0,Gear,DERIVED\n" +
                "200,GEAR,0.0,Gear,DERIVED\n" +
                "300,GEAR,-1.0,Gear,DERIVED\n",
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
            "timestamp,pid,value,name,source\n" +
                "100,OIL_BAR,-1.0,Oil bar,DERIVED\n" +
                "200,OIL_BAR,0.0,Oil bar,DERIVED\n" +
                "300,OIL_BAR,1.0,Oil bar,DERIVED\n" +
                "400,OIL_BAR,2.0,Oil bar,DERIVED\n",
            exportDir.listFiles()!!.single().readText()
        )
    }
}

