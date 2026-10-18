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
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Das ViewModel rechnet die Gelaende-Kachel nur, wenn sie jemand sieht: ein Slot `TERRAIN`, der GPS-Schalter an und eine stehende Verbindung. Sonst laufen weder
 * GPS noch Barometer. Die Rechnung selbst steht in `de.adv990de.obd2.terrain` (eigene Tests); hier geht es um Bedingung, Zuruecksetzen und darum, dass die Kachel
 * nichts anderes anruehrt (Abfrage, Aufzeichnung, Auswahl).
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

    private fun setUp(
        slots: List<String> = listOf(DerivedPids.TERRAIN_ID, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id),
        gps: Boolean = true,
        connected: Boolean = true,
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

    private fun fix(t: Long = 1_000, altitude: Float = 100f) =
        GpsFix(t, speedKmh = 50f, altitudeM = altitude, verticalAccuracyM = 5f, bearingDeg = 90f, bearingAccuracyDeg = 10f)

    @Test
    fun `without a terrain slot nobody collects the inputs`() = runTest {
        val s = setUp(slots = listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id))
        advanceUntilIdle()

        assertEquals(0, s.terrain.started)
        assertNull(s.vm.uiState.value.terrain)
    }

    @Test
    fun `with the slot, the GPS switch and a connection the inputs are collected and shown`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        assertEquals(1, s.terrain.collectors)

        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        val terrain = s.vm.uiState.value.terrain!!
        assertEquals(100f, terrain.altitudeM!!, 0.01f)
        assertEquals(90f, terrain.headingDeg!!, 0.001f)
    }

    @Test
    fun `without the GPS switch nothing is collected`() = runTest {
        val s = setUp(gps = false)
        advanceUntilIdle()

        assertEquals(0, s.terrain.started)
    }

    @Test
    fun `without a connection nothing is collected`() = runTest {
        val s = setUp(connected = false)
        advanceUntilIdle()

        assertEquals(0, s.terrain.started)
    }

    @Test
    fun `losing the connection ends the collection and clears the tile state`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()
        assertNotNull(s.vm.uiState.value.terrain)

        s.connected.value = false
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
        assertNull(s.vm.uiState.value.terrain)
    }

    @Test
    fun `switching GPS off ends the collection too`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.gps.value = false
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
        assertNull(s.vm.uiState.value.terrain)
    }

    @Test
    fun `moving the slot away from the terrain tile ends the collection`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.slots.value = listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id)
        advanceUntilIdle()

        assertEquals(0, s.terrain.collectors)
        assertNull(s.vm.uiState.value.terrain)
    }

    @Test
    fun `a new activation starts from a fresh calibration`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(altitude = 100f), 1013.25f))
        advanceUntilIdle()
        s.connected.value = false
        advanceUntilIdle()
        s.connected.value = true
        advanceUntilIdle()
        assertEquals(2, s.terrain.started)

        // Bei gleichem Druck und anderer GPS-Hoehe: mit dem alten Versatz bliebe die Hoehe bei 100 m.
        s.clock[0] = 60_000L
        s.terrain.source.emit(TerrainInput(fix(t = 60_000, altitude = 500f), 1013.25f))
        advanceUntilIdle()

        assertEquals(500f, s.vm.uiState.value.terrain!!.altitudeM!!, 0.01f)
    }

    @Test
    fun `the terrain state survives ordinary OBD cycles`() = runTest {
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.events.emit(SessionEvent.Values(listOf(SensorValue(PidRegistry.RPM, 3000f)), gpsSpeedKmh = null))
        advanceUntilIdle()

        assertNotNull(s.vm.uiState.value.terrain)
    }

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
        val s = setUp()
        advanceUntilIdle()
        s.terrain.source.emit(TerrainInput(fix(), 1013.25f))
        advanceUntilIdle()

        s.events.emit(SessionEvent.Values(listOf(SensorValue(PidRegistry.RPM, 3000f, timestamp = 5_000)), gpsSpeedKmh = null))
        advanceUntilIdle()

        val ids = s.recorded.flatten().map { it.pid.id }
        assertEquals(listOf(DerivedPids.GEAR_ID, DerivedPids.OIL_BAR_ID), ids)
    }
}
