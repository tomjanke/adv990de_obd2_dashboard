package de.adv990de.obd2.ui.history

import android.net.Uri
import de.adv990de.obd2.R
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.TripImporter
import de.adv990de.obd2.tripimport.TripFileException
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.ui.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Der Fahrten-Import im [HistoryViewModel]: Datei lesen → Vorschau → bestaetigen → schreiben, mit allen Wegen des Scheiterns und Abbrechens. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTripImportTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private val plan = TripImportPlan(emptyList(), duplicateTrips = 2, expiredTrips = 1, rejected = emptyList(), rejectedCount = 0)
    private val source = mockk<Uri>()

    private class Fixture(val importer: TripImporter, val vm: HistoryViewModel)

    private fun fixture(): Fixture {
        val repo = mockk<SessionRepository>(relaxed = true) {
            every { sessionIds } returns MutableStateFlow(emptyList())
            every { activeSessionId } returns MutableStateFlow(null)
        }
        val importer = mockk<TripImporter>()
        coEvery { importer.read(any(), any()) } returns plan
        coEvery { importer.apply(any()) } returns 3
        return Fixture(importer, HistoryViewModel(repo, importer, CoroutineScope(testDispatcher + Job())))
    }

    @Test
    fun `nothing is shown before an import was started`() = runTest {
        assertEquals(TripImportState.Idle, fixture().vm.tripImportState.value)
    }

    // ── Datei lesen ───────────────────────────────────────────────────────────

    @Test
    fun `picking a file reads it and shows the preview - nothing is written`() = runTest {
        val f = fixture()
        val before = System.currentTimeMillis()

        f.vm.pickTripImport(source)
        assertEquals("working while it reads", TripImportState.Working, f.vm.tripImportState.value)
        runCurrent()

        assertEquals(TripImportState.Preview(plan), f.vm.tripImportState.value)
        val now = slot<Long>()
        coVerify(exactly = 1) { f.importer.read(source, capture(now)) }
        assertTrue("the current time is passed to the plan", now.captured in before..System.currentTimeMillis())
        coVerify(exactly = 0) { f.importer.apply(any()) }
    }

    @Test
    fun `a second pick while it is working does nothing`() = runTest {
        val f = fixture()
        val release = CompletableDeferred<TripImportPlan>()
        coEvery { f.importer.read(any(), any()) } coAnswers { release.await() }

        f.vm.pickTripImport(source); runCurrent()
        f.vm.pickTripImport(mockk()); runCurrent()
        release.complete(plan); runCurrent()

        coVerify(exactly = 1) { f.importer.read(any(), any()) }
        assertEquals(TripImportState.Preview(plan), f.vm.tripImportState.value)
    }

    @Test
    fun `a new pick after the preview replaces it`() = runTest {
        val f = fixture()
        f.vm.pickTripImport(source); runCurrent()
        val second = plan.copy(duplicateTrips = 9)
        coEvery { f.importer.read(any(), any()) } returns second

        f.vm.pickTripImport(source); runCurrent()

        assertEquals(TripImportState.Preview(second), f.vm.tripImportState.value)
    }

    private fun failureOf(error: Throwable): TripImportState {
        val f = fixture()
        coEvery { f.importer.read(any(), any()) } throws error
        f.vm.pickTripImport(source)
        testDispatcher.scheduler.runCurrent()
        return f.vm.tripImportState.value
    }

    private fun failed(res: Int) = TripImportState.Failed(UiText.StringResource(res))

    @Test
    fun `every reason a file can fail has its own text`() {
        assertEquals(failed(R.string.trip_import_error_format), failureOf(TripFileException(TripFileException.Kind.NOT_AN_EXPORT, "x")))
        assertEquals(failed(R.string.trip_import_error_too_many_rows), failureOf(TripFileException(TripFileException.Kind.TOO_MANY_ROWS, "x")))
        assertEquals(failed(R.string.trip_import_error_split), failureOf(TripFileException(TripFileException.Kind.SPLIT_TRIP, "x")))
        assertEquals("an unclosed quote or a field that is too long", failed(R.string.trip_import_error_format), failureOf(IllegalArgumentException("x")))
        assertEquals(failed(R.string.trip_import_error_too_large), failureOf(FileTooLargeException("x")))
        assertEquals(failed(R.string.trip_import_error_unreadable), failureOf(IOException("x")))
        assertEquals(failed(R.string.trip_import_error_unreadable), failureOf(SecurityException("x")))
    }

    @Test
    fun `an unknown failure shows its message, or the class name without one - never an empty text`() {
        assertEquals(TripImportState.Failed(UiText.DynamicString("boom")), failureOf(IllegalStateException("boom")))
        assertEquals(TripImportState.Failed(UiText.DynamicString("IllegalStateException")), failureOf(IllegalStateException()))
    }

    @Test
    fun `a cancelled read goes back to nothing and is not shown as an error`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(any(), any()) } throws CancellationException("gone")

        f.vm.pickTripImport(source); runCurrent()

        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)
    }

    // ── Bestaetigen ───────────────────────────────────────────────────────────

    @Test
    fun `confirming writes the plan of the preview once and reports the number of trips`() = runTest {
        val f = fixture()
        f.vm.pickTripImport(source); runCurrent()

        f.vm.confirmTripImport()
        assertEquals("working while it writes", TripImportState.Working, f.vm.tripImportState.value)
        runCurrent()

        assertEquals(TripImportState.Done(3), f.vm.tripImportState.value)
        coVerify(exactly = 1) { f.importer.apply(plan) }
    }

    @Test
    fun `confirming without a preview writes nothing`() = runTest {
        val f = fixture()

        f.vm.confirmTripImport(); runCurrent()

        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)
        coVerify(exactly = 0) { f.importer.apply(any()) }
    }

    @Test
    fun `confirming twice writes once - also while it is still writing`() = runTest {
        val f = fixture()
        val release = CompletableDeferred<Int>()
        coEvery { f.importer.apply(any()) } coAnswers { release.await() }
        f.vm.pickTripImport(source); runCurrent()

        f.vm.confirmTripImport(); runCurrent()
        f.vm.confirmTripImport(); runCurrent()
        release.complete(3); runCurrent()
        f.vm.confirmTripImport(); runCurrent()

        coVerify(exactly = 1) { f.importer.apply(any()) }
        assertEquals(TripImportState.Done(3), f.vm.tripImportState.value)
    }

    @Test
    fun `a failure while writing says nothing was imported`() = runTest {
        val f = fixture()
        coEvery { f.importer.apply(any()) } throws IOException("disk full")
        f.vm.pickTripImport(source); runCurrent()

        f.vm.confirmTripImport(); runCurrent()

        assertEquals(failed(R.string.trip_import_error_write), f.vm.tripImportState.value)
    }

    @Test
    fun `a cancelled write goes back to nothing`() = runTest {
        val f = fixture()
        coEvery { f.importer.apply(any()) } throws CancellationException("gone")
        f.vm.pickTripImport(source); runCurrent()

        f.vm.confirmTripImport(); runCurrent()

        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)
    }

    // ── Schliessen ────────────────────────────────────────────────────────────

    @Test
    fun `dismissing closes the preview, the result and the error - the plan is let go`() = runTest {
        val f = fixture()
        f.vm.pickTripImport(source); runCurrent()
        f.vm.dismissTripImport()
        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)

        f.vm.pickTripImport(source); runCurrent(); f.vm.confirmTripImport(); runCurrent()
        assertEquals(TripImportState.Done(3), f.vm.tripImportState.value)
        f.vm.dismissTripImport()
        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)

        coEvery { f.importer.read(any(), any()) } throws IOException("x")
        f.vm.pickTripImport(source); runCurrent()
        f.vm.dismissTripImport()
        assertEquals(TripImportState.Idle, f.vm.tripImportState.value)
    }

    @Test
    fun `dismissing while it works changes nothing`() = runTest {
        val f = fixture()
        val release = CompletableDeferred<TripImportPlan>()
        coEvery { f.importer.read(any(), any()) } coAnswers { release.await() }
        f.vm.pickTripImport(source); runCurrent()

        f.vm.dismissTripImport()
        assertEquals(TripImportState.Working, f.vm.tripImportState.value)
        release.complete(plan); runCurrent()

        assertEquals(TripImportState.Preview(plan), f.vm.tripImportState.value)
    }

    @Test
    fun `a new import can start after a failure`() = runTest {
        val f = fixture()
        coEvery { f.importer.read(any(), any()) } throws IOException("x")
        f.vm.pickTripImport(source); runCurrent()
        coEvery { f.importer.read(any(), any()) } returns plan

        f.vm.pickTripImport(source); runCurrent()

        assertEquals(TripImportState.Preview(plan), f.vm.tripImportState.value)
    }
}
