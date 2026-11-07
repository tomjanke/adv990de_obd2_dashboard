package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogbookOdometer
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.MaintenanceDue
import de.adv990de.obd2.logbook.DueState
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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
        val vm = LogbookViewModel(repo, prefs, odometer, CoroutineScope(testDispatcher + Job()))
        return Fixture(entries, repo, vm, intervals, counter, prefs)
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
