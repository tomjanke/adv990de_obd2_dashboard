package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.logbook.FormError
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class LogEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Fest und fern vom echten Datum. */
    private val today = LocalDate.of(2031, 3, 14)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val entries: MutableStateFlow<List<LogEntry>>,
        val repo: VehicleLogRepository,
        val fuel: FuelRepository,
        val gauge: MutableStateFlow<FuelGaugeState?>,
        val vm: LogEntryViewModel,
    )

    private fun fixture(
        id: Long? = null,
        entries: List<LogEntry> = emptyList(),
        gauge: FuelGaugeState? = FuelGaugeState(rangeFullKm = 300, kmSinceFill = 100f),
        separator: Char = ',',
    ): Fixture {
        val entriesFlow = MutableStateFlow(entries)
        val gaugeFlow = MutableStateFlow(gauge)
        val repo = mockk<VehicleLogRepository>()
        every { repo.entries } returns entriesFlow
        coEvery { repo.get(any()) } answers { val id = firstArg<Long>(); entriesFlow.value.firstOrNull { it.id == id } }
        coEvery { repo.latestByOdometer() } answers { entriesFlow.value.maxByOrNull { it.odometerKm } }
        coEvery { repo.save(any()) } answers { firstArg<LogEntry>().id.takeIf { it > 0 } ?: 99L }
        val fuel = mockk<FuelRepository> {
            every { state } returns gaugeFlow
            every { reset() } just Runs
            every { credit(any()) } just Runs
        }
        val handle = SavedStateHandle(if (id == null) emptyMap() else mapOf("id" to id))
        val vm = LogEntryViewModel(handle, repo, fuel, today = { today }, decimalSeparator = separator)
        return Fixture(entriesFlow, repo, fuel, gaugeFlow, vm)
    }

    private fun TestScope.collect(vm: LogEntryViewModel) {
        backgroundScope.launch { vm.check.collect {} }
        backgroundScope.launch { vm.visibleErrors.collect {} }
        backgroundScope.launch { vm.pricePerLiterCents.collect {} }
    }

    private fun LogEntryViewModel.fillFuel(odometer: String = "40362", liters: String = "14,37", cost: String = "27,43") {
        setOdometer(odometer); setAmount(liters); setCost(cost)
    }

    private fun entry(id: Long, type: LogType = LogType.FUEL, km: Int = 40_000, date: LocalDate = today.minusDays(5)) = LogEntry(
        id = id, type = type, date = date, odometerKm = km,
        amountMl = if (type.hasAmount) 14_370 else null, fullTank = if (type == LogType.FUEL) true else null,
        costCents = if (type == LogType.FUEL) 2_743 else null, note = "alt", brand = if (type.hasBrand) "Heidenau K60" else null,
    )

    // ── ein neuer Eintrag ─────────────────────────────────────────────────────

    @Test
    fun `a new entry starts as a full tank today with empty fields`() = runTest {
        val f = fixture()
        runCurrent()

        assertFalse(f.vm.isEditing)
        assertTrue(f.vm.loaded.value)
        val form = f.vm.form.value
        assertEquals(LogType.FUEL, form.type)
        assertEquals(today, form.date)
        assertTrue(form.fullTank)
        assertEquals(listOf("", "", "", ""), listOf(form.odometer, form.amount, form.cost, form.note))
    }

    @Test
    fun `the form can be saved once the fields make a valid entry - not before`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        assertNull(f.vm.check.value.entry)

        f.vm.fillFuel(); runCurrent()

        assertNotNull(f.vm.check.value.entry)
        assertEquals(14_370, f.vm.check.value.entry!!.amountMl)
    }

    @Test
    fun `an error shows at a field only after the user touched it - or tried to save`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        assertTrue("noch nichts getippt, noch kein Fehler zu sehen", f.vm.visibleErrors.value.isEmpty())

        f.vm.setOdometer("abc"); runCurrent()
        assertEquals(setOf(FormField.ODOMETER), f.vm.visibleErrors.value.keys)

        f.vm.save(); runCurrent()
        assertEquals("nach dem Speichern-Versuch sieht man alle", setOf(FormField.ODOMETER, FormField.AMOUNT), f.vm.visibleErrors.value.keys)
        coVerify(exactly = 0) { f.repo.save(any()) }
    }

    // ── nach einem fehlgeschlagenen Speichern zum ersten Fehler scrollen ──

    @Test
    fun `a failed save asks the page to scroll to the topmost field with an error`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        assertNull("vor dem ersten Versuch gibt es nichts zu scrollen", f.vm.scrollToError.value)
        f.vm.setOdometer("abc"); runCurrent()       // der Stand ist ungueltig, die Menge fehlt: oben steht der Stand

        f.vm.save(); runCurrent()

        assertEquals(FormField.ODOMETER, f.vm.scrollToError.value!!.field)
        coVerify(exactly = 0) { f.repo.save(any()) }
    }

    @Test
    fun `every failed save asks again, also when the same field is still wrong`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        f.vm.setOdometer("abc"); runCurrent()

        f.vm.save(); runCurrent()
        val first = f.vm.scrollToError.value!!
        f.vm.save(); runCurrent()
        val second = f.vm.scrollToError.value!!

        assertEquals("dasselbe Feld", first.field, second.field)
        assertTrue("aber eine neue Anfrage, sonst scrollt die Seite beim zweiten Versuch nicht", first != second)
    }

    @Test
    fun `a save that is valid, or only asks about warnings, does not scroll`() = runTest {
        val warn = fixtureWithConflict()
        collect(warn.vm); runCurrent()
        warn.vm.fillFuel(odometer = "40362"); runCurrent()

        warn.vm.save(); runCurrent()

        assertTrue("die Rueckfrage ist offen", warn.vm.pendingWarnings.value.isNotEmpty())
        assertNull(warn.vm.scrollToError.value)

        val ok = fixture()
        collect(ok.vm); runCurrent()
        ok.vm.fillFuel(); runCurrent()
        ok.vm.save(); runCurrent()

        assertNull(ok.vm.scrollToError.value)
    }

    @Test
    fun `saving a valid entry writes it as a new row and finishes`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        f.vm.fillFuel(); f.vm.setNote("Shell"); runCurrent()

        f.vm.save(); runCurrent()

        val saved = slot<LogEntry>()
        coVerify(exactly = 1) { f.repo.save(capture(saved)) }
        assertEquals(LogEntry(0, LogType.FUEL, today, 40_362, 14_370, true, 2_743, "Shell", null), saved.captured)
        assertEquals("neue volle Tankung von heute: die Frage nach der Tankanzeige folgt", SaveOutcome.AskFuelGaugeReset, f.vm.outcome.value)
    }

    @Test
    fun `a second tap while it saves writes once`() = runTest {
        val f = fixture()
        collect(f.vm)
        val gate = CompletableDeferred<Long>()
        coEvery { f.repo.save(any()) } coAnswers { gate.await() }
        f.vm.fillFuel(); runCurrent()

        f.vm.save(); f.vm.save(); runCurrent()          // zwei Taps, bevor der erste etwas getan hat
        assertTrue(f.vm.isSaving.value)
        f.vm.save(); runCurrent()                       // und einer, waehrend es schreibt
        gate.complete(5L); runCurrent()

        coVerify(exactly = 1) { f.repo.save(any()) }
        assertFalse(f.vm.isSaving.value)
    }

    @Test
    fun `a failed save reports the text and does not finish - a cancellation is no error`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        f.vm.fillFuel(); runCurrent()
        coEvery { f.repo.save(any()) } throws IOException("disk full")

        f.vm.save(); runCurrent()
        assertEquals("disk full", f.vm.saveError.value)
        assertNull(f.vm.outcome.value)
        assertFalse(f.vm.isSaving.value)

        f.vm.consumeSaveError()
        coEvery { f.repo.save(any()) } throws IllegalStateException()
        f.vm.save(); runCurrent()
        assertEquals("ein Fehler ohne Meldung hat trotzdem einen Text", "IllegalStateException", f.vm.saveError.value)

        f.vm.consumeSaveError()
        coEvery { f.repo.save(any()) } throws CancellationException("cancelled")
        f.vm.save(); runCurrent()
        assertNull(f.vm.saveError.value)
    }

    // ── Warnungen ─────────────────────────────────────────────────────────────

    private fun fixtureWithConflict() = fixture(entries = listOf(entry(1, km = 41_000, date = today.minusDays(10))))

    @Test
    fun `a warning blocks the save until the user confirms`() = runTest {
        val f = fixtureWithConflict()
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362"); runCurrent()   // frueheres Datum hat den hoeheren Stand: Widerspruch

        f.vm.save(); runCurrent()

        assertTrue("$f", f.vm.pendingWarnings.value.any { it is LogIssue.OdometerOutOfOrder })
        coVerify(exactly = 0) { f.repo.save(any()) }
        assertNull(f.vm.outcome.value)

        f.vm.confirmWarnings(); runCurrent()

        coVerify(exactly = 1) { f.repo.save(any()) }
        assertTrue(f.vm.pendingWarnings.value.isEmpty())
        assertEquals(SaveOutcome.AskFuelGaugeReset, f.vm.outcome.value)
    }

    @Test
    fun `declining the warning writes nothing and keeps the form`() = runTest {
        val f = fixtureWithConflict()
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362"); runCurrent()
        f.vm.save(); runCurrent()

        f.vm.dismissWarnings(); runCurrent()

        assertTrue(f.vm.pendingWarnings.value.isEmpty())
        coVerify(exactly = 0) { f.repo.save(any()) }
        assertEquals("40362", f.vm.form.value.odometer)
    }

    @Test
    fun `an entry without a warning is saved at once`() = runTest {
        val f = fixture(entries = listOf(entry(1, km = 39_800, date = today.minusDays(10))))   // 554 km fuer 14,37 l: 2,6 l/100 km, plausibel
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362", liters = "14,37", cost = "27,43"); runCurrent()

        f.vm.save(); runCurrent()

        assertTrue(f.vm.pendingWarnings.value.isEmpty())
        coVerify(exactly = 1) { f.repo.save(any()) }
    }

    // ── Art und Felder ────────────────────────────────────────────────────────

    @Test
    fun `changing the kind keeps what was typed but ignores the fields the new kind does not have`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        f.vm.fillFuel(); runCurrent()

        f.vm.setType(LogType.CHAIN_SMALL); runCurrent()

        assertEquals("40362", f.vm.form.value.odometer)
        val entry = f.vm.check.value.entry!!
        assertEquals(LogType.CHAIN_SMALL, entry.type)
        assertNull(entry.amountMl)
        assertNull(entry.costCents)
    }

    @Test
    fun `the price per liter follows cost and liters - and is empty while one of them is not a number`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()
        assertNull(f.vm.pricePerLiterCents.value)

        f.vm.fillFuel(liters = "14,37", cost = "27,43"); runCurrent()
        assertEquals(191, f.vm.pricePerLiterCents.value)

        f.vm.setCost("x"); runCurrent()
        assertNull(f.vm.pricePerLiterCents.value)

        f.vm.setCost(""); runCurrent()
        assertNull("ohne Kosten gibt es keinen Preis, auch keinen von 0", f.vm.pricePerLiterCents.value)
    }

    @Test
    fun `the hint shows the entry with the highest reading`() = runTest {
        val f = fixture(entries = listOf(entry(1, km = 39_000), entry(2, km = 40_362)))
        runCurrent()

        assertEquals(40_362, f.vm.lastEntry.value!!.odometerKm)
    }

    @Test
    fun `without entries there is no hint`() = runTest {
        val f = fixture()
        runCurrent()

        assertNull(f.vm.lastEntry.value)
    }

    // ── Bearbeiten ────────────────────────────────────────────────────────────

    @Test
    fun `editing loads the entry into the form with the device decimal separator, and locks the kind`() = runTest {
        val f = fixture(id = 5, entries = listOf(entry(5, LogType.FUEL, km = 40_362)), separator = ',')
        collect(f.vm); runCurrent()

        assertTrue(f.vm.isEditing)
        assertTrue(f.vm.loaded.value)
        assertEquals("14,37", f.vm.form.value.amount)
        assertEquals("27,43", f.vm.form.value.cost)
        assertEquals("alt", f.vm.form.value.note)

        f.vm.setType(LogType.OIL); runCurrent()
        assertEquals("beim Bearbeiten ist die Art gesperrt", LogType.FUEL, f.vm.form.value.type)
    }

    @Test
    fun `an English device edits with a point`() = runTest {
        val f = fixture(id = 5, entries = listOf(entry(5)), separator = '.')
        runCurrent()

        assertEquals("14.37", f.vm.form.value.amount)
    }

    @Test
    fun `saving an edit replaces the row with the same id`() = runTest {
        val f = fixture(id = 5, entries = listOf(entry(5, km = 40_362)))
        collect(f.vm); runCurrent()
        f.vm.setNote("korrigiert"); runCurrent()

        f.vm.save(); runCurrent()

        val saved = slot<LogEntry>()
        coVerify { f.repo.save(capture(saved)) }
        assertEquals(5L, saved.captured.id)
        assertEquals("korrigiert", saved.captured.note)
    }

    @Test
    fun `the entry being edited is not compared with itself`() = runTest {
        // Der gespeicherte Eintrag liegt am Tag -5 bei 40362. Schiebt der Nutzer ihn auf Tag -20 bei 41000, stuende die alte Fassung "spaeter mit niedrigerem Stand"
        // dagegen — ein Widerspruch, den es nur gaebe, wenn der Eintrag mit sich selbst verglichen wuerde.
        val f = fixture(id = 5, entries = listOf(entry(5, km = 40_362, date = today.minusDays(5))))
        collect(f.vm); runCurrent()
        f.vm.setDate(today.minusDays(20)); f.vm.setOdometer("41000"); runCurrent()

        assertTrue("$f", f.vm.check.value.warnings.isEmpty())
        assertNotNull(f.vm.check.value.entry)
    }

    @Test
    fun `saving an edit does not ask about a conflict with the old version of the same entry`() = runTest {
        val f = fixture(id = 5, entries = listOf(entry(5, km = 40_362, date = today.minusDays(5))))
        collect(f.vm); runCurrent()
        f.vm.setDate(today.minusDays(20)); f.vm.setOdometer("41000"); runCurrent()

        f.vm.save(); runCurrent()

        assertTrue("keine Rueckfrage: ${f.vm.pendingWarnings.value}", f.vm.pendingWarnings.value.isEmpty())
        coVerify(exactly = 1) { f.repo.save(any()) }
    }

    @Test
    fun `editing an entry that does not exist any more says so`() = runTest {
        val f = fixture(id = 77, entries = listOf(entry(1)))
        runCurrent()

        assertTrue(f.vm.notFound.value)
        assertFalse(f.vm.loaded.value)
    }

    @Test
    fun `an unfinished edit cannot be saved before the entry has loaded`() = runTest {
        val gate = CompletableDeferred<LogEntry?>()
        val f = fixture(id = 5, entries = listOf(entry(5)))
        coEvery { f.repo.get(5) } coAnswers { gate.await() }
        val vm = LogEntryViewModel(SavedStateHandle(mapOf("id" to 5L)), f.repo, f.fuel, today = { today }, decimalSeparator = ',')
        runCurrent()
        assertFalse(vm.loaded.value)
        vm.setOdometer("40362"); vm.setAmount("14,37"); runCurrent()   // ein gueltiges Formular, noch bevor der Eintrag geladen ist

        vm.save(); runCurrent()

        coVerify(exactly = 0) { f.repo.save(any()) }
        gate.complete(entry(5)); runCurrent()
        assertTrue(vm.loaded.value)
    }

    // ── Tankanzeige ───────────────────────────────────────────────────────────

    private suspend fun TestScope.saveFuel(f: Fixture, configure: LogEntryViewModel.() -> Unit = {}) {
        collect(f.vm); runCurrent()
        f.vm.fillFuel(); f.vm.configure(); runCurrent()
        f.vm.save(); runCurrent()
    }

    @Test
    fun `a new full tank of today asks whether the fuel gauge should go back to full`() = runTest {
        val f = fixture()
        saveFuel(f)

        assertEquals(SaveOutcome.AskFuelGaugeReset, f.vm.outcome.value)
        verify(exactly = 0) { f.fuel.reset() }
    }

    @Test
    fun `yes resets the gauge and finishes - no leaves it alone and finishes`() = runTest {
        val yes = fixture()
        saveFuel(yes)
        yes.vm.resetFuelGauge()
        verify(exactly = 1) { yes.fuel.reset() }
        assertEquals(SaveOutcome.Done, yes.vm.outcome.value)

        val no = fixture()
        saveFuel(no)
        no.vm.declineFuelGaugeReset()
        verify(exactly = 0) { no.fuel.reset() }
        assertEquals(SaveOutcome.Done, no.vm.outcome.value)
    }

    @Test
    fun `no question for a partial fill, another day, another kind, an edit or without a fuel gauge`() = runTest {
        val partial = fixture(); saveFuel(partial) { setFullTank(false) }
        assertEquals("Teiltankung", SaveOutcome.Done, partial.vm.outcome.value)

        val otherDay = fixture(); saveFuel(otherDay) { setDate(today.minusDays(1)) }
        assertEquals("nachgetragen", SaveOutcome.Done, otherDay.vm.outcome.value)

        val oil = fixture(); saveFuel(oil) { setType(LogType.OIL); setAmount("150") }
        assertEquals("kein Tanken", SaveOutcome.Done, oil.vm.outcome.value)

        val noGauge = fixture(gauge = null); saveFuel(noGauge)
        assertEquals("ohne Tankanzeige gibt es nichts zurueckzusetzen", SaveOutcome.Done, noGauge.vm.outcome.value)

        val edit = fixture(id = 5, entries = listOf(entry(5, km = 40_362, date = today)))
        edit.vm.let { vm ->
            collect(vm); runCurrent(); vm.setNote("x"); runCurrent(); vm.save(); runCurrent()
        }
        assertEquals("beim Bearbeiten nie", SaveOutcome.Done, edit.vm.outcome.value)
    }

    // ── Teilbetankung: die Tankanzeige anheben (F5, Phase C) ──────────────────

    private val fillDay = LocalDate.of(2031, 1, 1)

    private fun pastFill(id: Long, km: Int, ml: Int) = LogEntry(
        id = id, type = LogType.FUEL, date = fillDay.plusDays(id), odometerKm = km, amountMl = ml, fullTank = true,
        costCents = null, note = null, brand = null,
    )

    /** Eine erste volle Tankung und [intervals] Intervalle zu je 100 km und 7,7 l = 7,7 l/100 km (erfundene Zahlen). */
    private fun steadyPast(intervals: Int) = listOf(pastFill(0, 1_000, 15_000)) + List(intervals) { pastFill(it + 1L, 1_100 + it * 100, 7_700) }

    @Test
    fun `a new part fill of today with a known consumption asks whether to raise the fuel gauge`() = runTest {
        val f = fixture(entries = steadyPast(3))
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); f.vm.setFullTank(false); runCurrent()
        f.vm.save(); runCurrent()

        // 6,2 l bei 7,7 l/100 km = 80,5 km -> 81
        assertEquals(SaveOutcome.AskFuelGaugeCredit(amountMl = 6_200, km = 81), f.vm.outcome.value)
        verify(exactly = 0) { f.fuel.credit(any()) }
        verify(exactly = 0) { f.fuel.reset() }
    }

    @Test
    fun `yes raises the gauge by the shown kilometers and finishes - no leaves it alone and finishes`() = runTest {
        val yes = fixture(entries = steadyPast(3))
        collect(yes.vm); runCurrent()
        yes.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); yes.vm.setFullTank(false); runCurrent()
        yes.vm.save(); runCurrent()
        yes.vm.creditFuelGauge()
        verify(exactly = 1) { yes.fuel.credit(81f) }
        assertEquals(SaveOutcome.Done, yes.vm.outcome.value)

        val no = fixture(entries = steadyPast(3))
        collect(no.vm); runCurrent()
        no.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); no.vm.setFullTank(false); runCurrent()
        no.vm.save(); runCurrent()
        no.vm.declineFuelGaugeCredit()
        verify(exactly = 0) { no.fuel.credit(any()) }
        assertEquals(SaveOutcome.Done, no.vm.outcome.value)
    }

    @Test
    fun `answering the credit question when there is none does nothing`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        f.vm.creditFuelGauge()
        f.vm.declineFuelGaugeCredit()

        verify(exactly = 0) { f.fuel.credit(any()) }
        assertNull(f.vm.outcome.value)
    }

    @Test
    fun `no credit question without a known consumption - fewer than three intervals`() = runTest {
        for (intervals in 0..2) {
            val f = fixture(entries = steadyPast(intervals))
            collect(f.vm); runCurrent()
            f.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); f.vm.setFullTank(false); runCurrent()
            f.vm.save(); runCurrent()

            assertEquals("$intervals Intervalle", SaveOutcome.Done, f.vm.outcome.value)
        }
    }

    @Test
    fun `no credit question for another day, an edit, another kind, a full tank or without a fuel gauge`() = runTest {
        suspend fun TestScope.partial(f: Fixture, configure: LogEntryViewModel.() -> Unit = {}) {
            collect(f.vm); runCurrent()
            f.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); f.vm.setFullTank(false); f.vm.configure(); runCurrent()
            f.vm.save(); runCurrent()
        }

        val otherDay = fixture(entries = steadyPast(3)); partial(otherDay) { setDate(today.minusDays(1)) }
        assertEquals("nachgetragen", SaveOutcome.Done, otherDay.vm.outcome.value)

        val noGauge = fixture(entries = steadyPast(3), gauge = null); partial(noGauge)
        assertEquals("ohne Tankanzeige gibt es nichts anzuheben", SaveOutcome.Done, noGauge.vm.outcome.value)

        val oil = fixture(entries = steadyPast(3)); partial(oil) { setType(LogType.OIL); setAmount("150") }
        assertEquals("kein Tanken", SaveOutcome.Done, oil.vm.outcome.value)

        // Eine volle Tankung im Anschluss an die Historie (7,7 l auf 100 km): keine Verbrauchswarnung, die Rueckfrage "auf voll setzen" bleibt die eigene.
        val full = fixture(entries = steadyPast(3))
        collect(full.vm); runCurrent()
        full.vm.fillFuel(odometer = "1400", liters = "7,7", cost = ""); runCurrent()
        full.vm.save(); runCurrent()
        assertEquals("eine volle Tankung behaelt ihre eigene Rueckfrage", SaveOutcome.AskFuelGaugeReset, full.vm.outcome.value)

        val edit = fixture(id = 5, entries = steadyPast(3) + entry(5, km = 40_362, date = today).copy(fullTank = false))
        collect(edit.vm); runCurrent(); edit.vm.setNote("x"); runCurrent(); edit.vm.save(); runCurrent()
        assertEquals("beim Bearbeiten nie", SaveOutcome.Done, edit.vm.outcome.value)
    }

    @Test
    fun `a part fill so small that it credits less than one kilometer asks nothing`() = runTest {
        val f = fixture(entries = steadyPast(3))
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362", liters = "0,01", cost = ""); f.vm.setFullTank(false); runCurrent()
        f.vm.save(); runCurrent()

        assertEquals(SaveOutcome.Done, f.vm.outcome.value)
    }

    @Test
    fun `the credit uses the consumption of the newest three intervals only`() = runTest {
        // Das aelteste Intervall (20 l/100 km) liegt ausserhalb des Fensters von drei: die letzten drei sind 7,7 l/100 km.
        // Mit allen vier Intervallen waeren es (20 + 3 x 7,7) l auf 400 km = 10,775 l/100 km und die Gutschrift 58 km statt 81.
        val entries = listOf(
            pastFill(0, 1_000, 15_000), pastFill(1, 1_100, 20_000),
            pastFill(2, 1_200, 7_700), pastFill(3, 1_300, 7_700), pastFill(4, 1_400, 7_700),
        )
        val f = fixture(entries = entries)
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362", liters = "6,2", cost = ""); f.vm.setFullTank(false); runCurrent()
        f.vm.save(); runCurrent()

        // 6,2 l bei 7,7 l/100 km = 80,5 km -> 81
        assertEquals(SaveOutcome.AskFuelGaugeCredit(amountMl = 6_200, km = 81), f.vm.outcome.value)
    }

    @Test
    fun `the saved part fill is not counted into the consumption it is converted with`() = runTest {
        // Die Teiltankung selbst schliesst kein Intervall: der Verbrauch kommt aus den bisherigen, nicht aus ihren eigenen 30 l.
        val f = fixture(entries = steadyPast(3))
        collect(f.vm); runCurrent()
        f.vm.fillFuel(odometer = "40362", liters = "30", cost = ""); f.vm.setFullTank(false); runCurrent()
        f.vm.save(); runCurrent()

        // 30 l bei 7,7 l/100 km = 389,6 km -> 390
        assertEquals(SaveOutcome.AskFuelGaugeCredit(amountMl = 30_000, km = 390), f.vm.outcome.value)
    }
}
