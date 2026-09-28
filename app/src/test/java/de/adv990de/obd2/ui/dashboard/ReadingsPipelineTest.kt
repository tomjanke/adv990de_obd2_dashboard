package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val coolantTemp = SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 90f, timestamp = 1000L)

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
        rpmDynamicThreshold: Boolean = false,
        logOnlyDiscoveredPids: Boolean = false,
        slotPids: List<String> = AppPreferences.DEFAULT_SLOT_PIDS,
        obdWatchdogTimeoutSeconds: Int = AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS,
    ): AppPreferences = mockk {
        every { this@mockk.slotPids } returns flowOf(slotPids)
        every { this@mockk.rpmDynamicThreshold } returns flowOf(rpmDynamicThreshold)
        every { logOnlyDtcSessions } returns flowOf(logOnlyDtc)
        every { this@mockk.logOnlyDiscoveredPids } returns flowOf(logOnlyDiscoveredPids)
        every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
        every { this@mockk.useGpsSpeed } returns flowOf(useGpsSpeed)
        every { this@mockk.obdWatchdogTimeoutSeconds } returns flowOf(obdWatchdogTimeoutSeconds)
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

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
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

    // --- Test 9 ---

    @Test
    fun `dynamic RPM threshold forces oil and coolant temp PIDs into the selection`() = runTest {
        val repo = makeRepo(connected = true)
        val pidsSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot)) } returns flowOf(listOf(rpm))
        val prefs = makePrefs(
            rpmDynamicThreshold = true,
            slotPids = listOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id),
        )

        makePipeline(repo, prefs = prefs).events(backgroundScope).take(1).toList()

        val selectedIds = pidsSlot.captured.map { it.id }
        assertEquals(
            setOf(
                PidRegistry.THROTTLE.id,
                PidRegistry.ENGINE_LOAD.id,
                PidRegistry.RPM.id,
                PidRegistry.ENGINE_OIL_TEMP.id,
                PidRegistry.COOLANT_TEMP.id,
            ),
            selectedIds.toSet(),
        )
    }

    // --- Test 10 ---

    @Test
    fun `without the dynamic RPM threshold, coolant temp is still force-included but oil temp is not`() = runTest {
        val repo = makeRepo(connected = true)
        val pidsSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot)) } returns flowOf(listOf(rpm))
        val prefs = makePrefs(
            rpmDynamicThreshold = false,
            slotPids = listOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id),
        )

        makePipeline(repo, prefs = prefs).events(backgroundScope).take(1).toList()

        val selectedIds = pidsSlot.captured.map { it.id }
        assertEquals(
            setOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            selectedIds.toSet(),
        )
        assertTrue(PidRegistry.ENGINE_OIL_TEMP.id !in selectedIds)
        // Seit dem Oel-Temperatur-Balken unbedingt, wie RPM: sonst bliebe der Balken bei jeder
        // Slot-Belegung ohne Kuehlmittel (und ausgeschalteter dynamischer Schwelle) dauerhaft grau.
        assertTrue(PidRegistry.COOLANT_TEMP.id in selectedIds)
    }

    // --- Test 10b ---

    @Test
    fun `coolant temp is polled only once when it is also one of the slots`() = runTest {
        // Die Default-Belegung enthaelt COOLANT_TEMP bereits als Slot; das unbedingte Hinzufuegen
        // darf ihn nicht ein zweites Mal pro Zyklus abfragen (distinctBy in selectPids()).
        val repo = makeRepo(connected = true)
        val pidsSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot)) } returns flowOf(listOf(rpm))
        val prefs = makePrefs(rpmDynamicThreshold = true, slotPids = AppPreferences.DEFAULT_SLOT_PIDS)

        makePipeline(repo, prefs = prefs).events(backgroundScope).take(1).toList()

        val selectedIds = pidsSlot.captured.map { it.id }
        assertEquals(1, selectedIds.count { it == PidRegistry.COOLANT_TEMP.id })
        assertEquals(1, selectedIds.count { it == PidRegistry.RPM.id })
    }

    // --- Test 11 ---

    @Test
    fun `logOnlyDiscoveredPids filters recorded readings down to supported PIDs`() = runTest {
        val readings = listOf(rpm, coolantTemp)
        val repo = makeRepo(connected = true, readings = readings, supported = setOf(rpm.pid.id))
        val sessionRepo = makeSessionRepo()
        val prefs = makePrefs(logOnlyDiscoveredPids = true)

        makePipeline(repo, sessionRepo = sessionRepo, prefs = prefs)
            .events(backgroundScope).take(1).toList()

        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
    }

    // --- Test 12 ---

    @Test
    fun `without logOnlyDiscoveredPids, unsupported readings are still recorded`() = runTest {
        val readings = listOf(rpm, coolantTemp)
        val repo = makeRepo(connected = true, readings = readings, supported = setOf(rpm.pid.id))
        val sessionRepo = makeSessionRepo()
        val prefs = makePrefs(logOnlyDiscoveredPids = false)

        makePipeline(repo, sessionRepo = sessionRepo, prefs = prefs)
            .events(backgroundScope).take(1).toList()

        coVerify { sessionRepo.writeReadings(7L, readings) }
    }

    // --- Test 13 ---

    @Test
    fun `a failing readDtcs does not crash the session and keeps it under logOnlyDtc as a fail-safe`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } throws IllegalStateException("ELM327 timeout")
        val sessionRepo = makeSessionRepo()

        val events = makePipeline(
            repo,
            sessionRepo = sessionRepo,
            prefs = makePrefs(logOnlyDtc = true),
        ).events(backgroundScope).take(1).toList()

        // readDtcs() throwing is caught inside runSession()'s own try/catch: the session still runs
        // and emits its readings normally, it does not surface as SessionEvent.Failed.
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
        // dtcList becomes null, so hasDtcForRecording falls back to true (fail-safe) — even with
        // logOnlyDtc enabled, the session must be kept, not discarded as a "no DTC" drive.
        coVerify(exactly = 0) { sessionRepo.deleteSession(any()) }
        coVerify { sessionRepo.endSession(7L) }
    }

    // --- Test 14 ---

    @Test
    fun `the configured OBD watchdog timeout reaches livePids in milliseconds`() = runTest {
        val repo = makeRepo(connected = true)
        // Every parameter open: makeRepo's `livePids(any())` stub only matches the DEFAULT values of
        // the remaining parameters (MockK records Kotlin default arguments as concrete values), so
        // it would not answer a call carrying the non-default 45_000 ms.
        every { repo.livePids(any(), any(), any(), any()) } returns flowOf(listOf(rpm))

        makePipeline(repo, prefs = makePrefs(obdWatchdogTimeoutSeconds = 45))
            .events(backgroundScope).take(1).toList()

        verify {
            repo.livePids(
                pids = any(),
                pollIntervalMs = any(),
                watchdogTimeoutMs = 45_000L,
                timeSource = any(),
            )
        }
    }

    // --- Test 15 ---

    // StateFlow-Reentrancy-Risiko (s. CLAUDE.md / android-test-environment-pitfalls.md /
    // .superpowers/sdd/2026-09-26-obd-watchdog-bugfix/progress.md Finding "C2"): die echte
    // ObdRepository.livePids() setzt isConnectedFlow.value = false (in disconnect(), VOR dem
    // throw) — und isConnectedFlow ist genau der Schluessel, auf den events() selbst per
    // combine()/flatMapLatest() reagiert. Auf einem Dispatcher, der StateFlow-Updates
    // eager/reentrant zustellt (Dispatchers.Main.immediate in Produktion; hier
    // UnconfinedTestDispatcher — das Standardmittel, um das in einem JVM-Test zu erzwingen), kann
    // dieser Wertwechsel genau die Coroutine abbrechen, die gerade dabei ist, auf die Exception zu
    // reagieren, die ihn ausgeloest hat. Der Standard-runTest (StandardTestDispatcher, FIFO/queued)
    // kann diese Race NICHT auffangen — s. DashboardScreenTest.kt's
    // dashboard_showsConnectingDot_whenReconnecting-Kommentar fuer die am echten Geraet bestaetigte
    // Ursache. Dieser Test reproduziert sie deterministisch statt sich auf ein Geraet zu verlassen.
    @Test
    fun `a lost connection is still reported when isConnectedFlow flips synchronously (reentrancy race)`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            every { repo.livePids(any()) } returns flow {
                isConnected.value = false // exactly what ObdRepository.disconnect() does
                throw ObdException.ConnectionLost()
            }
            // A real suspension point, mirroring AppLogRepository.log()'s actual Room dao.insert()
            // call in production — a relaxed mock's log() would otherwise never suspend at all,
            // so a pending cancellation would have nowhere to land and this test would pass for
            // the wrong reason regardless of whether the production code is fixed.
            val logRepo: AppLogRepository = mockk(relaxed = true) {
                coEvery { log(any(), any()) } coAnswers { delay(1) }
            }

            val events = makePipeline(repo, logRepo = logRepo).events(backgroundScope).take(1).toList()

            assertEquals(listOf(SessionEvent.ConnectionLost), events)
        }
}
