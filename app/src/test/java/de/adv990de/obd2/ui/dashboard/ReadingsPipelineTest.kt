package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer den herausgeloesten Messwert-Pfad.
 *
 * Die Gegenprobe zu [ConnectionCoordinatorTest]: Hier gibt es weder BluetoothAdapter noch Context
 * noch Foreground Service. Was diese Klasse leistet — PID-Auswahl, Aufzeichnung, Zusammenfuehrung
 * der Geschwindigkeitsquellen, Fahrterkennung — laesst sich ohne eine Zeile Verbindungslogik
 * pruefen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingsPipelineTest {

    private val rpm = SensorValue(pid = PidRegistry.RPM, value = 1500f, timestamp = 1000L)

    private fun makeRepo(
        connected: Boolean,
        readings: List<SensorValue> = listOf(rpm),
        supported: Set<String>? = null,
    ): ObdRepository = mockk {
        every { isConnectedFlow } returns MutableStateFlow(connected)
        every { supportedPidIds } returns MutableStateFlow(supported)
        every { livePids(any()) } returns flowOf(readings)
        every { updateLatestReadings(any()) } just Runs
        every { setMoving(any()) } just Runs
        coEvery { readVin() } returns "VIN"
        coEvery { readDtcs() } returns emptyList()
    }

    private fun makeSessionRepo(): SessionRepository = mockk {
        coEvery { startSession(any()) } returns 7L
        coEvery { writeReadings(any(), any()) } just Runs
        coEvery { endSession(any()) } just Runs
        coEvery { deleteSession(any()) } returns null  // Rueckgabetyp ist Unit?
    }

    private fun makePrefs(
        useGpsSpeed: Boolean = false,
        logOnlyDtc: Boolean = false,
    ): AppPreferences = mockk {
        every { slotPids } returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
        every { rpmDynamicThreshold } returns flowOf(false)
        every { logOnlyDtcSessions } returns flowOf(logOnlyDtc)
        every { logOnlyDiscoveredPids } returns flowOf(false)
        every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
        every { this@mockk.useGpsSpeed } returns flowOf(useGpsSpeed)
    }

    private fun makeGps(kmh: Float? = null): GpsSpeedSource = mockk {
        every { speedKmh } returns flowOf(kmh)
    }

    private fun makePipeline(
        repo: ObdRepository,
        sessionRepo: SessionRepository = makeSessionRepo(),
        prefs: AppPreferences = makePrefs(),
        gps: GpsSpeedSource = makeGps(),
        crashReporter: CrashReporter = mockk(relaxed = true),
        logRepo: AppLogRepository = mockk(relaxed = true),
    ) = ReadingsPipeline(repo, sessionRepo, prefs, logRepo, crashReporter, gps)

    // --- Test 1 ---

    @Test
    fun `without a connection the pipeline idles`() = runTest {
        val events = makePipeline(makeRepo(connected = false)).events(backgroundScope).take(1).toList()
        assertEquals(listOf(SessionEvent.Idle), events)
    }

    // --- Test 2 ---

    @Test
    fun `a connected session emits its readings and records them`() = runTest {
        val sessionRepo = makeSessionRepo()
        val events = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo)
            .events(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm))), events)
        coVerify { sessionRepo.startSession("VIN") }
        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
    }

    // --- Test 3 ---

    @Test
    fun `GPS fills in the speed the vehicle does not provide`() = runTest {
        val events = makePipeline(
            makeRepo(connected = true),
            prefs = makePrefs(useGpsSpeed = true),
            gps = makeGps(42f),
        ).events(backgroundScope).take(1).toList()

        val values = (events.single() as SessionEvent.Values).values
        val speed = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
        assertTrue("Die GPS-Geschwindigkeit fehlt: $values", speed != null)
        assertEquals(42f, speed!!.value, 0.001f)
    }

    // --- Test 4 ---

    @Test
    fun `a moving vehicle is reported to the repository`() = runTest {
        val repo = makeRepo(connected = true)
        makePipeline(repo, prefs = makePrefs(useGpsSpeed = true), gps = makeGps(42f))
            .events(backgroundScope).take(1).toList()

        verify { repo.setMoving(true) }
    }

    // --- Test 5 ---

    @Test
    fun `a lost connection is reported, not swallowed`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns kotlinx.coroutines.flow.flow {
            throw ObdException.ConnectionLost()
        }

        val events = makePipeline(repo).events(backgroundScope).take(1).toList()
        assertEquals(listOf(SessionEvent.ConnectionLost), events)
    }

    // --- Test 6 ---

    @Test
    fun `any other failure ends the session and reaches the crash reporter`() = runTest {
        val repo = makeRepo(connected = true)
        val boom = IllegalStateException("parser exploded")
        every { repo.livePids(any()) } returns kotlinx.coroutines.flow.flow { throw boom }
        val crashReporter = mockk<CrashReporter>(relaxed = true)
        val sessionRepo = makeSessionRepo()

        val events = makePipeline(repo, sessionRepo = sessionRepo, crashReporter = crashReporter)
            .events(backgroundScope).take(1).toList()

        assertTrue("Erwartet wurde ein Fehlerereignis: $events", events.single() is SessionEvent.Failed)
        // Seit der Zusammenfuehrung per combine() (1.15.2) durchquert die Exception eine zusaetzliche
        // Coroutine-Grenze; kotlinx.coroutines kopiert sie dabei fuer eine korrekte Stacktrace ("exception
        // was originally thrown here") - `eq(boom)` (Referenzgleichheit) traefe die Kopie nicht mehr, obwohl
        // Typ und Nachricht identisch sind.
        verify { crashReporter.recordException(match { it is IllegalStateException && it.message == boom.message }) }
        coVerify { sessionRepo.endSession(7L) }
    }

    // --- Test 7 ---

    @Test
    fun `a session without DTCs is discarded when only DTC drives are kept`() = runTest {
        val sessionRepo = makeSessionRepo()
        makePipeline(
            makeRepo(connected = true),
            sessionRepo = sessionRepo,
            prefs = makePrefs(logOnlyDtc = true),
        ).events(backgroundScope).take(1).toList()

        coVerify { sessionRepo.deleteSession(7L) }
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
    }

    // --- Test 8 ---

    @Test
    fun `GPS refreshes the displayed speed between OBD ticks but records only once per OBD tick`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow {
            emit(listOf(rpm))
            awaitCancellation()
        }
        val gps: GpsSpeedSource = mockk {
            every { speedKmh } returns flow {
                emit(10f)
                delay(100)
                emit(20f)
            }
        }
        val sessionRepo = makeSessionRepo()

        val collected = mutableListOf<SessionEvent>()
        val job = launch {
            makePipeline(repo, sessionRepo = sessionRepo, prefs = makePrefs(useGpsSpeed = true), gps = gps)
                .events(backgroundScope)
                .filterIsInstance<SessionEvent.Values>()
                .toList(collected)
        }
        advanceTimeBy(150)
        job.cancel()

        val speeds = collected.filterIsInstance<SessionEvent.Values>()
            .mapNotNull { evt -> evt.values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }?.value }
        assertEquals(listOf(10f, 20f), speeds)
        coVerify(exactly = 1) { sessionRepo.writeReadings(any(), any()) }
    }
}
