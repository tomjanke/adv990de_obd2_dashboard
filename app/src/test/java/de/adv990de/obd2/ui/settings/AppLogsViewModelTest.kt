package de.adv990de.obd2.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.AppLogExporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.db.entity.AppLogEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
import java.io.IOException

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
        val exporter: AppLogExporter,
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
        val exporter = mockk<AppLogExporter>()
        // The app scope shares the test scheduler, so virtual time drives the undo window.
        return Fixture(logsFlow, repo, exporter, AppLogsViewModel(repo, exporter, CoroutineScope(testDispatcher + Job())))
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

    // ── export (share or save) ────────────────────────────────────────────────

    @Test
    fun `requestExport opens the dialog only when there are entries`() = runTest {
        val empty = fixture()
        val emptyJob = collect(empty.vm); runCurrent()
        empty.vm.requestExport()
        assertFalse(empty.vm.exportDialogVisible.value)
        emptyJob.cancel()

        val filled = fixture(entry(1))
        val filledJob = collect(filled.vm); runCurrent()
        filled.vm.requestExport()
        assertTrue(filled.vm.exportDialogVisible.value)
        filledJob.cancel()
    }

    @Test
    fun `cancelExport closes the dialog and exports nothing`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.requestExport()
        f.vm.cancelExport()
        runCurrent()

        assertFalse(f.vm.exportDialogVisible.value)
        coVerify(exactly = 0) { f.exporter.exportToCsv(any()) }
        coVerify(exactly = 0) { f.exporter.saveCsv(any(), any()) }
        job.cancel()
    }

    @Test
    fun `shareExport exports the whole log, closes the dialog and hands the file to the share chooser`() = runTest {
        val f = fixture(entry(2), entry(1))
        val uri = mockk<Uri>()
        coEvery { f.exporter.exportToCsv(any()) } returns uri
        val job = collect(f.vm); runCurrent()

        f.vm.requestExport()
        f.vm.shareExport(); runCurrent()

        assertFalse(f.vm.exportDialogVisible.value)
        assertEquals(uri, f.vm.exportFeedback.shareUri.value)
        coVerify(exactly = 1) { f.exporter.exportToCsv(0L) }
        f.vm.exportFeedback.consumeShareUri()
        assertNull(f.vm.exportFeedback.shareUri.value)
        job.cancel()
    }

    @Test
    fun `an export leaves out what a delete-all in its undo window already hides - only the lines after the highest hidden id`() = runTest {
        val f = fixture(entry(2), entry(1))
        coEvery { f.exporter.exportToCsv(any()) } returns mockk()
        coEvery { f.exporter.saveCsv(any(), any()) } just Runs
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()
        f.logsFlow.value = listOf(entry(3), entry(2), entry(1)); runCurrent()   // a new line arrives inside the undo window

        f.vm.shareExport(); runCurrent()
        f.vm.saveExportTo(mockk()); runCurrent()

        coVerify(exactly = 1) { f.exporter.exportToCsv(2L) }                    // not 0: the hidden lines stay out of the file
        coVerify(exactly = 1) { f.exporter.saveCsv(any(), 2L) }
        job.cancel()
    }

    @Test
    fun `an export with nothing visible says so and does not export`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm); runCurrent()

        f.vm.confirmClear(); runCurrent()                                        // everything hidden, undo window open
        f.vm.shareExport(); runCurrent()
        assertTrue(f.vm.exportFeedback.nothingToExport.value)
        f.vm.exportFeedback.consumeNothingToExport()
        assertFalse(f.vm.exportFeedback.nothingToExport.value)
        f.vm.saveExportTo(mockk()); runCurrent()
        assertTrue(f.vm.exportFeedback.nothingToExport.value)

        coVerify(exactly = 0) { f.exporter.exportToCsv(any()) }
        coVerify(exactly = 0) { f.exporter.saveCsv(any(), any()) }
        job.cancel()
    }

    @Test
    fun `a failed share is reported with the message, or the class name when there is none`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm); runCurrent()

        coEvery { f.exporter.exportToCsv(any()) } throws IOException("disk full")
        f.vm.shareExport(); runCurrent()
        assertEquals("disk full", f.vm.exportFeedback.error.value)
        assertNull(f.vm.exportFeedback.shareUri.value)

        f.vm.exportFeedback.consumeError()
        coEvery { f.exporter.exportToCsv(any()) } throws IllegalStateException()
        f.vm.shareExport(); runCurrent()
        assertEquals("IllegalStateException", f.vm.exportFeedback.error.value)
        job.cancel()
    }

    @Test
    fun `saveExportTo writes into the chosen file and reports it`() = runTest {
        val f = fixture(entry(2), entry(1))
        val target = mockk<Uri>()
        coEvery { f.exporter.saveCsv(any(), any()) } just Runs
        val job = collect(f.vm); runCurrent()

        f.vm.saveExportTo(target); runCurrent()

        coVerify(exactly = 1) { f.exporter.saveCsv(target, 0L) }
        assertTrue(f.vm.saved.value)
        assertNull(f.vm.exportFeedback.error.value)
        f.vm.consumeSaved()
        assertFalse(f.vm.saved.value)
        job.cancel()
    }

    @Test
    fun `a failed save reports the error and does not claim success`() = runTest {
        val f = fixture(entry(1))
        coEvery { f.exporter.saveCsv(any(), any()) } throws IOException("The chosen file could not be opened for writing")
        val job = collect(f.vm); runCurrent()

        f.vm.saveExportTo(mockk()); runCurrent()

        assertEquals("The chosen file could not be opened for writing", f.vm.exportFeedback.error.value)
        assertFalse(f.vm.saved.value)
        job.cancel()
    }

    @Test
    fun `a new export attempt replaces the error of the one before`() = runTest {
        val f = fixture(entry(1))
        val job = collect(f.vm); runCurrent()
        coEvery { f.exporter.exportToCsv(any()) } throws IOException("disk full") andThen mockk<Uri>()
        coEvery { f.exporter.saveCsv(any(), any()) } throws IOException("no space") andThen Unit

        f.vm.shareExport(); runCurrent()
        assertEquals("disk full", f.vm.exportFeedback.error.value)
        f.vm.shareExport(); runCurrent()                       // the error was not consumed, the retry works
        assertNull(f.vm.exportFeedback.error.value)

        f.vm.saveExportTo(mockk()); runCurrent()
        assertEquals("no space", f.vm.exportFeedback.error.value)
        f.vm.saveExportTo(mockk()); runCurrent()
        assertNull(f.vm.exportFeedback.error.value)
        assertTrue(f.vm.saved.value)
        job.cancel()
    }

    @Test
    fun `a cancelled export is not an error`() = runTest {
        val f = fixture(entry(1))
        coEvery { f.exporter.exportToCsv(any()) } throws CancellationException("scope gone")
        coEvery { f.exporter.saveCsv(any(), any()) } throws CancellationException("scope gone")
        val job = collect(f.vm); runCurrent()

        f.vm.shareExport(); runCurrent()
        f.vm.saveExportTo(mockk()); runCurrent()

        assertNull(f.vm.exportFeedback.error.value)
        assertFalse(f.vm.saved.value)
        job.cancel()
    }

    @Test
    fun `the file name proposed to the system dialog is the log name with the export time`() {
        val f = fixture()
        val name = f.vm.exportFileName()
        assertTrue(name, Regex("""applog_\d{8}T\d{9}Z\.csv""").matches(name))
    }
}
