package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
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
            // Fremdschluessel: Rooms generierter onOpen schaltet sie ein — kein nachgebauter Callback (B36,
            // AppDatabaseBootstrapTest weist es fuer den Produktions-Builder nach).
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
    fun deleteBySessions_removesOnlyTargetSession() = runBlocking {
        val sid1 = insertSession()
        val sid2 = insertSession()
        dao.insert(SensorReadingEntity(sessionId = sid1, timestamp = 1L, pidId = "010C", value = 0f))
        dao.insert(SensorReadingEntity(sessionId = sid2, timestamp = 2L, pidId = "010C", value = 0f))
        dao.deleteBySessions(listOf(sid1))
        val cursor1 = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid1)))
        val cursor2 = dao.getBySessionCursor(SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid2)))
        assertEquals(0, cursor1.count)
        assertEquals(1, cursor2.count)
        cursor1.close(); cursor2.close()
    }

    @Test
    fun deleteBySessions_removesSeveralSessionsAndKeepsTheRest() = runBlocking {
        val sid1 = insertSession()
        val sid2 = insertSession()
        val sid3 = insertSession()
        listOf(sid1, sid2, sid3).forEach { sid ->
            dao.insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 0f))
        }
        dao.deleteBySessions(listOf(sid1, sid3))
        fun count(sid: Long) = dao.getBySessionCursor(
            SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid))
        ).use { it.count }
        assertEquals(0, count(sid1))
        assertEquals(1, count(sid2))
        assertEquals(0, count(sid3))
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
        val cursor = dao.getBySessionCursor(
            SimpleSQLiteQuery("SELECT pidId, value, timestamp FROM sensor_readings WHERE sessionId = ? ORDER BY timestamp ASC", arrayOf(sid))
        )
        assertEquals(2, cursor.count)
        // Verify row 1 (inserted via insert())
        cursor.moveToFirst()
        assertEquals("010C", cursor.getString(cursor.getColumnIndexOrThrow("pidId")))
        assertEquals(100f, cursor.getFloat(cursor.getColumnIndexOrThrow("value")), 0.001f)
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
        // Verify row 2 (inserted via insertAll())
        cursor.moveToNext()
        assertEquals("010D", cursor.getString(cursor.getColumnIndexOrThrow("pidId")))
        assertEquals(50f, cursor.getFloat(cursor.getColumnIndexOrThrow("value")), 0.001f)
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
        cursor.close()
    }
}
