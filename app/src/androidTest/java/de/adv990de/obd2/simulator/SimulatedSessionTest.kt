package de.adv990de.obd2.simulator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Faehrt eine vollstaendige Sitzung gegen den eingebauten ECU-Simulator: verbinden,
 * PID-Discovery, Messwert-Strom, trennen.
 *
 * Warum das noetig ist: Die uebrigen Tests pruefen einzelne Bausteine — Parser, Zusammenfuehrung,
 * ViewModel-Logik gegen Mocks. Keiner davon prueft, ob die Bausteine auch *verdrahtet* sind. Genau
 * daran ist die GPS-Geschwindigkeit in 1.10.0/1.10.1 gescheitert: Die Logik war korrekt und
 * getestet, der Flow, der sie speisen sollte, lief nie an. Dieser Test schliesst die Luecke fuer
 * den Hauptpfad der App.
 *
 * Bewusst *ohne* Hilt und ohne Mock: [ObdRepository] laesst sich mit
 * `ConnectionFactory(context)` direkt bauen, und der Simulator-Zweig der Factory braucht weder
 * Bluetooth-Hardware noch eine Berechtigung. Der Kommentar in `DashboardScreenTest`, die
 * Konstruktor-Abhaengigkeiten seien im Instrumentationstest nicht verfuegbar, gilt nur fuer die
 * beiden echten Bluetooth-Zweige.
 */
@RunWith(AndroidJUnit4::class)
class SimulatedSessionTest {

    private val simulator = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")

    private lateinit var repository: ObdRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = ObdRepository(ConnectionFactory(context), PidParser(), DtcParser())
    }

    @After
    fun teardown() = runBlocking {
        runCatching { repository.disconnect() }
        Unit
    }

    // --- Test 1 ---

    @Test
    fun connecting_to_the_simulator_reports_a_live_connection() = runBlocking {
        withTimeout(TIMEOUT_MS) { repository.connect(simulator) }

        assertTrue("isConnectedFlow bleibt false nach connect()", repository.isConnectedFlow.value)
        assertEquals(simulator, repository.lastConnectedDevice.value)
    }

    // --- Test 2 ---

    @Test
    fun discovery_reports_exactly_the_pids_the_simulator_answers() = runBlocking {
        withTimeout(TIMEOUT_MS) { repository.connect(simulator) }

        val supported = repository.supportedPidIds.value
        assertNotNull("Die Discovery hat keine PID-Menge geliefert", supported)

        // Aus den drei Bitmaps des Dispatchers (0100/0120/0140). 0x20 und 0x40 sind
        // Fortsetzungs-Flags und stehen deshalb nicht in PidRegistry.
        val expected = setOf(
            PidRegistry.ENGINE_LOAD.id,          // 0104
            PidRegistry.COOLANT_TEMP.id,         // 0105
            PidRegistry.INTAKE_MANIFOLD_PRESSURE.id, // 010B
            PidRegistry.RPM.id,                  // 010C
            PidRegistry.SPEED.id,                // 010D
            PidRegistry.TIMING_ADVANCE.id,       // 010E
            PidRegistry.MAF.id,                  // 0110
            PidRegistry.THROTTLE.id,             // 0111
            PidRegistry.FUEL_TANK_LEVEL.id,      // 012F
            PidRegistry.CONTROL_MODULE_VOLTAGE.id, // 0142
            PidRegistry.AMBIENT_AIR_TEMP.id,     // 0146
        )
        assertEquals(expected, supported)
    }

    // --- Test 3 ---

    @Test
    fun live_pids_stream_plausible_values() = runBlocking {
        withTimeout(TIMEOUT_MS) { repository.connect(simulator) }

        val pids = listOf(PidRegistry.RPM, PidRegistry.SPEED, PidRegistry.COOLANT_TEMP)
        val values = withTimeout(TIMEOUT_MS) { repository.livePids(pids).first() }

        assertEquals(pids.map { it.id }.toSet(), values.map { it.pid.id }.toSet())
        values.forEach { sv ->
            assertTrue("${sv.pid.id} ist nicht endlich: ${sv.value}", sv.value.isFinite())
            assertTrue(
                "${sv.pid.id} liegt ausserhalb seines Wertebereichs: ${sv.value}",
                sv.value >= sv.pid.minValue && sv.value <= sv.pid.maxValue
            )
        }
        // Die Drehzahl folgt einer Dreieckswelle zwischen 900 und 8500 — nie 0.
        val rpm = values.first { it.pid.id == PidRegistry.RPM.id }
        assertTrue("Drehzahl steht bei 0, der Simulator laeuft nicht", rpm.value > 0f)
    }

    // --- Test 4 ---

    @Test
    fun a_second_poll_cycle_delivers_fresh_values() = runBlocking {
        withTimeout(TIMEOUT_MS) { repository.connect(simulator) }

        // Zwei aufeinanderfolgende Zyklen: Der Strom darf nicht nach dem ersten Wert stehen
        // bleiben — genau das wuerde ein aufgehaengter Dispatcher-Thread verursachen.
        val cycles = withTimeout(TIMEOUT_MS) {
            repository.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 50L)
                .take(2)
                .toList()
        }
        assertEquals(2, cycles.size)
        cycles.forEach { values ->
            assertTrue("Ein Zyklus lieferte keine Drehzahl", values.any { it.pid.id == PidRegistry.RPM.id })
        }
    }

    // --- Test 5 ---

    @Test
    fun disconnect_clears_connection_and_discovery_state() = runBlocking {
        withTimeout(TIMEOUT_MS) { repository.connect(simulator) }
        repository.disconnect()

        assertEquals(false, repository.isConnectedFlow.value)
        assertEquals(null, repository.supportedPidIds.value)
        // Das zuletzt verbundene Geraet bleibt absichtlich stehen — der Reconnect-Pfad braucht es.
        assertEquals(simulator, repository.lastConnectedDevice.value)
    }

    private companion object {
        /** Der Simulator antwortet ueber Pipes im selben Prozess; 15 s sind reichlich. */
        const val TIMEOUT_MS = 15_000L
    }
}
