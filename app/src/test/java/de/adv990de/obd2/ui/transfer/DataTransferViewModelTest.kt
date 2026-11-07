package de.adv990de.obd2.ui.transfer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ExportCounts
import de.adv990de.obd2.data.ExportRequest
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.TransferExporter
import de.adv990de.obd2.data.TransferImporter
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.NotAPackageException
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.TransferResult
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.ui.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.LocalDate

/** Der Zustand der Seite „Daten sichern und wiederherstellen“: Auswahl, Format, Export und die zwei Schritte des Imports. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataTransferViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private val source = mockk<Uri>()
    private val target = mockk<Uri>()
    private val shareUri = mockk<Uri>()
    private val fullCounts = ExportCounts(trips = 3, logbook = 2, appLog = 5)

    private val entry = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, 2), odometerKm = 40_362, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = null, brand = null,
    )
    private val tripsNoNews = PartPlan.Trips(TripImportPlan(emptyList(), duplicateTrips = 2, expiredTrips = 0, rejected = emptyList(), rejectedCount = 0))
    private val logbookNews = PartPlan.Logbook(ImportPlan(listOf(entry), duplicates = 0, rejected = emptyList(), warnings = 0))
    private val preview = TransferPreview(
        kind = FileKind.PACKAGE, newerFormat = false, ignoredAppLog = false,
        parts = linkedMapOf(DataPart.LOGBOOK to logbookNews, DataPart.TRIPS to tripsNoNews),
    )

    private class Fixture(val exporter: TransferExporter, val importer: TransferImporter, val vm: DataTransferViewModel)

    private fun fixture(args: Map<String, Any?> = emptyMap(), counts: ExportCounts = fullCounts): Fixture {
        val exporter = mockk<TransferExporter>()
        coEvery { exporter.counts() } returns counts
        coEvery { exporter.share(any()) } returns shareUri
        coEvery { exporter.save(any(), any()) } returns Unit
        every { exporter.suggestedFileName(any()) } returns "name.zip"
        val importer = mockk<TransferImporter>()
        coEvery { importer.inspect(any(), any(), any()) } returns preview
        coEvery { importer.apply(any(), any()) } returns TransferResult(mapOf(DataPart.LOGBOOK to PartOutcome.Done(1)))
        val vm = DataTransferViewModel(exporter, importer, SavedStateHandle(args))
        return Fixture(exporter, importer, vm)
    }

    private fun TestScope.ready(args: Map<String, Any?> = emptyMap(), counts: ExportCounts = fullCounts): Fixture =
        fixture(args, counts).also { runCurrent() }

    // ── Startzustand ──────────────────────────────────────────────────────────

    @Test
    fun `without arguments the page starts exporting the default parts as a package`() = runTest {
        val f = ready()
        assertEquals(TransferMode.EXPORT, f.vm.mode.value)
        assertEquals(DataPart.DEFAULT_EXPORT, f.vm.selection.value)
        assertEquals(ExportFormat.PACKAGE, f.vm.wantedFormat.value)
        assertEquals(ExportFormat.PACKAGE, f.vm.format.value)
        assertEquals(fullCounts, f.vm.counts.value)
    }

    @Test
    fun `an abbreviation with a part selects only that part and asks for csv`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "LOGBOOK"))
        assertEquals(setOf(DataPart.LOGBOOK), f.vm.selection.value)
        assertEquals(ExportFormat.CSV, f.vm.wantedFormat.value)
        assertEquals(ExportFormat.CSV, f.vm.format.value)
        assertTrue(f.vm.csvAllowed.value)
    }

    @Test
    fun `mode import starts importing`() = runTest {
        val f = ready(mapOf(TRANSFER_MODE_ARG to "import"))
        assertEquals(TransferMode.IMPORT, f.vm.mode.value)
    }

    @Test
    fun `an unknown part counts as none and an unknown mode exports`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "NOPE", TRANSFER_MODE_ARG to "sideways"))
        assertEquals(DataPart.DEFAULT_EXPORT, f.vm.selection.value)
        assertEquals(ExportFormat.PACKAGE, f.vm.wantedFormat.value)
        assertEquals(TransferMode.EXPORT, f.vm.mode.value)
    }

    @Test
    fun `an empty part argument counts as none`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to ""))
        assertEquals(DataPart.DEFAULT_EXPORT, f.vm.selection.value)
    }

    @Test
    fun `setMode switches between the two modes`() = runTest {
        val f = ready()
        f.vm.setMode(TransferMode.IMPORT)
        assertEquals(TransferMode.IMPORT, f.vm.mode.value)
        f.vm.setMode(TransferMode.EXPORT)
        assertEquals(TransferMode.EXPORT, f.vm.mode.value)
    }

    // ── Auswahl ───────────────────────────────────────────────────────────────

    @Test
    fun `toggle adds and removes a part and the app log is selectable but not preselected`() = runTest {
        val f = ready()
        assertFalse(DataPart.APP_LOG in f.vm.selection.value)
        f.vm.toggle(DataPart.APP_LOG)
        assertTrue(DataPart.APP_LOG in f.vm.selection.value)
        f.vm.toggle(DataPart.TRIPS)
        assertFalse(DataPart.TRIPS in f.vm.selection.value)
        f.vm.toggle(DataPart.TRIPS)
        assertTrue(DataPart.TRIPS in f.vm.selection.value)
    }

    @Test
    fun `a part this version does not offer cannot be chosen`() = runTest {
        val f = ready()
        f.vm.toggle(DataPart.SETTINGS)
        assertFalse(DataPart.SETTINGS in f.vm.selection.value)
    }

    @Test
    fun `an empty part cannot be chosen`() = runTest {
        val f = ready(counts = ExportCounts(trips = 0, logbook = 2, appLog = 0))
        f.vm.toggle(DataPart.APP_LOG)
        assertFalse(DataPart.APP_LOG in f.vm.selection.value)
        f.vm.toggle(DataPart.TRIPS)
        assertFalse(DataPart.TRIPS in f.vm.selection.value)
    }

    @Test
    fun `parts without a count drop out of the selection when the counts arrive`() = runTest {
        val f = fixture(counts = ExportCounts(trips = 0, logbook = 2, appLog = 0))
        // Vor den Mengen: alles Vorgewaehlte steht noch da.
        assertEquals(DataPart.DEFAULT_EXPORT, f.vm.selection.value)
        runCurrent()
        assertEquals(setOf(DataPart.LOGBOOK), f.vm.selection.value)
    }

    // ── Format ────────────────────────────────────────────────────────────────

    @Test
    fun `csv is only allowed for exactly one kind and the wish survives the fall back to the package`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "LOGBOOK"))
        assertTrue(f.vm.csvAllowed.value)
        assertEquals(ExportFormat.CSV, f.vm.format.value)

        f.vm.toggle(DataPart.TRIPS)
        runCurrent()
        assertFalse(f.vm.csvAllowed.value)
        assertEquals("two kinds fall back to the package", ExportFormat.PACKAGE, f.vm.format.value)
        assertEquals("the wish stays", ExportFormat.CSV, f.vm.wantedFormat.value)

        f.vm.toggle(DataPart.TRIPS)
        runCurrent()
        assertTrue(f.vm.csvAllowed.value)
        assertEquals("one kind again: csv is back", ExportFormat.CSV, f.vm.format.value)
    }

    @Test
    fun `setFormat changes the wish`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "TRIPS"))
        f.vm.setFormat(ExportFormat.PACKAGE)
        runCurrent()
        assertEquals(ExportFormat.PACKAGE, f.vm.format.value)
        f.vm.setFormat(ExportFormat.CSV)
        runCurrent()
        assertEquals(ExportFormat.CSV, f.vm.format.value)
    }

    @Test
    fun `canExport needs a non empty selection`() = runTest {
        val f = ready()
        assertTrue(f.vm.canExport.value)
        f.vm.toggle(DataPart.TRIPS)
        f.vm.toggle(DataPart.LOGBOOK)
        runCurrent()
        assertFalse(f.vm.canExport.value)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @Test
    fun `share exports the chosen parts with the effective format`() = runTest {
        val f = ready()
        f.vm.share()
        runCurrent()
        val request = slot<ExportRequest>()
        coVerify(exactly = 1) { f.exporter.share(capture(request)) }
        assertEquals(DataPart.DEFAULT_EXPORT, request.captured.parts)
        assertEquals(ExportFormat.PACKAGE, request.captured.format)
        assertEquals(shareUri, f.vm.exportFeedback.shareUri.value)
    }

    @Test
    fun `share reads the counts afresh and leaves out a part that became empty`() = runTest {
        val f = ready()
        coEvery { f.exporter.counts() } returns ExportCounts(trips = 0, logbook = 2, appLog = 0)
        f.vm.share()
        runCurrent()
        val request = slot<ExportRequest>()
        coVerify { f.exporter.share(capture(request)) }
        assertEquals(setOf(DataPart.LOGBOOK), request.captured.parts)
        assertEquals("the page learned the new counts", 0, f.vm.counts.value?.trips)
    }

    @Test
    fun `share reports nothing to export when every chosen part is empty`() = runTest {
        val f = ready()
        coEvery { f.exporter.counts() } returns ExportCounts(trips = 0, logbook = 0, appLog = 0)
        f.vm.share()
        runCurrent()
        assertTrue(f.vm.exportFeedback.nothingToExport.value)
        coVerify(exactly = 0) { f.exporter.share(any()) }
    }

    @Test
    fun `csv share of the abbreviated part uses csv`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "TRIPS"))
        f.vm.share()
        runCurrent()
        val request = slot<ExportRequest>()
        coVerify { f.exporter.share(capture(request)) }
        assertEquals(setOf(DataPart.TRIPS), request.captured.parts)
        assertEquals(ExportFormat.CSV, request.captured.format)
    }

    @Test
    fun `saveTo writes the file and reports saved`() = runTest {
        val f = ready()
        f.vm.saveTo(target)
        runCurrent()
        coVerify(exactly = 1) { f.exporter.save(target, any()) }
        assertTrue(f.vm.saved.value)
        f.vm.consumeSaved()
        assertFalse(f.vm.saved.value)
    }

    @Test
    fun `saveTo drops an empty part and reports nothing when none is left`() = runTest {
        val f = ready()
        coEvery { f.exporter.counts() } returns ExportCounts(trips = 0, logbook = 0, appLog = 0)
        f.vm.saveTo(target)
        runCurrent()
        assertTrue(f.vm.exportFeedback.nothingToExport.value)
        assertFalse(f.vm.saved.value)
        coVerify(exactly = 0) { f.exporter.save(any(), any()) }
    }

    @Test
    fun `a failing save lands in the feedback and does not count as saved`() = runTest {
        val f = ready()
        coEvery { f.exporter.save(any(), any()) } throws IOException("disk full")
        f.vm.saveTo(target)
        runCurrent()
        assertEquals("disk full", f.vm.exportFeedback.error.value)
        assertFalse(f.vm.saved.value)
    }

    @Test
    fun `a cancelled save is never an error`() = runTest {
        val f = ready()
        coEvery { f.exporter.save(any(), any()) } throws CancellationException("gone")
        f.vm.saveTo(target)
        runCurrent()
        assertNull(f.vm.exportFeedback.error.value)
        assertFalse(f.vm.saved.value)
    }

    @Test
    fun `suggestedFileName asks the exporter with the current choice`() = runTest {
        val f = ready(mapOf(TRANSFER_PART_ARG to "LOGBOOK"))
        val request = slot<ExportRequest>()
        every { f.exporter.suggestedFileName(capture(request)) } returns "logbook.csv"
        assertEquals("logbook.csv", f.vm.suggestedFileName())
        assertEquals(setOf(DataPart.LOGBOOK), request.captured.parts)
        assertEquals(ExportFormat.CSV, request.captured.format)
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    fun `picking a file shows the preview with every part that has news selected`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        assertEquals(TransferImportState.Working, f.vm.importState.value)
        runCurrent()
        val state = f.vm.importState.value as TransferImportState.Preview
        assertEquals(preview, state.preview)
        assertEquals("only the logbook has news", setOf(DataPart.LOGBOOK), state.selected)
    }

    @Test
    fun `the preview passes the clock and the day to the importer`() = runTest {
        val f = fixture()
        f.vm.nowMs = { 1234L }
        f.vm.today = { LocalDate.of(2026, 11, 7) }
        runCurrent()
        f.vm.pickImport(source)
        runCurrent()
        coVerify { f.importer.inspect(source, 1234L, LocalDate.of(2026, 11, 7)) }
    }

    @Test
    fun `an unreadable part is never preselected and cannot be chosen`() = runTest {
        val f = ready()
        coEvery { f.importer.inspect(any(), any(), any()) } returns preview.copy(
            parts = linkedMapOf(DataPart.LOGBOOK to logbookNews, DataPart.TRIPS to PartPlan.Unreadable(UnreadableReason.FORMAT)),
        )
        f.vm.pickImport(source)
        runCurrent()
        f.vm.toggleImportPart(DataPart.TRIPS)
        assertEquals(setOf(DataPart.LOGBOOK), (f.vm.importState.value as TransferImportState.Preview).selected)
    }

    @Test
    fun `toggleImportPart switches a part with news off and on`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        f.vm.toggleImportPart(DataPart.LOGBOOK)
        assertEquals(emptySet<DataPart>(), (f.vm.importState.value as TransferImportState.Preview).selected)
        f.vm.toggleImportPart(DataPart.LOGBOOK)
        assertEquals(setOf(DataPart.LOGBOOK), (f.vm.importState.value as TransferImportState.Preview).selected)
        f.vm.toggleImportPart(DataPart.APP_LOG)
        assertEquals("a part the preview does not hold is ignored", setOf(DataPart.LOGBOOK), (f.vm.importState.value as TransferImportState.Preview).selected)
    }

    @Test
    fun `a second pick while working is ignored`() = runTest {
        val f = ready()
        val gate = CompletableDeferred<TransferPreview>()
        coEvery { f.importer.inspect(any(), any(), any()) } coAnswers { gate.await() }
        f.vm.pickImport(source)
        runCurrent()
        f.vm.pickImport(mockk())
        runCurrent()
        coVerify(exactly = 1) { f.importer.inspect(any(), any(), any()) }
        gate.complete(preview)
        runCurrent()
        assertTrue(f.vm.importState.value is TransferImportState.Preview)
    }

    @Test
    fun `a new pick after the preview replaces it`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        val second = preview.copy(parts = linkedMapOf(DataPart.TRIPS to tripsNoNews))
        coEvery { f.importer.inspect(any(), any(), any()) } returns second
        f.vm.pickImport(mockk())
        runCurrent()

        assertEquals(second, (f.vm.importState.value as TransferImportState.Preview).preview)
        coVerify(exactly = 2) { f.importer.inspect(any(), any(), any()) }
    }

    @Test
    fun `a new import can start after a failure`() = runTest {
        val f = ready()
        coEvery { f.importer.inspect(any(), any(), any()) } throws java.io.IOException("gone")
        f.vm.pickImport(source)
        runCurrent()
        assertTrue(f.vm.importState.value is TransferImportState.Failed)

        coEvery { f.importer.inspect(any(), any(), any()) } returns preview
        f.vm.pickImport(source)
        runCurrent()

        assertTrue(f.vm.importState.value is TransferImportState.Preview)
    }

    @Test
    fun `confirm writes the chosen parts and shows the result and refreshes the counts`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        coEvery { f.exporter.counts() } returns ExportCounts(trips = 9, logbook = 9, appLog = 9)
        f.vm.confirmImport()
        assertEquals(TransferImportState.Working, f.vm.importState.value)
        runCurrent()
        val done = f.vm.importState.value as TransferImportState.Done
        assertEquals(mapOf(DataPart.LOGBOOK to PartOutcome.Done(1)), done.result.outcomes)
        coVerify { f.importer.apply(preview, setOf(DataPart.LOGBOOK)) }
        assertEquals(9, f.vm.counts.value?.trips)
    }

    @Test
    fun `confirm without a preview does nothing`() = runTest {
        val f = ready()
        f.vm.confirmImport()
        runCurrent()
        assertEquals(TransferImportState.Idle, f.vm.importState.value)
        coVerify(exactly = 0) { f.importer.apply(any(), any()) }
    }

    @Test
    fun `confirm with an empty choice does nothing`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        f.vm.toggleImportPart(DataPart.LOGBOOK)
        f.vm.confirmImport()
        runCurrent()
        assertTrue(f.vm.importState.value is TransferImportState.Preview)
        coVerify(exactly = 0) { f.importer.apply(any(), any()) }
    }

    @Test
    fun `a second confirm while working does not write twice`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        f.vm.confirmImport()
        f.vm.confirmImport()
        runCurrent()
        coVerify(exactly = 1) { f.importer.apply(any(), any()) }
    }

    @Test
    fun `dismiss closes preview and result but not while working`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        f.vm.dismissImport()
        assertEquals(TransferImportState.Idle, f.vm.importState.value)

        val gate = CompletableDeferred<TransferPreview>()
        coEvery { f.importer.inspect(any(), any(), any()) } coAnswers { gate.await() }
        f.vm.pickImport(source)
        runCurrent()
        f.vm.dismissImport()
        assertEquals("working cannot be dismissed", TransferImportState.Working, f.vm.importState.value)
        gate.complete(preview)
        runCurrent()
        f.vm.confirmImport()
        runCurrent()
        assertTrue(f.vm.importState.value is TransferImportState.Done)
        f.vm.dismissImport()
        assertEquals(TransferImportState.Idle, f.vm.importState.value)
    }

    @Test
    fun `a cancelled read goes back to idle`() = runTest {
        val f = ready()
        coEvery { f.importer.inspect(any(), any(), any()) } throws CancellationException("gone")
        f.vm.pickImport(source)
        runCurrent()
        assertEquals(TransferImportState.Idle, f.vm.importState.value)
    }

    @Test
    fun `a cancelled write goes back to idle`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        coEvery { f.importer.apply(any(), any()) } throws CancellationException("gone")
        f.vm.confirmImport()
        runCurrent()
        assertEquals(TransferImportState.Idle, f.vm.importState.value)
    }

    @Test
    fun `a failure outside the parts is shown as failed`() = runTest {
        val f = ready()
        f.vm.pickImport(source)
        runCurrent()
        coEvery { f.importer.apply(any(), any()) } throws IllegalStateException("boom")
        f.vm.confirmImport()
        runCurrent()
        assertEquals(TransferImportState.Failed(UiText.DynamicString("boom")), f.vm.importState.value)
    }

    // ── Fehlertexte ───────────────────────────────────────────────────────────

    private fun failureOf(e: Exception): UiText {
        val f = fixture()
        coEvery { f.importer.inspect(any(), any(), any()) } throws e
        f.vm.pickImport(source)
        testDispatcher.scheduler.runCurrent()
        return (f.vm.importState.value as TransferImportState.Failed).text
    }

    private fun res(id: Int) = UiText.StringResource(id)

    @Test
    fun `every failure has a visible text`() {
        assertEquals(res(R.string.transfer_error_not_a_backup), failureOf(TransferFileException(TransferFileException.Kind.NOT_A_BACKUP, "x")))
        assertEquals(res(R.string.transfer_error_applog_csv), failureOf(TransferFileException(TransferFileException.Kind.APP_LOG_NOT_IMPORTABLE, "x")))
        assertEquals(res(R.string.transfer_error_unrecognized), failureOf(TransferFileException(TransferFileException.Kind.UNRECOGNIZED, "x")))
        assertEquals(res(R.string.transfer_error_not_a_backup), failureOf(NotAPackageException("x")))
        assertEquals(res(R.string.transfer_error_too_large), failureOf(FileTooLargeException("x")))
        assertEquals(res(R.string.transfer_error_unrecognized), failureOf(IllegalArgumentException("x")))
        assertEquals(res(R.string.transfer_error_unreadable), failureOf(IOException("x")))
        assertEquals(res(R.string.transfer_error_unreadable), failureOf(SecurityException("x")))
        assertEquals(UiText.DynamicString("odd"), failureOf(IllegalStateException("odd")))
        assertEquals(UiText.DynamicString("IllegalStateException"), failureOf(IllegalStateException()))
    }

    @Test
    fun `the counts are not live but loaded once at the start`() = runTest {
        val f = ready()
        coVerify(exactly = 1) { f.exporter.counts() }
        assertNotNull(f.vm.counts.value)
    }
}
