package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.DtcHistoryDao
import de.adv990de.obd2.data.db.entity.DtcHistoryEntity
import de.adv990de.obd2.obd.model.DtcEntry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DtcHistoryRepositoryTest {

    private val dao = mockk<DtcHistoryDao> {
        coEvery { recordSeen(any(), any(), any()) } just Runs
        coEvery { pruneToNewest(any()) } just Runs
        coEvery { deleteByCodes(any()) } just Runs
    }
    private val appLog = mockk<AppLogRepository>(relaxed = true)
    private val repo = DtcHistoryRepository(dao, appLog)

    private fun dtc(code: String, pending: Boolean = false) = DtcEntry(code, "text of $code", pending)

    // ── record ────────────────────────────────────────────────────────────────

    @Test
    fun `an empty reading records nothing`() = runTest {
        repo.record(emptyList(), now = 1_000L)

        coVerify(exactly = 0) { dao.recordSeen(any(), any(), any()) }
        coVerify(exactly = 0) { dao.pruneToNewest(any()) }
    }

    @Test
    fun `every distinct code is recorded once with the given time`() = runTest {
        repo.record(listOf(dtc("P0301"), dtc("P0420", pending = true)), now = 7_000L)

        coVerify(exactly = 1) { dao.recordSeen("P0301", 7_000L, false) }
        coVerify(exactly = 1) { dao.recordSeen("P0420", 7_000L, true) }
    }

    @Test
    fun `a code that was read as confirmed and as pending counts as confirmed`() = runTest {
        repo.record(listOf(dtc("P0301", pending = true), dtc("P0301", pending = false)), now = 7_000L)

        coVerify(exactly = 1) { dao.recordSeen("P0301", 7_000L, false) }
        coVerify(exactly = 0) { dao.recordSeen("P0301", any(), true) }
    }

    @Test
    fun `a code that was only ever read as pending stays pending`() = runTest {
        repo.record(listOf(dtc("P0301", pending = true), dtc("P0301", pending = true)), now = 7_000L)

        coVerify(exactly = 1) { dao.recordSeen("P0301", 7_000L, true) }
    }

    @Test
    fun `the table is pruned to the newest 200 after recording`() = runTest {
        repo.record(listOf(dtc("P0301")), now = 1_000L)

        coVerify(exactly = 1) { dao.pruneToNewest(200) }
    }

    @Test
    fun `a database failure is swallowed and logged as a warning`() = runTest {
        coEvery { dao.recordSeen(any(), any(), any()) } throws IllegalStateException("disk full")

        repo.record(listOf(dtc("P0301")), now = 1_000L)

        coVerify { appLog.log(LogLevel.WARN, match { "DTC history" in it && "disk full" in it }) }
    }

    @Test
    fun `a failing log call does not make record throw either`() = runTest {
        coEvery { dao.recordSeen(any(), any(), any()) } throws IllegalStateException("disk full")
        coEvery { appLog.log(any(), any()) } throws IllegalStateException("log table gone too")

        repo.record(listOf(dtc("P0301")), now = 1_000L)
    }

    @Test
    fun `a cancellation is never swallowed`() = runTest {
        coEvery { dao.recordSeen(any(), any(), any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { repo.record(listOf(dtc("P0301")), now = 1_000L) }
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete passes the codes on`() = runTest {
        repo.delete(listOf("P0301", "P0420"))

        coVerify(exactly = 1) { dao.deleteByCodes(listOf("P0301", "P0420")) }
    }

    @Test
    fun `delete ignores duplicates and an empty collection`() = runTest {
        repo.delete(listOf("P0301", "P0301"))
        repo.delete(emptyList())

        coVerify(exactly = 1) { dao.deleteByCodes(listOf("P0301")) }
    }

    @Test
    fun `delete works in chunks of 500 so no statement exceeds the SQLite variable limit`() = runTest {
        val codes = (1..1_200).map { "P%04d".format(it) }

        repo.delete(codes)

        coVerify(exactly = 1) { dao.deleteByCodes(codes.subList(0, 500)) }
        coVerify(exactly = 1) { dao.deleteByCodes(codes.subList(500, 1_000)) }
        coVerify(exactly = 1) { dao.deleteByCodes(codes.subList(1_000, 1_200)) }
    }

    // ── history ───────────────────────────────────────────────────────────────

    @Test
    fun `history maps the stored rows and resolves the description for display`() = runTest {
        every { dao.observeAll() } returns flowOf(
            listOf(
                DtcHistoryEntity(code = "P0301", firstSeen = 1_000L, lastSeen = 3_000L, lastWasPending = false),
                DtcHistoryEntity(code = "P9999", firstSeen = 2_000L, lastSeen = 2_000L, lastWasPending = true),
            )
        )

        val entries = repo.history.first()

        assertEquals(
            listOf(
                DtcHistoryEntry("P0301", "Cylinder 1 Misfire Detected", 1_000L, 3_000L, false),
                DtcHistoryEntry("P9999", "Unknown DTC", 2_000L, 2_000L, true),
            ),
            entries,
        )
    }
}
