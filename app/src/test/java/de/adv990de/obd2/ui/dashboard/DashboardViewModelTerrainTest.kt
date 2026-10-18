package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fakeObdRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.terrain.GpsFix
import de.adv990de.obd2.terrain.TerrainInput
import de.adv990de.obd2.terrain.TerrainInputs
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Das ViewModel rechnet die Gelaende-Kachel nur, wenn sie jemand sieht: ein Slot `TERRAIN`, der GPS-Schalter an und ein Sammler der Oberflaeche (`vm.terrain`, ein
 * `WhileSubscribed`-Fluss). **Die Verbindung zum Fahrzeug spielt keine Rolle** — die Werte kommen vom Telefon. Sonst laufen weder GPS noch Barometer. Die Rechnung selbst
 * steht in `de.adv990de.obd2.terrain` (eigene Tests); hier geht es um Bedingung, Zuruecksetzen und darum, dass die Kachel nichts anderes anruehrt (Verbindungszustand,
 * Abfrage, Aufzeichnung, Auswahl).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTerrainTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    /** Zaehlt, wie oft der Fluss gestartet wurde und wie viele Sammler gerade laufen. */
    private class FakeTerrainInputs : TerrainInputs {
        val source = MutableSharedFlow<TerrainInput>(extraBufferCapacity = 16)
        var started = 0
        var collectors = 0
        override val inputs: Flow<TerrainInput> = source
            .onStart { started++; collectors++ }
            .onCompletion { collectors-- }
    }

    private class Setup(
        val vm: DashboardViewModel,
        val terrain: FakeTerrainInputs,
        val connected: MutableStateFlow<Boolean>,
        val gps: MutableStateFlow<Boolean>,
        val slots: MutableStateFlow<List<String>>,
        val events: MutableSharedFlow<SessionEvent>,
        val recorded: MutableList<List<SensorValue>>,
        /** Die monotone Uhr des ViewModels (in JVM-Tests liefert `SystemClock` immer 0): ein Fix gilt nur, wenn seine Zeit nicht in der Zukunft liegt. */
        val clock: LongArray,
    )

    private val withTerrain = listOf(DerivedPids.TERRAIN_ID, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id)
    private val withoutTerrain = listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id)

    private fun setUp(
        slots: List<String> = withTerrain,
        gps: Boolean = true,
        connected: Boolean = false,
        supported: Set<String>? = null,
    ): Setup {
        val terrain = FakeTerrainInputs()
        val connectedFlow = MutableStateFlow(connected)
        val gpsFlow = MutableStateFlow(gps)
        val slotsFlow = MutableStateFlow(slots)
        val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
        val recorded = mutableListOf<List<SensorValue>>()
        val pipeline = mockk<ReadingsPipeline> {
            every { events(any()) } returns events
            coEvery { recordDerived(capture(recorded)) } just Runs
        }
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns connectedFlow
            every { supportedPidIds } returns MutableStateFlow(supported)
        }
        val prefs: AppPreferences = fakeAppPreferences().also {
            every { it.slotPids } returns slotsFlow
            every { it.useGpsSpeed } returns gpsFlow
        }
        val clock = longArrayOf(1_000L)
        val vm = DashboardViewModel(
            obdRepo, prefs, mockk(relaxed = true), pipeline, SavedStateHandle(),
            terrainInputs = terrain,
        ).also { it.nowMillis = { clock[0] } }
        return Setup(vm, terrain, connectedFlow, gpsFlow, slotsFlow, events, recorded, clock)
    }

    /**
     * Die Oberflaeche sammelt `vm.terrain` (ein `WhileSubscribed`-Fluss laeuft nur mit Sammler); der Job laesst ein Test das Verlassen des Dashboards nachstellen.
     * `runCurrent()`, nicht `advanceUntilIdle()`: Arbeit im `backgroundScope` laeuft damit — `advanceUntilIdle()` hoert auf, sobald nur noch Hintergrundaufgaben uebrig sind.
     */
    private fun TestScope.watchTerrain(s: Setup) = backgroundScope.launch { s.vm.terrain.collect { } }.also { runCurrent() }

    private fun fix(t: Long = 1_000, altitude: Float = 100f) =
        GpsFix(t, speedKmh = 50f, altitudeM = altitude, verticalAccuracyM = 5f, bearingDeg = 90f, bearingAccuracyDeg = 10f)

    // --- wann gerechnet wird ---

    @Test
    fun `without a terrain slot nobody collects the inputs`() = runTest {
        val s = setUp(slots = withoutTerrain)
        watchTerrain(s)
        advanceUntilIdle()

        assertEquals(0, s.terrain.started)
        assertNull(s.vm.terrain.value)
    }

    @Test
    fun `with the slot and the GPS switch the inputs are collected and shown - without any connection`() = runTest {
        val s = setUp(connected = false)
        watchTerrain(s)
        advanceUntilIdle()
        assertEquals(1, s.terrain.collectors)

        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        val terrain = s.vm.terrain.value!!
        assertEquals(100f, terrain.altitudeM!!, 0.01f)
        assertEquals(90f, terrain.headingDeg!!, 0.001f)
    }

    @Test
    fun `without the GPS switch nothing is collected`() = runTest {
        val s = setUp(gps = false)
        watchTerrain(s)
        advanceUntilIdle()

        assertEquals(0, s.terrain.started)
        assertNull(s.vm.terrain.value)
    }

    @Test
    fun `the tile shows nothing before the first input - the screen then names what is missing`() = runTest {
        val s = setUp()
        watchTerrain(s)
        advanceUntilIdle()

        assertNull(s.vm.terrain.value)
    }

    @Test
    fun `switching GPS off ends the collection and clears the tile state`() = runTest {
        val s = setUp()
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()
        assertNotNull(s.vm.terrain.value)

        s.gps.value = false
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
        assertNull(s.vm.terrain.value)
    }

    @Test
    fun `moving the slot away from the terrain tile ends the collection`() = runTest {
        val s = setUp()
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.slots.value = withoutTerrain
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
        assertNull(s.vm.terrain.value)
    }

    @Test
    fun `a new activation starts from a fresh calibration`() = runTest {
        val s = setUp()
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(altitude = 100f), 1013.25f))
        advanceUntilIdle()
        s.gps.value = false
        advanceUntilIdle()
        s.gps.value = true
        advanceUntilIdle()
        assertEquals(2, s.terrain.started)

        // Bei gleichem Druck und anderer GPS-Hoehe: mit dem alten Versatz bliebe die Hoehe bei 100 m.
        s.clock[0] = 60_000L
        s.terrain.source.emit(TerrainInput(fix(t = 60_000, altitude = 500f), 1013.25f))
        advanceUntilIdle()

        assertEquals(500f, s.vm.terrain.value!!.altitudeM!!, 0.01f)
    }

    // --- Verbindung: egal ---

    @Test
    fun `connecting and losing the connection neither stop nor clear the tile`() = runTest {
        val s = setUp(connected = false)
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()
        assertNotNull(s.vm.terrain.value)

        s.connected.value = true
        advanceUntilIdle()
        s.connected.value = false
        advanceUntilIdle()

        assertEquals("ein Sammler, nie neu gestartet", 1, s.terrain.started)
        assertEquals(1, s.terrain.collectors)
        assertNotNull(s.vm.terrain.value)
    }

    @Test
    fun `the connection state machine does not wipe the tile - idle, lost connection and a stale vehicle leave it alone`() = runTest {
        val s = setUp(connected = true)
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()
        val before = s.vm.terrain.value
        assertNotNull(before)

        s.events.emit(SessionEvent.Idle); advanceUntilIdle()
        assertEquals(before, s.vm.terrain.value)
        s.events.emit(SessionEvent.ConnectionLost); advanceUntilIdle()
        assertEquals(before, s.vm.terrain.value)
        s.events.emit(SessionEvent.Stale); advanceUntilIdle()
        assertEquals(before, s.vm.terrain.value)
    }

    @Test
    fun `ordinary OBD cycles do not disturb the tile`() = runTest {
        val s = setUp(connected = true)
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.events.emit(SessionEvent.Values(listOf(SensorValue(PidRegistry.RPM, 3000f)), gpsSpeedKmh = null))
        advanceUntilIdle()

        assertNotNull(s.vm.terrain.value)
    }

    // --- nur, solange das Dashboard sichtbar ist ---

    @Test
    fun `when the dashboard is left the collection stops after five seconds`() = runTest {
        val s = setUp()
        val watching = watchTerrain(s)
        advanceUntilIdle()
        assertEquals(1, s.terrain.collectors)

        watching.cancel()
        advanceTimeBy(4_900)
        assertEquals("noch nicht", 1, s.terrain.collectors)
        advanceTimeBy(200)
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
    }

    @Test
    fun `coming back to the dashboard starts from scratch`() = runTest {
        val s = setUp()
        val first = watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(altitude = 100f), 1013.25f))
        advanceUntilIdle()
        assertNotNull(s.vm.terrain.value)
        first.cancel()
        advanceTimeBy(6_000); advanceUntilIdle()

        watchTerrain(s)
        advanceUntilIdle()

        assertEquals(2, s.terrain.started)
        assertNull("kein alter Stand beim Zurueckkommen", s.vm.terrain.value)
        s.clock[0] = 20_000L
        s.terrain.source.emit(TerrainInput(fix(t = 20_000, altitude = 700f), 1013.25f))
        advanceUntilIdle()
        assertEquals(700f, s.vm.terrain.value!!.altitudeM!!, 0.01f)
    }

    // --- die Kachel beruehrt nichts anderes ---

    @Test
    fun `the picker offers the terrain entry even when the vehicle lists its PIDs`() = runTest {
        val s = setUp(supported = setOf(PidRegistry.RPM.id))
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { s.vm.availablePids.collect { seen += it.map { pid -> pid.id } } }
        advanceUntilIdle()

        assertTrue(seen.isNotEmpty())
        assertTrue("jeder Stand der Auswahl nennt TERRAIN: $seen", seen.all { DerivedPids.TERRAIN_ID in it })
    }

    @Test
    fun `the recording never gets a terrain line`() = runTest {
        val s = setUp(connected = true)
        watchTerrain(s)
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.events.emit(SessionEvent.Values(listOf(SensorValue(PidRegistry.RPM, 3000f, timestamp = 5_000)), gpsSpeedKmh = null))
        advanceUntilIdle()

        val ids = s.recorded.flatten().map { it.pid.id }
        assertEquals(listOf(DerivedPids.GEAR_ID, DerivedPids.OIL_BAR_ID), ids)
    }
}
