package de.adv990de.obd2.fuel

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Der Zaehler der Tankanzeige: Zustand, Drosselung des Speicherns, Zuruecksetzen und das Verhalten, solange der gespeicherte Wert noch
 * nicht gelesen ist. [AppPreferences] ist die strenge Attrappe (`fakeAppPreferences`); jeder Schreibvorgang landet in [written].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelRepositoryTest {

    private val written = mutableListOf<Float>()

    private fun prefs(range: Int = 200, km: Float = 0f): AppPreferences =
        fakeAppPreferences(fuelRangeFullKm = range, fuelKmSinceFill = km).also(::stubWrites)

    private fun stubWrites(prefs: AppPreferences) {
        coEvery { prefs.setFuelKmSinceFill(any()) } answers { written += firstArg<Float>() }
    }

    private fun TestScope.repo(prefs: AppPreferences) = FuelRepository(prefs, backgroundScope).also { runCurrent() }

    @Test
    fun `with a range and a stored counter the state is available at once`() = runTest {
        val repo = repo(prefs(range = 320, km = 100f))

        assertEquals(FuelGaugeState(320, 100f), repo.state.value)
    }

    @Test
    fun `without a range there is no gauge`() = runTest {
        assertNull(repo(prefs(range = 0)).state.value)
    }

    @Test
    fun `added distance moves the gauge`() = runTest {
        val repo = repo(prefs(range = 200, km = 10f))

        repo.addDistance(5f)
        runCurrent()

        assertEquals(15f, repo.state.value!!.kmSinceFill, 1e-4f)
    }

    @Test
    fun `non-positive and non-finite distances are ignored`() = runTest {
        val repo = repo(prefs(range = 200, km = 10f))

        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) repo.addDistance(bad)
        repo.flush()
        runCurrent()

        assertEquals(10f, repo.state.value!!.kmSinceFill, 0f)
        assertTrue("nichts zu schreiben: $written", written.isEmpty())
    }

    @Test
    fun `a new range applies at once - the counter stays`() = runTest {
        val range = MutableStateFlow(0)
        val prefs = fakeAppPreferences(fuelKmSinceFill = 50f).also(::stubWrites)
        every { prefs.fuelRangeFullKm } returns range
        val repo = repo(prefs)
        assertNull(repo.state.value)

        range.value = 400
        runCurrent()
        assertEquals(FuelGaugeState(400, 50f), repo.state.value)

        range.value = 0
        runCurrent()
        assertNull("Reichweite geloescht = Leiste aus", repo.state.value)
    }

    @Test
    fun `small steps are not written, the step that reaches 0,2 km is`() = runTest {
        val repo = repo(prefs(km = 10f))

        repo.addDistance(0.15f)
        runCurrent()
        assertTrue("noch nichts geschrieben: $written", written.isEmpty())

        repo.addDistance(0.1f)
        runCurrent()
        assertEquals(listOf(10.25f), written)
    }

    @Test
    fun `after a write the next step counts from the written value`() = runTest {
        val repo = repo(prefs(km = 0f))

        repo.addDistance(0.25f)
        runCurrent()
        repo.addDistance(0.15f)
        runCurrent()
        assertEquals(listOf(0.25f), written)

        repo.addDistance(0.1f)
        runCurrent()
        assertEquals(listOf(0.25f, 0.5f), written)
    }

    @Test
    fun `flush writes what has not been written yet, and only once`() = runTest {
        val repo = repo(prefs(km = 10f))
        repo.addDistance(0.05f)

        repo.flush()
        runCurrent()
        repo.flush()
        runCurrent()

        assertEquals(listOf(10.05f), written)
    }

    @Test
    fun `flush without a change writes nothing`() = runTest {
        val repo = repo(prefs(km = 10f))

        repo.flush()
        runCurrent()

        assertTrue(written.isEmpty())
    }

    @Test
    fun `a burst of steps writes in order and ends with the latest value`() = runTest {
        val repo = repo(prefs(km = 0f))

        repeat(5) { repo.addDistance(0.3f) }
        runCurrent()

        assertEquals("aufsteigend, nie ein aelterer Wert nach einem neueren: $written", written.sorted(), written)
        assertEquals(1.5f, written.last(), 1e-4f)
    }

    @Test
    fun `reset puts the gauge back to full and writes 0 at once`() = runTest {
        val repo = repo(prefs(range = 200, km = 150f))

        repo.reset()
        runCurrent()

        assertEquals(FuelGaugeState(200, 0f), repo.state.value)
        assertEquals(listOf(0f), written)
    }

    @Test
    fun `after a reset the next step counts from zero`() = runTest {
        val repo = repo(prefs(km = 150f))
        repo.reset()
        runCurrent()

        repo.addDistance(0.25f)
        runCurrent()

        assertEquals(listOf(0f, 0.25f), written)
    }

    @Test
    fun `a write failure does not stop later writes`() = runTest {
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200, fuelKmSinceFill = 0f)
        var calls = 0
        coEvery { prefs.setFuelKmSinceFill(any()) } answers {
            calls++
            if (calls == 1) throw IOException("disk full") else written += firstArg<Float>()
        }
        val repo = repo(prefs)

        repo.addDistance(0.25f)
        runCurrent()
        repo.addDistance(0.25f)
        runCurrent()

        assertEquals("der zweite Schreibversuch kommt durch", listOf(0.5f), written)
    }

    // ── Der gespeicherte Zaehler ist noch nicht gelesen (haengende Platte) ──────────────────────────────

    @Test
    fun `while the stored counter is not read there is no gauge and nothing is written`() = runTest {
        val stored = MutableSharedFlow<Float>() // antwortet erst, wenn der Test es sagt
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)

        repo.addDistance(5f)
        repo.flush()
        runCurrent()

        assertNull("nicht geladen = keine Anzeige", repo.state.value)
        assertTrue("nichts schreiben, solange der alte Wert unbekannt ist: $written", written.isEmpty())
    }

    @Test
    fun `distance counted before the stored counter arrives is added to it`() = runTest {
        val stored = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)
        repo.addDistance(5f)
        runCurrent()

        stored.emit(100f)
        runCurrent()

        assertEquals(FuelGaugeState(200, 105f), repo.state.value)
    }

    @Test
    fun `a reset before the stored counter arrives wins over the late value`() = runTest {
        val stored = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(fuelRangeFullKm = 200).also(::stubWrites)
        every { prefs.fuelKmSinceFill } returns stored
        val repo = repo(prefs)
        runCurrent()

        repo.reset()
        runCurrent()
        stored.emit(100f)
        runCurrent()

        assertNotNull(repo.state.value)
        assertEquals(0f, repo.state.value!!.kmSinceFill, 0f)
    }
}
