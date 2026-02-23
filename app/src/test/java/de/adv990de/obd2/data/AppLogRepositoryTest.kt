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
import org.junit.Test

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
}
