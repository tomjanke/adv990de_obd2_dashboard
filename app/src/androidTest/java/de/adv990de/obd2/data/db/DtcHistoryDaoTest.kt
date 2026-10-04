package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DtcHistoryDao] against a real in-memory database. SQLite on API 26 (3.18) has no UPSERT, so
 * `recordSeen` is `INSERT OR IGNORE` + a guarded `UPDATE` in one transaction — these tests pin the
 * semantics that makes safe: the FIRST sighting is never overwritten, the LAST one only moves forward.
 */
@RunWith(AndroidJUnit4::class)
class DtcHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DtcHistoryDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dtcHistoryDao()
    }

    @After
    fun teardown() = db.close()

    private fun all() = runBlocking { dao.observeAll().first() }

    @Test
    fun recordSeen_insertsANewCodeWithFirstSeenEqualToLastSeen() = runBlocking {
        dao.recordSeen("P0301", now = 1_000L, pending = false)

        val row = all().single()
        assertEquals("P0301", row.code)
        assertEquals(1_000L, row.firstSeen)
        assertEquals(1_000L, row.lastSeen)
        assertFalse(row.lastWasPending)
    }

    @Test
    fun recordSeen_again_keepsFirstSeen_andMovesLastSeenAndThePendingFlag() = runBlocking {
        dao.recordSeen("P0301", now = 1_000L, pending = false)

        dao.recordSeen("P0301", now = 5_000L, pending = true)

        val row = all().single()
        assertEquals("the first sighting is never overwritten", 1_000L, row.firstSeen)
        assertEquals(5_000L, row.lastSeen)
        assertTrue(row.lastWasPending)
    }

    @Test
    fun recordSeen_withAnOlderTimestamp_changesNothing() = runBlocking {
        dao.recordSeen("P0301", now = 5_000L, pending = false)

        dao.recordSeen("P0301", now = 2_000L, pending = true)

        val row = all().single()
        assertEquals(5_000L, row.lastSeen)
        assertFalse("an older reading must not flip the pending flag", row.lastWasPending)
    }

    @Test
    fun observeAll_listsTheNewestSightingFirst_andBreaksTiesByCode() = runBlocking {
        dao.recordSeen("P0420", now = 1_000L, pending = false)
        dao.recordSeen("P0301", now = 3_000L, pending = false)
        dao.recordSeen("P0171", now = 3_000L, pending = false)

        assertEquals(listOf("P0171", "P0301", "P0420"), all().map { it.code })
    }

    @Test
    fun observeAll_followsChanges() = runBlocking {
        assertEquals(emptyList<String>(), all().map { it.code })

        dao.recordSeen("P0301", now = 1_000L, pending = false)

        assertEquals(listOf("P0301"), all().map { it.code })
    }

    @Test
    fun pruneToNewest_keepsOnlyTheNewestSightings() = runBlocking {
        listOf("P0001", "P0002", "P0003", "P0004").forEachIndexed { index, code ->
            dao.recordSeen(code, now = (index + 1) * 1_000L, pending = false)
        }

        dao.pruneToNewest(limit = 2)

        assertEquals(listOf("P0004", "P0003"), all().map { it.code })
    }

    @Test
    fun pruneToNewest_withFewerRowsThanTheLimitDeletesNothing() = runBlocking {
        dao.recordSeen("P0001", now = 1_000L, pending = false)

        dao.pruneToNewest(limit = 200)

        assertEquals(1, all().size)
    }

    @Test
    fun deleteByCodes_removesOnlyTheGivenCodes() = runBlocking {
        dao.recordSeen("P0301", now = 1_000L, pending = false)
        dao.recordSeen("P0420", now = 2_000L, pending = false)
        dao.recordSeen("P0171", now = 3_000L, pending = false)

        dao.deleteByCodes(listOf("P0301", "P0171", "P9999"))

        assertEquals(listOf("P0420"), all().map { it.code })
    }
}
