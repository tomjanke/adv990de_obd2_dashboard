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
    fun idsOlderThan_boundary() = runBlocking {
        val old = sessionDao.insert(SessionEntity(startTime = 1000L))
        sessionDao.insert(SessionEntity(startTime = 2000L)) // exactly at the cutoff: not older
        sessionDao.insert(SessionEntity(startTime = 3000L))
        assertEquals(listOf(old), sessionDao.idsOlderThan(cutoffMs = 2000L))
    }

    @Test
    fun idsOlderThan_isEmptyWhenNothingIsOld() = runBlocking {
        sessionDao.insert(SessionEntity(startTime = 3000L))
        assertEquals(emptyList<Long>(), sessionDao.idsOlderThan(cutoffMs = 2000L))
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
    fun observeIds_emitsEveryIdOldestFirstAndFollowsChanges() = runBlocking {
        assertEquals(emptyList<Long>(), sessionDao.observeIds().first())

        val first = sessionDao.insert(SessionEntity(startTime = 3000L))
        val second = sessionDao.insert(SessionEntity(startTime = 1000L))

        assertEquals(listOf(first, second), sessionDao.observeIds().first())

        sessionDao.deleteByIds(listOf(first))
        assertEquals(listOf(second), sessionDao.observeIds().first())
    }

    @Test
    fun deleteByIds_removesOnlyTheGivenSessionsAndCascadesTheirReadings() = runBlocking {
        val a = sessionDao.insert(SessionEntity(startTime = 1000L))
        val b = sessionDao.insert(SessionEntity(startTime = 2000L))
        val c = sessionDao.insert(SessionEntity(startTime = 3000L))
        listOf(a, b, c).forEach { sid ->
            readingDao.insert(SensorReadingEntity(sessionId = sid, timestamp = 1L, pidId = "010C", value = 0f))
        }

        sessionDao.deleteByIds(listOf(a, c))

        assertEquals(listOf(b), sessionDao.observeIds().first())
        fun readings(sid: Long) = readingDao.getBySessionCursor(
            SimpleSQLiteQuery("SELECT * FROM sensor_readings WHERE sessionId = ?", arrayOf(sid))
        ).use { it.count }
        assertEquals("cascade: readings of a deleted trip are gone", 0, readings(a))
        assertEquals("readings of a kept trip stay", 1, readings(b))
        assertEquals(0, readings(c))
    }

    @Test
    fun deleteByIds_withAnUnknownIdDoesNothing() = runBlocking {
        val a = sessionDao.insert(SessionEntity(startTime = 1000L))

        sessionDao.deleteByIds(listOf(9999L))

        assertEquals(listOf(a), sessionDao.observeIds().first())
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
