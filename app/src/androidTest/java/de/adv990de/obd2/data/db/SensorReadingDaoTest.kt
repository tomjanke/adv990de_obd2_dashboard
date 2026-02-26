package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorReadingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var dao: SensorReadingDao

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
        sessionDao = db.sessionDao()
        dao = db.sensorReadingDao()
    }

    @After fun teardown() = db.close()

    private suspend fun insertSession(): Long =
        sessionDao.insert(SessionEntity(startTime = System.currentTimeMillis()))

    @Test
    fun insertAll_cursorHasCorrectRowCount() = runBlocking {
        val sid = insertSession()
        dao.insertAll(listOf(
            SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 1500f),
            SensorReadingEntity(sessionId = sid, timestamp = 2L, pidId = "010D", value = 60f),
        ))
        val cursor = dao.getBySessionCursor(
            SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid))
        )
        assertEquals(2, cursor.count)
        cursor.close()
    }

    @Test
    fun deleteBySession_removesOnlyTargetSession() = runBlocking {
        val sid1 = insertSession()
        val sid2 = insertSession()
        dao.insert(SensorReadingEntity(sessionId = sid1, timestamp = 1L, pidId = "010C", value = 0f))
        dao.insert(SensorReadingEntity(sessionId = sid2, timestamp = 2L, pidId = "010C", value = 0f))
        dao.deleteBySession(sid1)
        val cursor1 = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid1)))
        val cursor2 = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid2)))
        assertEquals(0, cursor1.count)
        assertEquals(1, cursor2.count)
        cursor1.close(); cursor2.close()
    }

    @Test
    fun fkConstraint_insertWithInvalidSessionIdThrows() = runBlocking {
        var threw = false
        try {
            dao.insert(SensorReadingEntity(sessionId = 9999L, timestamp = 1L, pidId = "010C", value = 0f))
        } catch (_: Exception) { threw = true }
        assertTrue("Expected FK violation exception", threw)
    }

    @Test
    fun cursorColumns_containExpectedFields() = runBlocking {
        val sid = insertSession()
        dao.insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 1500f))
        val cursor = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid)))
        val columnNames = cursor.columnNames.toSet()
        assertTrue("pidId column missing", "pidId" in columnNames)
        assertTrue("value column missing", "value" in columnNames)
        assertTrue("timestamp column missing", "timestamp" in columnNames)
        cursor.close()
    }

    @Test
    fun insert_singleVsInsertAll_sameResult() = runBlocking {
        val sid = insertSession()
        dao.insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 100f))
        dao.insertAll(listOf(
            SensorReadingEntity(sessionId = sid, timestamp = 2L, pidId = "010D", value = 50f)
        ))
        val cursor = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid)))
        assertEquals(2, cursor.count)
        cursor.close()
    }
}
