package de.adv990de.obd2.ui.dtc

import androidx.lifecycle.ViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.data.DtcHistoryEntry
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.UNDO_WINDOW_MS
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
class DtcViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private class Fixture(
        val connected: MutableStateFlow<Boolean>,
        val stored: MutableStateFlow<List<DtcHistoryEntry>>,
        val obd: ObdRepository,
        val historyRepo: DtcHistoryRepository,
        val vm: DtcViewModel,
    )

    private fun entry(code: String, lastSeen: Long = 2_000L, pending: Boolean = false) =
        DtcHistoryEntry(code, "text of $code", firstSeen = 1_000L, lastSeen = lastSeen, lastWasPending = pending)

    private fun fixture(
        connected: Boolean = true,
        stored: List<DtcHistoryEntry> = emptyList(),
        live: List<DtcEntry> = emptyList(),
    ): Fixture {
        val connectedFlow = MutableStateFlow(connected)
        val storedFlow = MutableStateFlow(stored)
        val obd = mockk<ObdRepository> {
            every { isConnectedFlow } returns connectedFlow
            coEvery { readDtcs() } returns live
        }
        val historyRepo = mockk<DtcHistoryRepository> {
            every { history } returns storedFlow
            coEvery { record(any(), any()) } just Runs
            // Like the database: a delete drops the rows and the history flow re-emits.
            coEvery { delete(any()) } answers {
                val gone = firstArg<Collection<String>>().toSet()
                storedFlow.value = storedFlow.value.filter { it.code !in gone }
            }
        }
        // The app scope shares the test scheduler, so virtual time drives the undo window.
        val vm = DtcViewModel(obd, historyRepo, CoroutineScope(testDispatcher + Job()))
        return Fixture(connectedFlow, storedFlow, obd, historyRepo, vm)
    }

    /** `history` is `WhileSubscribed`, so something has to collect it for the upstream to run. */
    private fun TestScope.collect(vm: DtcViewModel): Job = launch { vm.history.collect {} }

    private fun DtcViewModel.leaveScreen() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    // NOTE: tests inside the undo window use runCurrent(), never advanceUntilIdle(): the latter jumps virtual
    // time over the 10 s window too, so the delete would already be committed.

    // ── the live list (unchanged behaviour) ───────────────────────────────────

    @Test
    fun `refresh sets dtcs on success`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", "Misfire cylinder 1", false))
        val f = fixture(live = dtcs)

        f.vm.refresh()
        advanceUntilIdle()

        assertEquals(dtcs, f.vm.uiState.value.dtcs)
        assertNull(f.vm.uiState.value.error)
        assertFalse(f.vm.uiState.value.isLoading)
    }

    @Test
    fun `refresh sets error on exception`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws Exception("DTC read failed")

        f.vm.refresh()
        advanceUntilIdle()

        assertEquals(UiText.DynamicString("DTC read failed"), f.vm.uiState.value.error)
        assertFalse(f.vm.uiState.value.isLoading)
    }

    @Test
    fun `refresh with an exception WITHOUT a message still sets an error (never a false all-clear)`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws RuntimeException()

        f.vm.refresh()
        advanceUntilIdle()

        assertNotNull("null als Fehler liesse die Seite 'Keine Fehlercodes' zeigen", f.vm.uiState.value.error)
        assertTrue(f.vm.uiState.value.dtcs.isEmpty())
    }

    @Test
    fun `refresh says the ECU did not answer - it never shows an all-clear for NO DATA (A9)`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws ObdException.NoData("03")

        f.vm.refresh()
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.dtc_no_answer), f.vm.uiState.value.error)
        assertTrue(f.vm.uiState.value.dtcs.isEmpty())
        coVerify(exactly = 0) { f.historyRepo.record(any(), any()) }     // eine Nicht-Antwort wird nicht als "keine Codes" gespeichert
    }

    @Test
    fun `refresh maps a known adapter failure to its localized text`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws ObdException.Timeout("03")

        f.vm.refresh()
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.obd_err_timeout), f.vm.uiState.value.error)
    }

    @Test
    fun `isLoading transitions correctly`() = runTest {
        val deferred = CompletableDeferred<List<DtcEntry>>()
        val f = fixture()
        coEvery { f.obd.readDtcs() } coAnswers { deferred.await() }

        f.vm.refresh()
        advanceUntilIdle() // runs until coroutine suspends on deferred.await()

        assertTrue(f.vm.uiState.value.isLoading)

        deferred.complete(emptyList())
        advanceUntilIdle()

        assertFalse(f.vm.uiState.value.isLoading)
    }

    @Test
    fun `CancellationException is rethrown`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws CancellationException("cancelled")

        f.vm.refresh()
        advanceUntilIdle()

        // CancellationException was rethrown, so error was never set
        assertNull(f.vm.uiState.value.error)
    }

    // ── connection ────────────────────────────────────────────────────────────

    @Test
    fun `isConnected follows the repository`() = runTest {
        val f = fixture(connected = false)
        assertFalse(f.vm.isConnected.value)

        f.connected.value = true

        assertTrue(f.vm.isConnected.value)
    }

    @Test
    fun `refresh without a connection reads nothing and records nothing`() = runTest {
        val f = fixture(connected = false, live = listOf(DtcEntry("P0301", "x", false)))

        f.vm.refresh()
        advanceUntilIdle()

        // readDtcs() would answer emptyList() without a connection — a wrong "all clear" — so it is not asked at all.
        coVerify(exactly = 0) { f.obd.readDtcs() }
        coVerify(exactly = 0) { f.historyRepo.record(any(), any()) }
        assertEquals(emptyList<DtcEntry>(), f.vm.uiState.value.dtcs)
        assertFalse(f.vm.uiState.value.isLoading)
    }

    @Test
    fun `a stale live list is cleared when refresh finds no connection`() = runTest {
        val f = fixture(live = listOf(DtcEntry("P0301", "x", false)))
        f.vm.refresh(); advanceUntilIdle()
        assertEquals(1, f.vm.uiState.value.dtcs.size)

        f.connected.value = false
        f.vm.refresh(); advanceUntilIdle()

        assertEquals(emptyList<DtcEntry>(), f.vm.uiState.value.dtcs)
    }

    // ── recording ─────────────────────────────────────────────────────────────

    @Test
    fun `refresh with a connection records what it read`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", "x", false), DtcEntry("P0420", "y", true))
        val f = fixture(live = dtcs)

        f.vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { f.historyRepo.record(dtcs, any()) }
    }

    @Test
    fun `a failed read records nothing`() = runTest {
        val f = fixture()
        coEvery { f.obd.readDtcs() } throws Exception("timeout")

        f.vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { f.historyRepo.record(any(), any()) }
    }

    // ── the history list ──────────────────────────────────────────────────────

    @Test
    fun `history lists the stored entries as delivered`() = runTest {
        val f = fixture(connected = false, stored = listOf(entry("P0420", lastSeen = 3_000L), entry("P0301")))
        val job = collect(f.vm); runCurrent()

        assertEquals(listOf("P0420", "P0301"), f.vm.history.value.map { it.entry.code })
        job.cancel()
    }

    @Test
    fun `an entry is active only while connected and only if the last read contained it`() = runTest {
        val f = fixture(
            connected = true,
            stored = listOf(entry("P0301"), entry("P0420")),
            live = listOf(DtcEntry("P0301", "x", false)),
        )
        val job = collect(f.vm); runCurrent()
        assertEquals("nothing is active before the first read", listOf(false, false), f.vm.history.value.map { it.isActive })

        f.vm.refresh(); runCurrent()

        assertEquals(listOf(true, false), f.vm.history.value.map { it.isActive })
        job.cancel()
    }

    @Test
    fun `nothing is active any more once the connection drops`() = runTest {
        val f = fixture(stored = listOf(entry("P0301")), live = listOf(DtcEntry("P0301", "x", false)))
        val job = collect(f.vm); runCurrent()
        f.vm.refresh(); runCurrent()
        assertTrue(f.vm.history.value.single().isActive)

        f.connected.value = false
        runCurrent()

        assertFalse(f.vm.history.value.single().isActive)
        job.cancel()
    }

    @Test
    fun `after a drop and a reconnect nothing is active until the next read - the old list says nothing about the new connection`() = runTest {
        // Sichtbar, wenn die DTC-Seite nicht offen war, waehrend die Verbindung wegbrach und zurueckkam: die Liste aus dem
        // alten Auslesen galt sonst wieder als "Aktiv", obwohl nach dem Wiederverbinden niemand das Fahrzeug gefragt hat.
        val f = fixture(stored = listOf(entry("P0301")), live = listOf(DtcEntry("P0301", "x", false)))
        val job = collect(f.vm); runCurrent()
        f.vm.refresh(); runCurrent()
        assertTrue("aktiv nach dem Auslesen", f.vm.history.value.single().isActive)

        f.connected.value = false; runCurrent()
        f.connected.value = true; runCurrent()

        assertFalse("wieder verbunden, aber nicht neu gelesen: nicht aktiv", f.vm.history.value.single().isActive)

        f.vm.refresh(); runCurrent()
        assertTrue("nach dem neuen Auslesen wieder aktiv", f.vm.history.value.single().isActive)
        job.cancel()
    }

    @Test
    fun `a slow older read cannot overwrite the result of a newer one`() = runTest {
        val f = fixture(stored = listOf(entry("A"), entry("B")))
        val gate = CompletableDeferred<Unit>()
        val older = listOf(DtcEntry("A", "old", false))
        val newer = listOf(DtcEntry("B", "new", false))
        coEvery { f.obd.readDtcs() } coAnswers { gate.await(); older } andThen newer
        val job = collect(f.vm); runCurrent()

        f.vm.refresh(); runCurrent()          // the first read hangs on the gate
        f.vm.refresh(); runCurrent()          // the second one answers at once
        assertEquals(newer, f.vm.uiState.value.dtcs)

        gate.complete(Unit); runCurrent()     // now the OLD read finishes

        assertEquals("das spaete alte Ergebnis darf das neue nicht ueberschreiben", newer, f.vm.uiState.value.dtcs)
        assertEquals(
            "nur der Code des neuen Auslesens ist aktiv",
            mapOf("A" to false, "B" to true),
            f.vm.history.value.associate { it.entry.code to it.isActive },
        )
        job.cancel()
    }

    @Test
    fun `a slow older read that fails cannot replace a newer successful result with an error`() = runTest {
        val f = fixture(stored = listOf(entry("B")))
        val gate = CompletableDeferred<Unit>()
        val newer = listOf(DtcEntry("B", "new", false))
        coEvery { f.obd.readDtcs() } coAnswers { gate.await(); throw IllegalStateException("late failure") } andThen newer

        f.vm.refresh(); runCurrent()
        f.vm.refresh(); runCurrent()
        gate.complete(Unit); runCurrent()

        assertNull("ein spaeter Fehler des alten Auslesens ueberschreibt das neue Ergebnis nicht", f.vm.uiState.value.error)
        assertEquals(newer, f.vm.uiState.value.dtcs)
    }

    @Test
    fun `a superseded read is still recorded - it was a real sighting`() = runTest {
        val f = fixture(stored = listOf(entry("A")))
        val gate = CompletableDeferred<Unit>()
        val older = listOf(DtcEntry("A", "old", false))
        coEvery { f.obd.readDtcs() } coAnswers { gate.await(); older } andThen emptyList()

        f.vm.refresh(); runCurrent()
        f.vm.refresh(); runCurrent()
        gate.complete(Unit); runCurrent()

        coVerify { f.historyRepo.record(older, any()) }
    }

    @Test
    fun `a failed read leaves every entry inactive`() = runTest {
        val f = fixture(stored = listOf(entry("P0301")))
        coEvery { f.obd.readDtcs() } throws Exception("timeout")
        val job = collect(f.vm); runCurrent()

        f.vm.refresh(); runCurrent()

        assertFalse(f.vm.history.value.single().isActive)
        job.cancel()
    }

    @Test
    fun `a failed read after a successful one clears the active marks`() = runTest {
        val f = fixture(stored = listOf(entry("P0301")), live = listOf(DtcEntry("P0301", "x", false)))
        val job = collect(f.vm); runCurrent()
        f.vm.refresh(); runCurrent()
        assertTrue("active after the successful read", f.vm.history.value.single().isActive)

        // The vehicle's state is unknown now — a stale "Aktiv" would claim something nobody verified.
        coEvery { f.obd.readDtcs() } throws Exception("timeout")
        f.vm.refresh(); runCurrent()

        assertFalse(f.vm.history.value.single().isActive)
        job.cancel()
    }

    // ── delete: row trash or swipe, no dialog, undo only ──────────────────────

    @Test
    fun `deleteEntry hides the entry at once but deletes nothing yet`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420")))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteEntry("P0301")
        runCurrent()

        assertEquals(listOf("P0420"), f.vm.history.value.map { it.entry.code })
        assertNotNull(f.vm.undoBatchId.value)
        assertEquals(1, f.vm.undoCount.value)
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }
        job.cancel()
    }

    @Test
    fun `deleteEntry deletes for real after the undo window`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420")))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteEntry("P0301")
        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.historyRepo.delete(setOf("P0301")) }
        assertNull(f.vm.undoBatchId.value)
        job.cancel()
    }

    @Test
    fun `undoDelete inside the window brings the entry back and never deletes`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420")))
        val job = collect(f.vm); runCurrent()
        f.vm.deleteEntry("P0301"); runCurrent()

        f.vm.undoDelete()
        advanceTimeBy(60_000); runCurrent()

        assertEquals(listOf("P0301", "P0420"), f.vm.history.value.map { it.entry.code })
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }
        job.cancel()
    }

    @Test
    fun `a second delete commits the first one at once and only the second stays undoable`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420"), entry("P0171")))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteEntry("P0301")
        val first = f.vm.undoBatchId.value
        f.vm.deleteEntry("P0420")
        runCurrent()

        coVerify(exactly = 1) { f.historyRepo.delete(setOf("P0301")) }
        coVerify(exactly = 0) { f.historyRepo.delete(setOf("P0420")) }
        assertTrue(f.vm.undoBatchId.value != first)
        job.cancel()
    }

    // ── delete all: dialog + undo ─────────────────────────────────────────────

    @Test
    fun `requestDeleteAll opens the dialog only when there is something to delete`() = runTest {
        val empty = fixture()
        val emptyJob = collect(empty.vm); runCurrent()
        empty.vm.requestDeleteAll()
        assertFalse(empty.vm.deleteAllConfirmationVisible.value)
        emptyJob.cancel()

        val filled = fixture(stored = listOf(entry("P0301")))
        val filledJob = collect(filled.vm); runCurrent()
        filled.vm.requestDeleteAll()
        assertTrue(filled.vm.deleteAllConfirmationVisible.value)
        filledJob.cancel()
    }

    @Test
    fun `cancelDeleteAll closes the dialog and deletes nothing`() = runTest {
        val f = fixture(stored = listOf(entry("P0301")))
        val job = collect(f.vm); runCurrent()
        f.vm.requestDeleteAll()

        f.vm.cancelDeleteAll()
        advanceTimeBy(60_000); runCurrent()

        assertFalse(f.vm.deleteAllConfirmationVisible.value)
        assertEquals(1, f.vm.history.value.size)
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }
        job.cancel()
    }

    @Test
    fun `confirmDeleteAll hides every entry and deletes exactly those after the window`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420"), entry("P0171")))
        val job = collect(f.vm); runCurrent()
        f.vm.requestDeleteAll()

        f.vm.confirmDeleteAll()
        runCurrent()

        assertFalse(f.vm.deleteAllConfirmationVisible.value)
        assertEquals(emptyList<String>(), f.vm.history.value.map { it.entry.code })
        assertEquals(3, f.vm.undoCount.value)
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }

        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.historyRepo.delete(setOf("P0301", "P0420", "P0171")) }
        job.cancel()
    }

    @Test
    fun `a code recorded inside the undo window is not swept up by delete all`() = runTest {
        val f = fixture(stored = listOf(entry("P0301")))
        val job = collect(f.vm); runCurrent()
        f.vm.confirmDeleteAll(); runCurrent()

        // A refresh records a NEW code while the snackbar is still up.
        f.stored.value = f.stored.value + entry("P0420")
        advanceTimeBy(UNDO_WINDOW_MS); runCurrent()

        coVerify(exactly = 1) { f.historyRepo.delete(setOf("P0301")) }
        coVerify(exactly = 0) { f.historyRepo.delete(match { "P0420" in it }) }
        assertEquals(listOf("P0420"), f.vm.history.value.map { it.entry.code })
        job.cancel()
    }

    @Test
    fun `undoDelete after delete all restores everything`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420")))
        val job = collect(f.vm); runCurrent()
        f.vm.confirmDeleteAll(); runCurrent()

        f.vm.undoDelete()
        advanceTimeBy(60_000); runCurrent()

        assertEquals(2, f.vm.history.value.size)
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }
        job.cancel()
    }

    @Test
    fun `undoCount follows the latest batch and an entry just deleted is not counted again`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420"), entry("P0171")))
        val job = collect(f.vm); runCurrent()

        f.vm.deleteEntry("P0301")
        assertEquals(1, f.vm.undoCount.value)
        // No runCurrent() in between: the history list trails the pending set by one emission.
        f.vm.confirmDeleteAll()

        assertEquals(2, f.vm.undoCount.value)
        job.cancel()
    }

    // ── leaving the screen ────────────────────────────────────────────────────

    @Test
    fun `leaving the screen commits an open delete at once`() = runTest {
        val f = fixture(stored = listOf(entry("P0301"), entry("P0420")))
        val job = collect(f.vm); runCurrent()
        f.vm.deleteEntry("P0420"); runCurrent()
        coVerify(exactly = 0) { f.historyRepo.delete(any()) }

        f.vm.leaveScreen()
        runCurrent()

        coVerify(exactly = 1) { f.historyRepo.delete(setOf("P0420")) }
        job.cancel()
    }
}
