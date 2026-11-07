package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.db.entity.AppLogEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLogsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(id: Long) = AppLogEntity(id = id, timestamp = id * 1000L, level = "INFO", message = "m$id")

    private class Fixture(
        val logsFlow: MutableStateFlow<List<AppLogEntity>>,
        val repo: AppLogRepository,
        val vm: AppLogsViewModel,
    )

    private fun fixture(vararg initial: AppLogEntity): Fixture {
        val logsFlow = MutableStateFlow(initial.toList())
        val repo = mockk<AppLogRepository> {
            every { logs } returns logsFlow
            coEvery { pruneOlderThan(any()) } just Runs
            // Like the database: a delete drops every row up to the id and the Flow re-emits.
            coEvery { deleteUpTo(any()) } answers {
                val maxId = firstArg<Long>()
                logsFlow.value = logsFlow.value.filter { it.id > maxId }
            }
        }
        // The app scope shares the test scheduler, so virtual time drives the undo window.
        return Fixture(logsFlow, repo, AppLogsViewModel(repo, CoroutineScope(testDispatcher + Job())))
    }

    // NOTE: tests inside the undo window use runCurrent(), never advanceUntilIdle(): the latter jumps virtual
    // time over the 10 s window too, so the delete would already be committed.

    /** `logs` is `WhileSubscribed`, so something has to collect it for the upstream to run. */
    private fun TestScope.collect(vm: AppLogsViewModel): Job = launch { vm.logs.collect {} }

    private fun AppLogsViewModel.leaveScreen() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    // ── existing behaviour ────────────────────────────────────────────────────

    @Test
    fun `logs emits list from repository`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm)
        runCurrent()

        assertEquals(listOf(entry(1)), f.vm.logs.value)
        job.cancel()
    }

    @Test
    fun `opening the log screen does not prune anything - retention runs once at app start`() = runTest {
        val f = fixture()
        runCurrent()
        coVerify(exactly = 0) { f.repo.pruneOlderThan(any()) }
    }

    // ── confirmation dialog ───────────────────────────────────────────────────

    @Test
    fun `requestClear opens the confirmation only when there are entries`() = runTest {
        val empty = fixture()
        val emptyJob = collect(empty.vm); runCurrent()
        empty.vm.requestClear()
        assertFalse(empty.vm.clearConfirmationVisible.value)
        emptyJob.cancel()

        val filled = fixture(entry(1))
        val filledJob = collect(filled.vm); runCurrent()
        filled.vm.requestClear()
        assertTrue(filled.vm.clearConfirmationVisible.value)
        filledJob.cancel()
    }

    @Test
    fun `cancelClear closes the confirmation and deletes nothing`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.requestClear()
        f.vm.cancelClear()
        advanceTimeBy(60_000); runCurrent()

        assertFalse(f.vm.clearConfirmationVisible.value)
        assertEquals(listOf(entry(1)), f.vm.logs.value)
        coVerify(exactly = 0) { f.repo.deleteUpTo(any()) }
        job.cancel()
    }

    // ── delete with undo ──────────────────────────────────────────────────────

    @Test
    fun `confirmClear hides every entry at once but deletes nothing yet`() = runTest {
        val f = fixture(entry(3), entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.requestClear()
        f.vm.confirmClear()
        runCurrent()

        assertTrue(f.vm.logs.value.isEmpty())
        assertFalse(f.vm.clearConfirmationVisible.value)
        assertNotNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.deleteUpTo(any()) }
        job.cancel()
    }

    @Test
    fun `the entries are really deleted once the undo window has passed`() = runTest {
        val f = fixture(entry(3), entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear()
        advanceTimeBy(10_000); runCurrent()

        coVerify(exactly = 1) { f.repo.deleteUpTo(3L) }      // the highest id at press time
        assertNull(f.vm.undoBatchId.value)
        job.cancel()
    }

    @Test
    fun `undoClear brings the entries back and never deletes`() = runTest {
        val f = fixture(entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()
        f.vm.undoClear(); runCurrent()

        assertEquals(listOf(entry(2), entry(1)), f.vm.logs.value)
        assertNull(f.vm.undoBatchId.value)
        advanceTimeBy(60_000); runCurrent()
        coVerify(exactly = 0) { f.repo.deleteUpTo(any()) }
        job.cancel()
    }

    @Test
    fun `lines written inside the undo window stay visible and survive the deletion`() = runTest {
        val f = fixture(entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()
        f.logsFlow.value = listOf(entry(3), entry(2), entry(1))   // a new line arrives meanwhile
        runCurrent()

        assertEquals(listOf(entry(3)), f.vm.logs.value)
        advanceTimeBy(10_000); runCurrent()
        coVerify(exactly = 1) { f.repo.deleteUpTo(2L) }           // not 3: the new line is kept
        job.cancel()
    }

    @Test
    fun `clearing again while the first clear is still undoable commits the first at once`() = runTest {
        val f = fixture(entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()
        f.logsFlow.value = listOf(entry(3), entry(2), entry(1)); runCurrent()
        f.vm.confirmClear(); runCurrent()

        coVerify(exactly = 1) { f.repo.deleteUpTo(2L) }           // the first one is final now
        assertTrue(f.vm.logs.value.isEmpty())
        f.vm.undoClear(); runCurrent()                      // reverts only the second one
        assertEquals(listOf(entry(3)), f.vm.logs.value)
        job.cancel()
    }

    @Test
    fun `confirmClear with no entries does nothing`() = runTest {
        val f = fixture()
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear()
        advanceTimeBy(60_000); runCurrent()

        assertNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.deleteUpTo(any()) }
        job.cancel()
    }

    @Test
    fun `leaving the screen commits a pending delete at once`() = runTest {
        val f = fixture(entry(2), entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()
        f.vm.leaveScreen(); runCurrent()                          // the undo UI is gone with the screen

        coVerify(exactly = 1) { f.repo.deleteUpTo(2L) }
        advanceTimeBy(60_000); runCurrent()
        coVerify(exactly = 1) { f.repo.deleteUpTo(any()) }         // and not a second time
        job.cancel()
    }
}
