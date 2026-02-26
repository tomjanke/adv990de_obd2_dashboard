package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var readingDao: SensorReadingDao

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
        readingDao = db.sensorReadingDao()
    }

    @After fun teardown() = db.close()

    @Test
    fun insert_returnsPositiveGeneratedId() = runBlocking {
        val id = sessionDao.insert(SessionEntity(startTime = 1000L))
        assertTrue("Expected positive generated ID, got $id", id > 0)
    }

    @Test
    fun update_changesEndTime() = runBlocking {
        val id = sessionDao.insert(SessionEntity(startTime = 1000L))
        val updated = SessionEntity(id = id, startTime = 1000L, endTime = 9000L)
        sessionDao.update(updated)
        val fetched = sessionDao.getById(id)
        assertNotNull(fetched)
        assertEquals(9000L, fetched!!.endTime)
    }

    @Test
    fun getById_returnsNullForUnknownId() = runBlocking {
        assertNull(sessionDao.getById(99999L))
    }

    @Test
    fun deleteOlderThan_boundary() = runBlocking {
        sessionDao.insert(SessionEntity(startTime = 1000L))
        sessionDao.insert(SessionEntity(startTime = 3000L))
        sessionDao.deleteOlderThan(cutoffMs = 2000L)
        val sessions = sessionDao.getAll().first()
        assertEquals(1, sessions.size)
        assertEquals(3000L, sessions[0].startTime)
    }

    @Test
    fun cascade_deletingSessionRemovesSensorReadings() = runBlocking {
        val sid = sessionDao.insert(SessionEntity(startTime = 1000L))
        readingDao.insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 0f))
        val session = sessionDao.getById(sid)!!
        sessionDao.delete(session)
        val cursor = readingDao.getBySessionCursor(
            SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid))
        )
        assertEquals("Cascade delete failed: readings still exist", 0, cursor.count)
        cursor.close()
    }

    @Test
    fun pagingSource_notEmptyAfterInsert() = runBlocking {
        sessionDao.insert(SessionEntity(startTime = 1000L))
        val source = sessionDao.pagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        assertEquals(1, (result as PagingSource.LoadResult.Page).data.size)
    }
}
