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
    fun deleteBeyondNewest_keepsExactlyTheNewestRows() = runBlocking {
        (1..10).forEach { dao.insert(AppLogEntity(timestamp = it * 1000L, level = "INFO", message = "m$it")) }

        dao.deleteBeyondNewest(keep = 3)

        val left = dao.getAllNewestFirst().first().map { it.message }
        assertEquals(listOf("m10", "m9", "m8"), left)
    }

    @Test
    fun deleteBeyondNewest_withFewerRowsThanTheCapDeletesNothing() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "only"))

        dao.deleteBeyondNewest(keep = 1000)

        assertEquals(1, dao.getAllNewestFirst().first().size)
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
    fun deleteUpTo_removesEverythingUpToAndIncludingTheId_keepsNewerRows() = runBlocking {
        for (i in 1..5) dao.insert(AppLogEntity(timestamp = i * 1000L, level = "INFO", message = "m$i"))
        val ids = dao.getAllNewestFirst().first().map { it.id }.sorted()   // ascending insertion ids

        dao.deleteUpTo(ids[2])                                              // drops the first three

        val remaining = dao.getAllNewestFirst().first()
        assertEquals(listOf("m5", "m4"), remaining.map { it.message })
    }

    @Test
    fun deleteUpTo_keepsLinesWrittenAfterTheIdWasChosen() = runBlocking {
        dao.insert(AppLogEntity(timestamp = 1000L, level = "INFO", message = "before"))
        val maxAtPressTime = dao.getAllNewestFirst().first().maxOf { it.id }
        dao.insert(AppLogEntity(timestamp = 2000L, level = "WARN", message = "written inside the undo window"))

        dao.deleteUpTo(maxAtPressTime)

        assertEquals(listOf("written inside the undo window"), dao.getAllNewestFirst().first().map { it.message })
    }

    @Test
    fun deleteUpTo_onAnEmptyTable_isANoOp() = runBlocking {
        dao.deleteUpTo(100L)
        assertTrue(dao.getAllNewestFirst().first().isEmpty())
    }

    @Test
    fun deleteUpTo_alsoRemovesRowsBeyondTheVisibleLimit() = runBlocking {
        // The screen shows at most 1000 rows; "delete all" must not leave the older, invisible ones behind.
        for (i in 1..1005) dao.insert(AppLogEntity(timestamp = i.toLong(), level = "INFO", message = "m$i"))
        val maxVisible = dao.getAllNewestFirst().first().maxOf { it.id }

        dao.deleteUpTo(maxVisible)

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
