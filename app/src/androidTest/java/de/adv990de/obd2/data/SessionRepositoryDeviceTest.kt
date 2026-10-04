package de.adv990de.obd2.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
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
 * Note: the database is built WITHOUT the `foreign_keys` pragma, so the explicit readings delete of
 * [SessionRepository.deleteSessions] is what removes the readings here (in production the cascade
 * would do the same).
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
}
