package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AppLogDao

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appLogDao()
    }

    @After fun teardown() = db.close()

    @Test
    fun insertAndRetrieve() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "hello"))
        val entries = dao.getAllNewestFirst().first()
        assertEquals(1, entries.size)
        assertEquals("hello", entries[0].message)
    }

    @Test
    fun newestFirst_orderCorrect() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "older"))
        dao.insert(AppLogEntity(timestamp = 2000L, level = "INFO", message = "newer"))
        val entries = dao.getAllNewestFirst().first()
        assertEquals("newer", entries[0].message)
        assertEquals("older", entries[1].message)
    }

    @Test
    fun deleteOlderThan_removesOldKeepsNew() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "old"))
        dao.insert(AppLogEntity(timestamp = 3000L, level = "INFO", message = "new"))
        dao.deleteOlderThan(cutoffMs = 2000L)
        val entries = dao.getAllNewestFirst().first()
        assertEquals(1, entries.size)
        assertEquals("new", entries[0].message)
    }

    @Test
    fun deleteAll_emptyResult() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "a"))
        dao.deleteAll()
        assertTrue(dao.getAllNewestFirst().first().isEmpty())
    }

    @Test
    fun flowEmitsAfterInsert() = runBlocking {
        val before = dao.getAllNewestFirst().first()
        assertTrue(before.isEmpty())
        dao.insert(AppLogEntity(timestamp = 1000L, level = "WARN", message = "x"))
        val after = dao.getAllNewestFirst().first()
        assertEquals(1, after.size)
    }
}
