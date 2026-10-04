package de.adv990de.obd2.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.ui.components.UNDO_WINDOW_MS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import kotlinx.coroutines.test.advanceUntilIdle
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
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private class Fixture(
        val ids: MutableStateFlow<List<Long>>,
        val active: MutableStateFlow<Long?>,
        val repo: SessionRepository,
        val vm: HistoryViewModel,
    )

    /**
     * pagingSource() is only called by Paging3 when `vm.sessions` is first collected; none of these
     * tests collect it (the filtering of the paged list is covered end to end in HistoryScreenTest),
     * so a relaxed repository is enough for it. The id list and the active trip are real flows.
     */
    private fun fixture(ids: List<Long> = emptyList(), active: Long? = null): Fixture {
        val idsFlow = MutableStateFlow(ids)
        val activeFlow = MutableStateFlow(active)
        val repo = mockk<SessionRepository>(relaxed = true) {
            every { sessionIds } returns idsFlow
            every { activeSessionId } returns activeFlow
            // Like the database: a delete drops the rows and the id flow re-emits.
            coEvery { deleteSessions(any()) } answers {
                val gone = firstArg<Collection<Long>>().toSet()
                idsFlow.value = idsFlow.value.filter { it !in gone }
            }
        }
        // The app scope shares the test scheduler, so virtual time drives the undo window.
        return Fixture(idsFlow, activeFlow, repo, HistoryViewModel(repo, CoroutineScope(testDispatcher + Job())))
    }

    /** `canDeleteAll` is `WhileSubscribed`, so something has to collect it for the upstream to run. */
    private fun TestScope.collect(vm: HistoryViewModel): Job = launch { vm.canDeleteAll.collect {} }

    private fun HistoryViewModel.leaveScreen() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    private fun session(id: Long) = SessionEntity(id = id, startTime = id * 1_000L)

    // NOTE: tests inside the undo window use runCurrent(), never advanceUntilIdle(): the latter jumps virtual
    // time over the 10 s window too, so the delete would already be committed.

    // ── export (unchanged behaviour) ──────────────────────────────────────────

    @Test
    fun `export sets shareUri on success`() = runTest {
        val uri = mockk<Uri>()
        val f = fixture()
        coEvery { f.repo.exportToCsv(42L) } returns uri

        f.vm.export(42L)
        advanceUntilIdle()

        assertEquals(uri, f.vm.shareUri.value)
        assertNull(f.vm.exportError.value)
    }

    @Test
    fun `export sets exportError on failure`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } throws Exception("write failed")

        f.vm.export(1L)
        advanceUntilIdle()

        assertEquals("write failed", f.vm.exportError.value)
        assertNull(f.vm.shareUri.value)
    }

    @Test
    fun `consumeShareUri clears shareUri`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } returns mockk<Uri>()
        f.vm.export(1L)
        advanceUntilIdle()
        assertNotNull(f.vm.shareUri.value)

        f.vm.consumeShareUri()

        assertNull(f.vm.shareUri.value)
    }

    @Test
    fun `consumeExportError clears exportError`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } throws Exception("some error")
        f.vm.export(1L)
        advanceUntilIdle()
        assertNotNull(f.vm.exportError.value)

        f.vm.consumeExportError()

        assertNull(f.vm.exportError.value)
    }

    @Test
    fun `opening the history does not purge anything - retention runs once at app start`() = runTest {
        val f = fixture()
        runCurrent()
        coVerify(exactly = 0) { f.repo.deleteOldSessions(any()) }
    }

    // ── one trip: trash button or swipe, no dialog, undo only ─────────────────

    @Test
    fun `deleteSession offers undo at once but deletes nothing yet`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteSession(session(1))
        runCurrent()

        assertNotNull(f.vm.undoBatchId.value)
        assertEquals(1, f.vm.undoCount.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }
        job.cancel()
    }

    @Test
    fun `deleteSession deletes the trip after the undo window`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteSession(session(1))
        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.repo.deleteSessions(setOf(1L)) }
        assertNull(f.vm.undoBatchId.value)
        job.cancel()
    }

    @Test
    fun `undoDelete inside the window brings the trip back and never deletes`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()
        f.vm.deleteSession(session(1))
        runCurrent()

        f.vm.undoDelete()
        advanceTimeBy(60_000); runCurrent()

        assertNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }
        job.cancel()
    }

    @Test
    fun `the trip that is being recorded cannot be deleted`() = runTest {
        val f = fixture(ids = listOf(1, 9), active = 9L)
        val job = collect(f.vm); runCurrent()

        f.vm.deleteSession(session(9))
        advanceTimeBy(60_000); runCurrent()

        assertNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }
        job.cancel()
    }

    @Test
    fun `a second delete commits the first one at once and only the second stays undoable`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteSession(session(1))
        val first = f.vm.undoBatchId.value
        f.vm.deleteSession(session(2))
        runCurrent()

        coVerify(exactly = 1) { f.repo.deleteSessions(setOf(1L)) }
        coVerify(exactly = 0) { f.repo.deleteSessions(setOf(2L)) }
        assertNotNull(f.vm.undoBatchId.value)
        assertTrue(f.vm.undoBatchId.value != first)
        job.cancel()
    }

    // ── delete all ────────────────────────────────────────────────────────────

    @Test
    fun `canDeleteAll is false without trips and when only the running trip exists`() = runTest {
        val empty = fixture()
        val emptyJob = collect(empty.vm); runCurrent()
        assertFalse(empty.vm.canDeleteAll.value)
        emptyJob.cancel()

        val recordingOnly = fixture(ids = listOf(9), active = 9L)
        val recordingJob = collect(recordingOnly.vm); runCurrent()
        assertFalse(recordingOnly.vm.canDeleteAll.value)
        recordingJob.cancel()
    }

    @Test
    fun `canDeleteAll is true with a finished trip and false while everything is pending`() = runTest {
        val f = fixture(ids = listOf(1, 2), active = null)
        val job = collect(f.vm); runCurrent()
        assertTrue(f.vm.canDeleteAll.value)

        f.vm.deleteSession(session(1))
        runCurrent()
        assertTrue("trip 2 is still there", f.vm.canDeleteAll.value)

        f.vm.deleteSession(session(2))
        runCurrent()
        assertFalse("everything is hidden during the undo window", f.vm.canDeleteAll.value)
        job.cancel()
    }

    @Test
    fun `requestDeleteAll opens the dialog only when there is something to delete`() = runTest {
        val empty = fixture()
        val emptyJob = collect(empty.vm); runCurrent()
        empty.vm.requestDeleteAll()
        assertFalse(empty.vm.deleteAllConfirmationVisible.value)
        emptyJob.cancel()

        val filled = fixture(ids = listOf(1))
        val filledJob = collect(filled.vm); runCurrent()
        filled.vm.requestDeleteAll()
        assertTrue(filled.vm.deleteAllConfirmationVisible.value)
        filledJob.cancel()
    }

    @Test
    fun `cancelDeleteAll closes the dialog and deletes nothing`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()
        f.vm.requestDeleteAll()

        f.vm.cancelDeleteAll()
        advanceTimeBy(60_000); runCurrent()

        assertFalse(f.vm.deleteAllConfirmationVisible.value)
        assertNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }
        job.cancel()
    }

    @Test
    fun `confirmDeleteAll hides all finished trips and deletes exactly them after the window`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3, 9), active = 9L)
        val job = collect(f.vm); runCurrent()
        f.vm.requestDeleteAll()

        f.vm.confirmDeleteAll()
        runCurrent()

        assertFalse(f.vm.deleteAllConfirmationVisible.value)
        assertNotNull(f.vm.undoBatchId.value)
        assertEquals(3, f.vm.undoCount.value)
        assertFalse("nothing left to delete while the batch is undoable", f.vm.canDeleteAll.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }

        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.repo.deleteSessions(setOf(1L, 2L, 3L)) }
        job.cancel()
    }

    @Test
    fun `a trip that ends inside the undo window is not swept up by delete all`() = runTest {
        val f = fixture(ids = listOf(1, 9), active = 9L)
        val job = collect(f.vm); runCurrent()
        f.vm.requestDeleteAll()
        f.vm.confirmDeleteAll()
        runCurrent()

        // The recording stops while the snackbar is still up: trip 9 is now a finished trip.
        f.active.value = null
        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.repo.deleteSessions(setOf(1L)) }
        coVerify(exactly = 0) { f.repo.deleteSessions(match { 9L in it }) }
        job.cancel()
    }

    @Test
    fun `undoDelete after delete all restores everything`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()
        f.vm.confirmDeleteAll()
        runCurrent()

        f.vm.undoDelete()
        advanceTimeBy(60_000); runCurrent()

        assertTrue(f.vm.canDeleteAll.value)
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }
        job.cancel()
    }

    @Test
    fun `undoCount follows the latest batch`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteSession(session(1))
        assertEquals(1, f.vm.undoCount.value)
        f.vm.confirmDeleteAll()
        assertEquals(2, f.vm.undoCount.value)
        job.cancel()
    }

    // ── leaving the screen ────────────────────────────────────────────────────

    @Test
    fun `leaving the screen commits an open delete at once`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        val job = collect(f.vm); runCurrent()
        f.vm.deleteSession(session(2))
        runCurrent()
        coVerify(exactly = 0) { f.repo.deleteSessions(any()) }

        f.vm.leaveScreen()
        runCurrent()

        coVerify(exactly = 1) { f.repo.deleteSessions(setOf(2L)) }
        job.cancel()
    }
}
