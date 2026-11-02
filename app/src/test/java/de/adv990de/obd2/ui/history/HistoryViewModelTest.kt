package de.adv990de.obd2.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.MinMax
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.SessionStats
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.ui.components.UNDO_WINDOW_MS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
    fun `export sets the share uri on success`() = runTest {
        val uri = mockk<Uri>()
        val f = fixture()
        coEvery { f.repo.exportToCsv(42L) } returns uri

        f.vm.export(42L)
        advanceUntilIdle()

        assertEquals(uri, f.vm.exportFeedback.shareUri.value)
        assertNull(f.vm.exportFeedback.error.value)
    }

    @Test
    fun `export sets the export error on failure`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } throws Exception("write failed")

        f.vm.export(1L)
        advanceUntilIdle()

        assertEquals("write failed", f.vm.exportFeedback.error.value)
        assertNull(f.vm.exportFeedback.shareUri.value)
    }

    @Test
    fun `consumeShareUri clears the share uri`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } returns mockk<Uri>()
        f.vm.export(1L)
        advanceUntilIdle()
        assertNotNull(f.vm.exportFeedback.shareUri.value)

        f.vm.exportFeedback.consumeShareUri()

        assertNull(f.vm.exportFeedback.shareUri.value)
    }

    @Test
    fun `consumeError clears the export error`() = runTest {
        val f = fixture()
        coEvery { f.repo.exportToCsv(any()) } throws Exception("some error")
        f.vm.export(1L)
        advanceUntilIdle()
        assertNotNull(f.vm.exportFeedback.error.value)

        f.vm.exportFeedback.consumeError()

        assertNull(f.vm.exportFeedback.error.value)
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

    // ── export of all trips into one CSV: share or save ───────────────────────

    @Test
    fun `the export dialog opens only when there is a finished trip`() = runTest {
        val empty = fixture(ids = listOf(5), active = 5)   // nur die laufende Aufzeichnung
        val job1 = collect(empty.vm); runCurrent()
        empty.vm.requestExportAll()
        assertFalse(empty.vm.canExportAll.value)
        assertFalse(empty.vm.exportAllDialogVisible.value)
        job1.cancel()

        val f = fixture(ids = listOf(1, 2), active = 2)
        val job2 = collect(f.vm); runCurrent()
        f.vm.requestExportAll()
        assertTrue(f.vm.canExportAll.value)
        assertTrue(f.vm.exportAllDialogVisible.value)
        f.vm.cancelExportAll()
        assertFalse(f.vm.exportAllDialogVisible.value)
        job2.cancel()
    }

    @Test
    fun `finishedTripCount is what the dialog announces - the recording and hidden trips are not counted`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3, 4), active = 4)
        val job = launch { f.vm.finishedTripCount.collect {} }
        runCurrent()
        assertEquals(3, f.vm.finishedTripCount.value)

        f.vm.deleteSession(session(1))
        runCurrent()

        assertEquals("die ausgeblendete Fahrt zaehlt nicht mehr", 2, f.vm.finishedTripCount.value)
        job.cancel()
    }

    @Test
    fun `exportAll shares one CSV of the finished trips, oldest first, and closes the dialog`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3), active = 3)
        val uri = mockk<Uri>()
        coEvery { f.repo.exportSessionsToCsv(any()) } returns uri
        val job = collect(f.vm); runCurrent()
        f.vm.requestExportAll()

        f.vm.exportAll()
        runCurrent()

        coVerify(exactly = 1) { f.repo.exportSessionsToCsv(listOf(1L, 2L)) }
        assertEquals(uri, f.vm.exportFeedback.shareUri.value)
        assertFalse(f.vm.exportAllDialogVisible.value)
        assertNull(f.vm.exportFeedback.error.value)
        job.cancel()
    }

    @Test
    fun `the trips are exported oldest first whatever order the ids arrive in`() = runTest {
        val f = fixture(ids = listOf(3, 1, 2))
        coEvery { f.repo.exportSessionsToCsv(any()) } returns mockk<Uri>()
        val job = collect(f.vm); runCurrent()

        f.vm.exportAll()
        runCurrent()

        coVerify(exactly = 1) { f.repo.exportSessionsToCsv(listOf(1L, 2L, 3L)) }
        job.cancel()
    }

    @Test
    fun `a trip whose delete can still be taken back is not exported`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3))
        coEvery { f.repo.exportSessionsToCsv(any()) } returns mockk<Uri>()
        val job = collect(f.vm); runCurrent()
        f.vm.deleteSession(session(2))
        runCurrent()

        f.vm.exportAll()
        runCurrent()

        coVerify(exactly = 1) { f.repo.exportSessionsToCsv(listOf(1L, 3L)) }
        job.cancel()
    }

    @Test
    fun `exportAll with nothing to export says so and exports nothing`() = runTest {
        val f = fixture(ids = listOf(5), active = 5)
        val job = collect(f.vm); runCurrent()

        f.vm.exportAll()
        runCurrent()

        assertTrue(f.vm.exportFeedback.nothingToExport.value)
        coVerify(exactly = 0) { f.repo.exportSessionsToCsv(any()) }
        assertNull(f.vm.exportFeedback.shareUri.value)
        f.vm.exportFeedback.consumeNothingToExport()
        assertFalse(f.vm.exportFeedback.nothingToExport.value)
        job.cancel()
    }

    @Test
    fun `a failing exportAll sets the error, not the share`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        coEvery { f.repo.exportSessionsToCsv(any()) } throws Exception("write failed")
        val job = collect(f.vm); runCurrent()

        f.vm.exportAll()
        runCurrent()

        assertEquals("write failed", f.vm.exportFeedback.error.value)
        assertNull(f.vm.exportFeedback.shareUri.value)
        job.cancel()
    }

    @Test
    fun `saveAllTo writes the finished trips into the chosen target and reports how many`() = runTest {
        val f = fixture(ids = listOf(1, 2, 3), active = 3)
        val target = mockk<Uri>()
        val job = collect(f.vm); runCurrent()

        f.vm.saveAllTo(target)
        runCurrent()

        coVerify(exactly = 1) { f.repo.saveSessionsCsv(listOf(1L, 2L), target) }
        assertEquals(2, f.vm.savedCount.value)
        assertNull(f.vm.exportFeedback.shareUri.value)
        f.vm.consumeSaved()
        assertNull(f.vm.savedCount.value)
        job.cancel()
    }

    @Test
    fun `a failing save sets the error and reports nothing as saved`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        coEvery { f.repo.saveSessionsCsv(any(), any()) } throws java.io.IOException("no space left")
        val job = collect(f.vm); runCurrent()

        f.vm.saveAllTo(mockk())
        runCurrent()

        assertEquals("no space left", f.vm.exportFeedback.error.value)
        assertNull(f.vm.savedCount.value)
        job.cancel()
    }

    @Test
    fun `saveAllTo with nothing to export says so and writes nothing`() = runTest {
        val f = fixture(ids = listOf(5), active = 5)
        val job = collect(f.vm); runCurrent()

        f.vm.saveAllTo(mockk())
        runCurrent()

        assertTrue(f.vm.exportFeedback.nothingToExport.value)
        coVerify(exactly = 0) { f.repo.saveSessionsCsv(any(), any()) }
        job.cancel()
    }

    @Test
    fun `the suggested file name of the save dialog is the shared name of an export of several trips`() {
        val name = fixture().vm.exportAllFileName()

        assertTrue(name, name.startsWith("session_all_") && name.endsWith(".csv"))
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

    // ── the readings of a trip (F11) ──────────────────────────────────────────

    @Test
    fun `loadStats hands over what the repository found`() = runTest {
        val f = fixture()
        val stats = SessionStats(coolant = MinMax(18f, 92f), voltage = MinMax(12.1f, 14.2f))
        coEvery { f.repo.sessionStats(5L) } returns stats

        assertEquals(SessionStatsState.Ready(stats), f.vm.loadStats(5L))
    }

    @Test
    fun `loadStats says Failed when the database cannot be read - never an empty result`() = runTest {
        val f = fixture()
        coEvery { f.repo.sessionStats(any()) } throws IOException("disk gone")

        assertEquals(SessionStatsState.Failed, f.vm.loadStats(5L))
    }

    @Test
    fun `loadStats passes a cancellation on instead of calling it a failure`() = runTest {
        val f = fixture()
        coEvery { f.repo.sessionStats(any()) } throws CancellationException("gone")

        assertThrows(CancellationException::class.java) { runBlocking { f.vm.loadStats(5L) } }
    }

    // ── Ein Abbruch ist kein Fehler: er darf nie als Fehlermeldung beim Nutzer landen ──────────────────

    @Test
    fun `a cancelled export of one trip is not shown as an error and shares nothing`() = runTest {
        val f = fixture(ids = listOf(1))
        coEvery { f.repo.exportToCsv(any()) } throws CancellationException("gone")

        f.vm.export(1); runCurrent()

        assertNull("ein Abbruch ist kein Fehler", f.vm.exportFeedback.error.value)
        assertNull(f.vm.exportFeedback.shareUri.value)
    }

    @Test
    fun `a cancelled exportAll is not shown as an error and shares nothing`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        coEvery { f.repo.exportSessionsToCsv(any()) } throws CancellationException("gone")
        val job = collect(f.vm); runCurrent()

        f.vm.exportAll(); runCurrent()

        assertNull(f.vm.exportFeedback.error.value)
        assertNull(f.vm.exportFeedback.shareUri.value)
        job.cancel()
    }

    @Test
    fun `a cancelled save is not shown as an error and reports nothing as saved`() = runTest {
        val f = fixture(ids = listOf(1, 2))
        coEvery { f.repo.saveSessionsCsv(any(), any()) } throws CancellationException("gone")
        val job = collect(f.vm); runCurrent()

        f.vm.saveAllTo(mockk()); runCurrent()

        assertNull(f.vm.exportFeedback.error.value)
        assertNull(f.vm.savedCount.value)
        job.cancel()
    }
}
