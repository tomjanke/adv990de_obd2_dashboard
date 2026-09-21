package de.adv990de.obd2.data

import android.content.Context
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.SessionDao
import de.adv990de.obd2.data.db.SensorReadingDao
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {

    private val sessionDao = mockk<SessionDao> {
        // SessionRepository eagerly calls sessionDao().getAll() in its property initializer,
        // so this stub must be present before the repository is constructed.
        every { getAll() } returns emptyFlow()
    }
    private val sensorReadingDao = mockk<SensorReadingDao>()
    private val db = mockk<AppDatabase> {
        every { sessionDao() } returns sessionDao
        every { sensorReadingDao() } returns sensorReadingDao
    }
    private val repo = SessionRepository(mockk<Context>(), db)

    @After
    fun tearDown() = clearAllMocks()

    // labelRes = 0: dieser Test rendert nichts, der Anzeigename wird nie aufgeloest.
    private fun makePid(id: String) = PidDefinition(
        id = id, name = "Test", labelRes = 0, unit = "", minValue = 0f, maxValue = 100f, formula = { 0f }
    )

    // ── startSession ──────────────────────────────────────────────────────────

    @Test
    fun `startSession inserts entity and returns id`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 42L

        val id = repo.startSession()

        assertEquals(42L, id)
        coVerify(exactly = 1) { sessionDao.insert(any()) }
        assertNull("vehicleVin should be null when no VIN is passed", inserted.captured.vehicleVin)
    }

    @Test
    fun `startSession with VIN inserts entity with VIN`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 1L

        repo.startSession(vin = "1HGBH41JXMN109186")

        assertEquals("1HGBH41JXMN109186", inserted.captured.vehicleVin)
    }

    // ── endSession ────────────────────────────────────────────────────────────

    @Test
    fun `endSession sets endTime on existing session`() = runTest {
        val updated = slot<SessionEntity>()
        coEvery { sessionDao.getById(1L) } returns SessionEntity(id = 1L, startTime = 1000L)
        coEvery { sessionDao.update(capture(updated)) } just Runs

        repo.endSession(1L)

        assertNotNull("endTime should be set after endSession", updated.captured.endTime)
    }

    @Test
    fun `endSession is no-op when session not found`() = runTest {
        coEvery { sessionDao.getById(99L) } returns null

        repo.endSession(99L)

        coVerify(exactly = 0) { sessionDao.update(any()) }
    }

    // ── writeReadings ─────────────────────────────────────────────────────────

    @Test
    fun `writeReadings inserts correctly mapped entities`() = runTest {
        val pid1 = makePid("010C")
        val pid2 = makePid("010D")
        val sv1 = SensorValue(pid = pid1, value = 1200f, timestamp = 1_000L)
        val sv2 = SensorValue(pid = pid2, value = 80f,   timestamp = 2_000L)

        val inserted = slot<List<SensorReadingEntity>>()
        coEvery { sensorReadingDao.insertAll(capture(inserted)) } just Runs

        repo.writeReadings(sessionId = 5L, values = listOf(sv1, sv2))

        val entities = inserted.captured
        assertEquals(2, entities.size)

        assertEquals(5L,     entities[0].sessionId)
        assertEquals("010C", entities[0].pidId)
        assertEquals(1200f,  entities[0].value)
        assertEquals(1_000L, entities[0].timestamp)

        assertEquals(5L,     entities[1].sessionId)
        assertEquals("010D", entities[1].pidId)
        assertEquals(80f,    entities[1].value)
        assertEquals(2_000L, entities[1].timestamp)
    }

    // ── deleteSession ─────────────────────────────────────────────────────────

    @Test
    fun `deleteSession deletes readings and session`() = runTest {
        coEvery { sensorReadingDao.deleteBySession(3L) } just Runs
        coEvery { sessionDao.getById(3L) } returns SessionEntity(id = 3L, startTime = 0L)
        coEvery { sessionDao.delete(any()) } just Runs

        repo.deleteSession(3L)

        coVerify(exactly = 1) { sensorReadingDao.deleteBySession(3L) }
        coVerify(exactly = 1) { sessionDao.delete(any()) }
    }

    @Test
    fun `deleteSession skips session delete when session not found`() = runTest {
        coEvery { sensorReadingDao.deleteBySession(99L) } just Runs
        coEvery { sessionDao.getById(99L) } returns null

        repo.deleteSession(99L)

        coVerify(exactly = 1) { sensorReadingDao.deleteBySession(99L) }
        coVerify(exactly = 0) { sessionDao.delete(any()) }
    }

    // ── deleteOldSessions ─────────────────────────────────────────────────────

    @Test
    fun `deleteOldSessions delegates to dao with cutoff calculated from maxAgeMs`() = runTest {
        val captor = slot<Long>()
        coEvery { sessionDao.deleteOlderThan(capture(captor)) } just Runs

        val maxAgeMs = 1_000L
        val before = System.currentTimeMillis()
        repo.deleteOldSessions(maxAgeMs)
        val after = System.currentTimeMillis()

        val cutoff = captor.captured
        assertTrue("cutoff should be within [before-maxAgeMs, after-maxAgeMs]",
            cutoff in (before - maxAgeMs)..(after - maxAgeMs))
    }
}
