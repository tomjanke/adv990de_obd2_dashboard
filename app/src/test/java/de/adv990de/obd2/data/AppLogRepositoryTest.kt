package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.entity.AppLogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AppLogRepositoryTest {

    private val dao = mockk<AppLogDao>()
    private val repo = AppLogRepository(dao)

    @Test
    fun `log inserts entry with correct level and message`() = runTest {
        val slot = slot<AppLogEntity>()
        coEvery { dao.insert(capture(slot)) } just Runs

        repo.log(LogLevel.ERROR, "test error")

        coVerify { dao.insert(any()) }
        assertEquals(LogLevel.ERROR.name, slot.captured.level)
        assertEquals("test error", slot.captured.message)
    }

    @Test
    fun `logs returns flow from dao`() = runTest {
        val entry = AppLogEntity(id = 1, timestamp = 1000L, level = "INFO", message = "hi")
        every { dao.getAllNewestFirst() } returns flowOf(listOf(entry))

        val result = repo.logs.first()

        assertEquals(1, result.size)
        assertEquals("hi", result[0].message)
    }

    @Test
    fun `clearAll delegates to dao`() = runTest {
        coEvery { dao.deleteAll() } just Runs

        repo.clearAll()

        coVerify { dao.deleteAll() }
    }

    @Test
    fun `pruneOlderThan with no argument uses default 30 day maxAgeMs`() = runTest {
        val slot = slot<Long>()
        coEvery { dao.deleteOlderThan(capture(slot)) } just Runs
        val defaultMaxAgeMs = 30L * 24 * 60 * 60 * 1000

        repo.pruneOlderThan()
        val afterCallMillis = System.currentTimeMillis()

        coVerify { dao.deleteOlderThan(any()) }
        val expectedCutoff = afterCallMillis - defaultMaxAgeMs
        assertTrue(
            "expected cutoff near $expectedCutoff but was ${slot.captured}",
            abs(slot.captured - expectedCutoff) < 500L
        )
    }

    @Test
    fun `pruneOlderThan with custom maxAgeMs computes matching cutoff`() = runTest {
        val slot = slot<Long>()
        coEvery { dao.deleteOlderThan(capture(slot)) } just Runs
        val customMaxAgeMs = 5L * 60 * 1000 // 5 minutes

        repo.pruneOlderThan(customMaxAgeMs)
        val afterCallMillis = System.currentTimeMillis()

        coVerify { dao.deleteOlderThan(any()) }
        val expectedCutoff = afterCallMillis - customMaxAgeMs
        assertTrue(
            "expected cutoff near $expectedCutoff but was ${slot.captured}",
            abs(slot.captured - expectedCutoff) < 500L
        )
    }
}
