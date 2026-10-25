package de.adv990de.obd2.ui.logbook

import android.net.Uri
import androidx.lifecycle.ViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.LogbookOdometer
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.MaintenanceDue
import de.adv990de.obd2.logbook.DueState
import de.adv990de.obd2.data.LogbookExporter
import de.adv990de.obd2.data.LogbookImporter
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    /** Fest und fern vom echten Datum: ein "heute" nahe der Uhr liesse einen Fehler, der `LocalDate.now()` nimmt, an diesem Tag unbemerkt. */
    private val today = LocalDate.of(2031, 3, 14)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(id: Long, type: LogType = LogType.FUEL, km: Int = 1000 + id.toInt()) = LogEntry(
        id = id, type = type, date = LocalDate.of(2026, 10, 1), odometerKm = km,
        amountMl = if (type.hasAmount) 10_000 else null, fullTank = if (type == LogType.FUEL) true else null,
        costCents = null, note = null, brand = if (type.hasBrand) "Heidenau K60" else null,
    )

    private class Fixture(
        val entries: MutableStateFlow<List<LogEntry>>,
        val repo: VehicleLogRepository,
        val exporter: LogbookExporter,
        val importer: LogbookImporter,
        val vm: LogbookViewModel,
        val intervals: MutableStateFlow<Map<LogType, Int>>,
        val counter: MutableStateFlow<GpsOdometer?>,
        val prefs: AppPreferences,
    )

    private fun fixture(vararg initial: LogEntry): Fixture {
        val entries = MutableStateFlow(initial.toList())
        val intervals = MutableStateFlow(emptyMap<LogType, Int>())
        val counter = MutableStateFlow<GpsOdometer?>(null)
        val prefs = fakeAppPreferences()
        every { prefs.maintenanceIntervals } returns intervals
        coEvery { prefs.setMaintenanceInterval(any(), any()) } coAnswers {
            val type = firstArg<LogType>()
            val km = secondArg<Int>()
            intervals.value = if (km == 0) intervals.value - type else intervals.value + (type to km)
        }
        val odometer = mockk<LogbookOdometer> { every { this@mockk.counter } returns counter }
        val repo = mockk<VehicleLogRepository> {
            every { this@mockk.entries } returns entries
            // Wie die Datenbank: ein Loeschen entfernt die Zeilen, der Fluss meldet neu.
            coEvery { delete(any()) } answers {
                val ids = firstArg<Collection<Long>>().toSet()
                entries.value = entries.value.filter { it.id !in ids }
            }
        }
        val exporter = mockk<LogbookExporter>()
        val importer = mockk<LogbookImporter>()
        val vm = LogbookViewModel(repo, exporter, importer, prefs, odometer, CoroutineScope(testDispatcher + Job()))
        vm.today = { today }
        return Fixture(entries, repo, exporter, importer, vm, intervals, counter, prefs)
    }

    /** Die Fluesse sind `WhileSubscribed`: damit der Upstream laeuft, muss jemand sammeln. */
    private fun TestScope.collect(vm: LogbookViewModel) {
        backgroundScope.launch { vm.entries.collect {} }
        backgroundScope.launch { vm.hasEntries.collect {} }
    }

    private fun LogbookViewModel.leaveScreen() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    // NOTE: Tests innerhalb des Rueckgaengig-Fensters nutzen runCurrent(), nie advanceUntilIdle() (das springt ueber die 10 s und wuerde schon loeschen).

    // ── Liste und Filter ──────────────────────────────────────────────────────

    @Test
    fun `the list is what the repository gives, newest first`() = runTest {
        val f = fixture(entry(3), entry(2), entry(1))
        collect(f.vm); runCurrent()

        assertEquals(listOf(3L, 2L, 1L), f.vm.entries.value.map { it.id })
    }

    @Test
    fun `a filter shows only its kinds, and All brings everything back`() = runTest {
        val f = fixture(entry(1, LogType.FUEL), entry(2, LogType.OIL), entry(3, LogType.COOLANT), entry(4, LogType.CHAIN_SMALL), entry(5, LogType.TIRE_CHANGE))
        collect(f.vm); runCurrent()

        f.vm.setFilter(LogFilter.Oil); runCurrent()
        assertEquals(listOf(2L), f.vm.entries.value.map { it.id })
        f.vm.setFilter(LogFilter.Maintenance); runCurrent()
        assertEquals(listOf(4L, 5L), f.vm.entries.value.map { it.id })
        f.vm.setFilter(LogFilter.All); runCurrent()
        assertEquals(5, f.vm.entries.value.size)
    }

    @Test
    fun `an empty filter is not an empty logbook - the screen tells them apart`() = runTest {
        val f = fixture(entry(1, LogType.FUEL))
        collect(f.vm); runCurrent()

        f.vm.setFilter(LogFilter.Coolant); runCurrent()

        assertTrue(f.vm.entries.value.isEmpty())
        assertTrue("es gibt Eintraege, nur nicht in diesem Filter", f.vm.hasEntries.value)
    }

    @Test
    fun `an empty logbook has no entries at all`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        assertFalse(f.vm.hasEntries.value)
        assertEquals(LogFilter.All, f.vm.filter.value)
    }

    // ── Loeschen mit Rueckgaengig (Loesch-Vertrag) ────────────────────────────

    @Test
    fun `deleting hides the entry at once but deletes nothing yet`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()

        f.vm.delete(1); runCurrent()

        assertEquals(listOf(2L), f.vm.entries.value.map { it.id })
        assertTrue("der Rueckgaengig-Vorschlag steht", f.vm.undoBatchId.value != null)
        coVerify(exactly = 0) { f.repo.delete(any()) }
    }

    @Test
    fun `the entry is really deleted once the undo window has passed`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()
        f.vm.delete(1)

        advanceTimeBy(10_001); runCurrent()
        advanceTimeBy(1_000); runCurrent()

        coVerify(exactly = 1) { f.repo.delete(setOf(1L)) }
        assertEquals(listOf(2L), f.vm.entries.value.map { it.id })
        assertNull(f.vm.undoBatchId.value)
    }

    @Test
    fun `undo brings the entry back and never deletes`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()
        f.vm.delete(1); runCurrent()

        f.vm.undoDelete(); runCurrent()
        advanceTimeBy(20_000); runCurrent()

        assertEquals(listOf(1L, 2L), f.vm.entries.value.map { it.id })
        coVerify(exactly = 0) { f.repo.delete(any()) }
    }

    @Test
    fun `deleting a second entry commits the first at once - only the latest is undoable`() = runTest {
        val f = fixture(entry(1), entry(2), entry(3))
        collect(f.vm); runCurrent()
        f.vm.delete(1); runCurrent()

        f.vm.delete(2); runCurrent()

        coVerify(exactly = 1) { f.repo.delete(setOf(1L)) }
        coVerify(exactly = 0) { f.repo.delete(setOf(2L)) }
    }

    @Test
    fun `leaving the screen commits a pending delete at once`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()
        f.vm.delete(1); runCurrent()

        f.vm.leaveScreen(); runCurrent()

        coVerify(exactly = 1) { f.repo.delete(setOf(1L)) }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @Test
    fun `requestExport opens the dialog only when there is something to export - and cancel closes it`() = runTest {
        val empty = fixture()
        collect(empty.vm); runCurrent()
        empty.vm.requestExport()
        assertFalse(empty.vm.exportDialogVisible.value)

        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        f.vm.requestExport()
        assertTrue(f.vm.exportDialogVisible.value)
        f.vm.cancelExport()
        assertFalse(f.vm.exportDialogVisible.value)
    }

    @Test
    fun `an export ignores the filter - the file is the whole logbook, not the chips`() = runTest {
        val f = fixture(entry(1, LogType.FUEL), entry(2, LogType.OIL))
        collect(f.vm); runCurrent()
        f.vm.setFilter(LogFilter.Oil); runCurrent()
        val uri = mockk<Uri>()
        coEvery { f.exporter.exportToCsv(any()) } returns uri

        f.vm.shareExport(); runCurrent()

        coVerify { f.exporter.exportToCsv(emptySet()) }
        assertSame(uri, f.vm.shareUri.value)
    }

    @Test
    fun `an export leaves out what a delete in its undo window already hides`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()
        f.vm.delete(1); runCurrent()
        coEvery { f.exporter.exportToCsv(any()) } returns mockk<Uri>()

        f.vm.shareExport(); runCurrent()

        coVerify { f.exporter.exportToCsv(setOf(1L)) }
    }

    @Test
    fun `share closes the dialog, and the share uri is consumed once handed on`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        f.vm.requestExport()
        coEvery { f.exporter.exportToCsv(any()) } returns mockk<Uri>()

        f.vm.shareExport(); runCurrent()

        assertFalse(f.vm.exportDialogVisible.value)
        assertTrue(f.vm.shareUri.value != null)
        f.vm.consumeShareUri()
        assertNull(f.vm.shareUri.value)
    }

    @Test
    fun `an export with nothing visible says so and does not export`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        f.vm.delete(1); runCurrent()

        f.vm.shareExport(); runCurrent()
        assertTrue(f.vm.nothingToExport.value)
        f.vm.saveExportTo(mockk()); runCurrent()

        coVerify(exactly = 0) { f.exporter.exportToCsv(any()) }
        coVerify(exactly = 0) { f.exporter.saveCsv(any(), any()) }
        f.vm.consumeNothingToExport()
        assertFalse(f.vm.nothingToExport.value)
    }

    @Test
    fun `saving leaves out what a delete in its undo window already hides - like sharing`() = runTest {
        val f = fixture(entry(1), entry(2))
        collect(f.vm); runCurrent()
        f.vm.delete(2); runCurrent()
        val target = mockk<Uri>()
        coEvery { f.exporter.saveCsv(any(), any()) } returns Unit

        f.vm.saveExportTo(target); runCurrent()

        coVerify { f.exporter.saveCsv(target, setOf(2L)) }
    }

    @Test
    fun `saveExportTo writes into the chosen file and reports it`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        val target = mockk<Uri>()
        coEvery { f.exporter.saveCsv(any(), any()) } returns Unit

        f.vm.saveExportTo(target); runCurrent()

        coVerify { f.exporter.saveCsv(target, emptySet()) }
        assertTrue(f.vm.saved.value)
        f.vm.consumeSaved()
        assertFalse(f.vm.saved.value)
    }

    @Test
    fun `a failed share or save reports the message - or the class name when there is none - and does not claim success`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()

        coEvery { f.exporter.exportToCsv(any()) } throws IOException("disk full")
        f.vm.shareExport(); runCurrent()
        assertEquals("disk full", f.vm.exportError.value)

        f.vm.consumeExportError()
        coEvery { f.exporter.exportToCsv(any()) } throws IllegalArgumentException()
        f.vm.shareExport(); runCurrent()
        assertEquals("auch beim Teilen hat ein Fehler ohne Meldung einen Text", "IllegalArgumentException", f.vm.exportError.value)

        f.vm.consumeExportError()
        coEvery { f.exporter.saveCsv(any(), any()) } throws IllegalStateException()
        f.vm.saveExportTo(mockk()); runCurrent()
        assertEquals("IllegalStateException", f.vm.exportError.value)
        assertFalse(f.vm.saved.value)
        assertNull(f.vm.shareUri.value)
    }

    @Test
    fun `a new export attempt replaces the error of the one before`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        coEvery { f.exporter.exportToCsv(any()) } throws IOException("disk full")
        f.vm.shareExport(); runCurrent()
        coEvery { f.exporter.exportToCsv(any()) } returns mockk<Uri>()

        f.vm.shareExport(); runCurrent()

        assertNull(f.vm.exportError.value)
    }

    @Test
    fun `a cancelled export is not an error`() = runTest {
        val f = fixture(entry(1))
        collect(f.vm); runCurrent()
        coEvery { f.exporter.exportToCsv(any()) } throws CancellationException("cancelled")

        f.vm.shareExport(); runCurrent()

        assertNull(f.vm.exportError.value)
    }

    @Test
    fun `the file name proposed to the system dialog is the logbook name with the export time`() {
        val name = fixture().vm.exportFileName()

        assertTrue(name, Regex("""logbook_\d{8}T\d{9}Z\.csv""").matches(name))
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private val plan = ImportPlan(toInsert = listOf(entry(0, km = 5)), duplicates = 2, rejected = emptyList(), warnings = 1)
    private val source = mockk<Uri>()

    @Test
    fun `picking a file reads it with today's date and shows the preview - nothing is written`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } returns plan
        assertEquals(ImportState.Idle, f.vm.importState.value)

        f.vm.pickImport(source); runCurrent()

        assertEquals(ImportState.Preview(plan), f.vm.importState.value)
        coVerify(exactly = 0) { f.importer.apply(any()) }
    }

    @Test
    fun `while a file is being read the state is Working`() = runTest {
        val f = fixture()
        val gate = CompletableDeferred<ImportPlan>()
        coEvery { f.importer.read(source, today) } coAnswers { gate.await() }

        f.vm.pickImport(source); runCurrent()
        assertEquals(ImportState.Working, f.vm.importState.value)

        gate.complete(plan); runCurrent()
        assertEquals(ImportState.Preview(plan), f.vm.importState.value)
    }

    @Test
    fun `each way a read can fail has its own text - the format, the size, the file itself`() = runTest {
        val f = fixture()
        fun failureOf(error: Throwable): ImportState {
            coEvery { f.importer.read(source, today) } throws error
            f.vm.pickImport(source); runCurrent()
            val state = f.vm.importState.value
            f.vm.dismissImport()
            return state
        }

        assertEquals(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_format)), failureOf(IllegalArgumentException("Required column missing: type")))
        assertEquals(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_too_large)), failureOf(FileTooLargeException("big")))
        assertEquals(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_unreadable)), failureOf(IOException("gone")))
        assertEquals(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_unreadable)), failureOf(SecurityException("no permission")))
        assertEquals(ImportState.Failed(UiText.DynamicString("odd")), failureOf(IllegalStateException("odd")))
        assertEquals("ein Fehler hat immer einen sichtbaren Text", ImportState.Failed(UiText.DynamicString("IllegalStateException")), failureOf(IllegalStateException()))
    }

    @Test
    fun `a cancelled read is not a failure`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } throws CancellationException("cancelled")

        f.vm.pickImport(source); runCurrent()

        assertEquals(ImportState.Idle, f.vm.importState.value)
    }

    @Test
    fun `confirming writes the plan and reports how many entries came in`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } returns plan
        coEvery { f.importer.apply(plan) } returns 1
        f.vm.pickImport(source); runCurrent()

        f.vm.confirmImport(); runCurrent()

        assertEquals(ImportState.Done(1), f.vm.importState.value)
        coVerify(exactly = 1) { f.importer.apply(plan) }
    }

    @Test
    fun `confirming twice writes once - a second tap while it works or after it is done does nothing`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } returns plan
        val gate = CompletableDeferred<Int>()
        coEvery { f.importer.apply(plan) } coAnswers { gate.await() }
        f.vm.pickImport(source); runCurrent()

        f.vm.confirmImport(); runCurrent()
        f.vm.confirmImport(); runCurrent()
        gate.complete(1); runCurrent()
        f.vm.confirmImport(); runCurrent()

        coVerify(exactly = 1) { f.importer.apply(any()) }
        assertEquals(ImportState.Done(1), f.vm.importState.value)
    }

    @Test
    fun `confirming without a preview writes nothing`() = runTest {
        val f = fixture()

        f.vm.confirmImport(); runCurrent()

        assertEquals(ImportState.Idle, f.vm.importState.value)
        coVerify(exactly = 0) { f.importer.apply(any()) }
    }

    @Test
    fun `a failing write says nothing was imported`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } returns plan
        coEvery { f.importer.apply(plan) } throws IllegalStateException("disk full")
        f.vm.pickImport(source); runCurrent()

        f.vm.confirmImport(); runCurrent()

        assertEquals(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_write)), f.vm.importState.value)
    }

    @Test
    fun `dismissing a preview, a result or an error goes back to idle and writes nothing`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(source, today) } returns plan
        f.vm.pickImport(source); runCurrent()

        f.vm.dismissImport()

        assertEquals(ImportState.Idle, f.vm.importState.value)
        coVerify(exactly = 0) { f.importer.apply(any()) }
    }

    @Test
    fun `dismissing while it works changes nothing, and a second pick is ignored`() = runTest {
        val f = fixture()
        val gate = CompletableDeferred<ImportPlan>()
        coEvery { f.importer.read(source, today) } coAnswers { gate.await() }
        f.vm.pickImport(source); runCurrent()

        f.vm.dismissImport()
        f.vm.pickImport(source); runCurrent()

        assertEquals(ImportState.Working, f.vm.importState.value)
        coVerify(exactly = 1) { f.importer.read(any(), any()) }
        gate.complete(plan); runCurrent()
    }

    // ── Auswertung ────────────────────────────────────────────────────────────

    private fun oil(id: Long, km: Int, ml: Int) = entry(id, LogType.OIL, km).copy(amountMl = ml)

    private fun TestScope.collectInsights(vm: LogbookViewModel) {
        backgroundScope.launch { vm.insights.collect {} }
        backgroundScope.launch { vm.entries.collect {} }
    }

    @Test
    fun `the insights are calculated from what the repository gives and follow its changes`() = runTest {
        val f = fixture(oil(1, 10_000, 100), oil(2, 10_500, 50))
        collectInsights(f.vm); runCurrent()

        assertEquals(100.0, f.vm.insights.value.oil.summary!!.averageMlPer1000Km, 1e-9)

        f.entries.value = f.entries.value + oil(3, 11_500, 150)
        runCurrent()

        assertEquals(200.0 / 1.5, f.vm.insights.value.oil.summary!!.averageMlPer1000Km, 1e-9)
    }

    @Test
    fun `the insights are not narrowed by the filter, the cards show the whole logbook`() = runTest {
        val f = fixture(oil(1, 10_000, 100), oil(2, 10_500, 50), entry(3, LogType.CHAIN_SMALL))
        collectInsights(f.vm); runCurrent()

        f.vm.setFilter(LogFilter.Fuel); runCurrent()

        assertEquals(1, f.vm.insights.value.oil.intervals.size)
        assertEquals(6, f.vm.insights.value.maintenance.size)
    }

    @Test
    fun `an entry in the undo window no longer counts in the insights, and undo brings it back`() = runTest {
        val f = fixture(oil(1, 10_000, 100), oil(2, 10_500, 50))
        collectInsights(f.vm); runCurrent()

        f.vm.delete(2); runCurrent()
        assertNull(f.vm.insights.value.oil.summary)

        f.vm.undoDelete(); runCurrent()
        assertEquals(1, f.vm.insights.value.oil.intervals.size)
    }

    @Test
    fun `an empty logbook has empty insights`() = runTest {
        val f = fixture()
        collectInsights(f.vm); runCurrent()

        assertNull(f.vm.insights.value.fuel.summary)
        assertTrue(f.vm.insights.value.consumption.isEmpty())
    }

    // ── Wartungs-Uebersicht und Intervalle (Phase 3) ──────────────────────────

    private fun TestScope.collectMaintenance(vm: LogbookViewModel) {
        backgroundScope.launch { vm.maintenance.collect {} }
        backgroundScope.launch { vm.entries.collect {} }
    }

    private fun chain(id: Long, km: Int) = entry(id, LogType.CHAIN_SMALL, km)

    @Test
    fun `without anything there are six rows, none planned and no reading`() = runTest {
        val f = fixture()
        collectMaintenance(f.vm); runCurrent()

        val state = f.vm.maintenance.value
        assertEquals(6, state.rows.size)
        assertTrue(state.rows.all { it.due == MaintenanceDue.NotSet })
        assertNull(state.loggedKm)
        assertNull(state.estimatedKm)
        assertEquals(0, state.gpsShareKm)
    }

    @Test
    fun `the due state is counted from the logged reading when there is no gps counter`() = runTest {
        val f = fixture(chain(1, 40_000), entry(2, LogType.FUEL, 40_300))
        f.intervals.value = mapOf(LogType.CHAIN_SMALL to 1_000)
        collectMaintenance(f.vm); runCurrent()

        val row = f.vm.maintenance.value.rows.first { it.item.type == LogType.CHAIN_SMALL }
        assertEquals(1_000, row.intervalKm)
        assertEquals(MaintenanceDue.Planned(700, DueState.Ok), row.due)
        assertEquals(40_300, f.vm.maintenance.value.loggedKm)
        assertEquals(40_300, f.vm.maintenance.value.estimatedKm)
        assertEquals(0, f.vm.maintenance.value.gpsShareKm)
    }

    @Test
    fun `the gps counter of the highest reading moves the estimate and the due state`() = runTest {
        val f = fixture(chain(1, 40_000), entry(2, LogType.FUEL, 40_300))
        f.intervals.value = mapOf(LogType.CHAIN_SMALL to 1_000)
        f.counter.value = GpsOdometer(40_300, 612.4f)
        collectMaintenance(f.vm); runCurrent()

        val state = f.vm.maintenance.value
        // 40 300 + 612 = 40 912 geschaetzt; Rest 40 000 + 1 000 - 40 912 = 88, im letzten Zehntel
        assertEquals(40_912, state.estimatedKm)
        assertEquals(612, state.gpsShareKm)
        assertEquals(MaintenanceDue.Planned(88, DueState.Soon), state.rows.first { it.item.type == LogType.CHAIN_SMALL }.due)
    }

    @Test
    fun `a gps counter of another reading is ignored`() = runTest {
        val f = fixture(chain(1, 40_000))
        f.intervals.value = mapOf(LogType.CHAIN_SMALL to 1_000)
        f.counter.value = GpsOdometer(39_000, 500f)
        collectMaintenance(f.vm); runCurrent()

        assertEquals(40_000, f.vm.maintenance.value.estimatedKm)
        assertEquals(0, f.vm.maintenance.value.gpsShareKm)
    }

    @Test
    fun `setting an interval writes it and the row follows, zero clears it`() = runTest {
        val f = fixture(chain(1, 40_000))
        collectMaintenance(f.vm); runCurrent()

        f.vm.setInterval(LogType.CHAIN_SMALL, 1_000); runCurrent()
        assertEquals(1_000, f.vm.maintenance.value.rows.first { it.item.type == LogType.CHAIN_SMALL }.intervalKm)

        f.vm.setInterval(LogType.CHAIN_SMALL, 0); runCurrent()
        assertNull(f.vm.maintenance.value.rows.first { it.item.type == LogType.CHAIN_SMALL }.intervalKm)
    }

    @Test
    fun `an interval outside the limits or for a kind without one is not written`() = runTest {
        val f = fixture(chain(1, 40_000))
        collectMaintenance(f.vm); runCurrent()

        f.vm.setInterval(LogType.CHAIN_SMALL, 49); runCurrent()
        f.vm.setInterval(LogType.CHAIN_SMALL, 100_001); runCurrent()
        f.vm.setInterval(LogType.FUEL, 1_000); runCurrent()

        coVerify(exactly = 0) { f.prefs.setMaintenanceInterval(any(), any()) }
    }

    @Test
    fun `an entry in the undo window no longer counts for the overview`() = runTest {
        val f = fixture(chain(1, 40_000), chain(2, 40_500))
        collectMaintenance(f.vm); runCurrent()
        assertEquals(40_500, f.vm.maintenance.value.rows.first { it.item.type == LogType.CHAIN_SMALL }.item.last!!.odometerKm)

        f.vm.delete(2); runCurrent()

        assertEquals(40_000, f.vm.maintenance.value.rows.first { it.item.type == LogType.CHAIN_SMALL }.item.last!!.odometerKm)
        assertEquals(40_000, f.vm.maintenance.value.loggedKm)
    }
}
