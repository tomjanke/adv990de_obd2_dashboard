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
import kotlinx.coroutines.CancellationException
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
    fun `log swallows a database failure instead of throwing into the caller`() = runTest {
        // log() steht in Verbindungs-, Wetter- und Pipeline-Pfaden, die im viewModelScope laufen: eine
        // Room-Ausnahme dort haette den ganzen Prozess beendet — fuer eine Protokollzeile.
        coEvery { dao.insert(any()) } throws IllegalStateException("database is full")

        repo.log(LogLevel.WARN, "anything")

        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun `log still propagates the cancellation of its caller`() = runTest {
        coEvery { dao.insert(any()) } throws CancellationException("cancelled")

        var cancelled = false
        try { repo.log(LogLevel.INFO, "x") } catch (_: CancellationException) { cancelled = true }

        assertTrue("a cancelled coroutine must stay cancelled", cancelled)
    }

    @Test
    fun `trimToNewest delegates the row cap to the dao`() = runTest {
        coEvery { dao.deleteBeyondNewest(any()) } just Runs

        repo.trimToNewest(250)

        coVerify(exactly = 1) { dao.deleteBeyondNewest(250) }
    }

    @Test
    fun `trimToNewest keeps 1000 entries by default - the number the screen shows`() = runTest {
        coEvery { dao.deleteBeyondNewest(any()) } just Runs

        repo.trimToNewest()

        coVerify(exactly = 1) { dao.deleteBeyondNewest(1000) }
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
    fun `deleteUpTo delegates to dao with the given id`() = runTest {
        coEvery { dao.deleteUpTo(any()) } just Runs

        repo.deleteUpTo(42L)

        coVerify(exactly = 1) { dao.deleteUpTo(42L) }
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
