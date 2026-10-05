package de.adv990de.obd2.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.just
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRetentionTest {

    private val sessions = mockk<SessionRepository>()
    private val logs = mockk<AppLogRepository>(relaxed = true)

    private fun stubAllOk() {
        coEvery { sessions.deleteOldSessions(any()) } just Runs
        coEvery { logs.pruneOlderThan(any()) } just Runs
        coEvery { logs.trimToNewest(any()) } just Runs
        coEvery { sessions.purgeStaleExports() } returns 0
    }

    @Test
    fun `run purges old trips, old log lines and the log beyond its row cap`() = runTest {
        stubAllOk()

        DataRetention(sessions, logs).run()

        coVerify(exactly = 1) { sessions.deleteOldSessions(any()) }
        coVerify(exactly = 1) { logs.pruneOlderThan(any()) }
        coVerify(exactly = 1) { logs.trimToNewest(any()) }
        coVerify(exactly = 1) { sessions.purgeStaleExports() }
    }

    @Test
    fun `a failing CSV export purge does not stop the other steps and is logged`() = runTest {
        stubAllOk()
        coEvery { sessions.purgeStaleExports() } throws java.io.IOException("read-only")

        DataRetention(sessions, logs).run()

        coVerify(exactly = 1) { sessions.deleteOldSessions(any()) }
        coVerify(exactly = 1) { logs.trimToNewest(any()) }
        coVerify { logs.log(LogLevel.WARN, match { it.contains("old CSV exports") && it.contains("read-only") }) }
    }

    @Test
    fun `a failing trip purge does not stop the log purge`() = runTest {
        stubAllOk()
        coEvery { sessions.deleteOldSessions(any()) } throws IllegalStateException("database is locked")

        DataRetention(sessions, logs).run()

        coVerify(exactly = 1) { logs.pruneOlderThan(any()) }
        coVerify(exactly = 1) { logs.trimToNewest(any()) }
    }

    @Test
    fun `a failing log prune does not stop the row cap or the trip purge`() = runTest {
        stubAllOk()
        coEvery { logs.pruneOlderThan(any()) } throws IllegalStateException("database is locked")

        DataRetention(sessions, logs).run()

        coVerify(exactly = 1) { sessions.deleteOldSessions(any()) }
        coVerify(exactly = 1) { logs.trimToNewest(any()) }
    }

    @Test
    fun `a failing step is written to the app log`() = runTest {
        stubAllOk()
        coEvery { sessions.deleteOldSessions(any()) } throws IllegalStateException("database is locked")

        DataRetention(sessions, logs).run()

        coVerify { logs.log(LogLevel.WARN, match { it.contains("database is locked") }) }
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        stubAllOk()
        coEvery { sessions.deleteOldSessions(any()) } throws CancellationException("cancelled")

        var cancelled = false
        try { DataRetention(sessions, logs).run() } catch (_: CancellationException) { cancelled = true }

        assertTrue(cancelled)
        coVerify(exactly = 0) { logs.pruneOlderThan(any()) }
    }
}
