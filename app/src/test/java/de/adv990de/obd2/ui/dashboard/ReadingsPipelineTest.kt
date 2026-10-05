package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
        every { userDisconnectCount } returns MutableStateFlow(0)
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
        coEvery { discardSession(any()) } just Runs
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
        dtcHistory: DtcHistoryRepository = mockk(relaxed = true),
    ) = ReadingsPipeline(repo, sessionRepo, prefs, logRepo, crashReporter, gps, dtcHistory)

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
    fun `no trip is created when only DTC drives are kept and the vehicle reports none`() = runTest {
        val sessionRepo = makeSessionRepo()
        val events = makePipeline(
            makeRepo(connected = true),
            sessionRepo = sessionRepo,
            prefs = makePrefs(logOnlyDtc = true),
        ).events(backgroundScope).take(1).toList()

        // Die Entscheidung steht vor dem Start fest. Frueher wurde die Fahrt angelegt, aufgezeichnet und
        // am Ende verworfen: unnoetige Schreiblast, und ein Prozess-Kill dazwischen hinterliess eine
        // DTC-lose Fahrt entgegen der Einstellung.
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify(exactly = 0) { sessionRepo.startSession(any()) }
        coVerify(exactly = 0) { sessionRepo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
        coVerify(exactly = 0) { sessionRepo.discardSession(any()) }
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
        coVerify(exactly = 0) { sessionRepo.discardSession(any()) }
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

    // --- Test 15-user: the user's own disconnect is not a lost link (B3) ---

    // "Trennen" schliesst den Socket; ein gerade laufendes Lesen scheitert dann u. U. mit ConnectionLost,
    // bevor flatMapLatest die Sitzung abbricht. Das darf keinen Reconnect ausloesen — der Nutzer wollte
    // getrennt sein.
    @Test
    fun `a read that fails BECAUSE the user disconnected is not reported as a lost connection`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val userDisconnects = MutableStateFlow(0)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            every { repo.userDisconnectCount } returns userDisconnects
            every { repo.livePids(any()) } returns flow {
                userDisconnects.value += 1           // disconnectByUser(): the count first ...
                isConnected.value = false            // ... then the disconnect itself
                throw ObdException.ConnectionLost()  // the interrupted read
            }
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch { makePipeline(repo).events(this).toList(events) }

            testScheduler.advanceUntilIdle()

            assertTrue("kein Reconnect nach Nutzer-Trennen: $events", events.none { it == SessionEvent.ConnectionLost })
            assertTrue("die Anzeige geht auf getrennt: $events", events.contains(SessionEvent.Idle))
        }

    @Test
    fun `a fault-code read that fails BECAUSE the user disconnected does not ask for a reconnect`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val userDisconnects = MutableStateFlow(0)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            every { repo.userDisconnectCount } returns userDisconnects
            coEvery { repo.readDtcs() } coAnswers {
                userDisconnects.value += 1
                isConnected.value = false
                throw ObdException.ConnectionLost()
            }
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch { makePipeline(repo).events(this).toList(events) }

            testScheduler.advanceUntilIdle()

            assertTrue("kein Reconnect nach Nutzer-Trennen: $events", events.none { it == SessionEvent.ConnectionLost })
        }

    @Test
    fun `a lost link without a user disconnect is still reported`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            every { repo.livePids(any()) } returns flow {
                isConnected.value = false
                throw ObdException.ConnectionLost()
            }

            val event = makePipeline(repo).events(backgroundScope).first { it == SessionEvent.ConnectionLost }

            assertEquals(SessionEvent.ConnectionLost, event)
        }

    // --- B11: Fahrterkennung mit Hysterese ---

    private fun speed(kmh: Float, timestamp: Long = 1000L) = SensorValue(PidRegistry.SPEED, kmh, timestamp)

    @Test
    fun `standstill noise never reports moving, a real start does, a crawl keeps it, a stop ends it`() = runTest {
        val speeds = listOf(1.5f, 4f, 6f, 4f, 2.5f, 0f)
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flowOf(*speeds.map { listOf(speed(it)) }.toTypedArray())
        val calls = mutableListOf<Boolean>()
        every { repo.setMoving(any()) } answers { calls.add(firstArg()) }

        makePipeline(repo).events(backgroundScope).take(speeds.size).toList()

        assertEquals(listOf(false, false, true, true, false, false), calls)
    }

    // --- B29: values nobody refreshes are stale ---

    private fun TestScope.collect(pipeline: ReadingsPipeline): MutableList<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { pipeline.events(backgroundScope).toList(events) }
        return events
    }

    @Test
    fun `values that nobody refreshes become stale after the limit and the moving flag drops`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow { emit(listOf(speed(50f))); awaitCancellation() }
        val calls = mutableListOf<Boolean>()
        every { repo.setMoving(any()) } answers { calls.add(firstArg()) }
        val events = collect(makePipeline(repo))

        advanceTimeBy(OBD_STALE_AFTER_MS - 1); runCurrent()
        assertTrue("noch frisch: $events", events.none { it == SessionEvent.Stale })
        assertEquals("die Fahrt laeuft noch", true, calls.last())

        advanceTimeBy(2); runCurrent()
        assertTrue("nach dem Limit: $events", events.contains(SessionEvent.Stale))
        assertEquals("veraltet heisst: steht", false, calls.last())
    }

    @Test
    fun `an ECU that is silent from the very start is reported stale too (B24 - waiting must end)`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow { awaitCancellation() }      // never delivers a cycle
        val events = collect(makePipeline(repo))

        advanceTimeBy(OBD_STALE_AFTER_MS - 1); runCurrent()
        assertTrue("noch innerhalb der Frist: $events", events.none { it == SessionEvent.Stale })

        advanceTimeBy(2); runCurrent()
        assertEquals(listOf<SessionEvent>(SessionEvent.Stale), events)
    }

    @Test
    fun `fresh cycles keep the values from going stale`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow {
            repeat(5) { emit(listOf(rpm)); delay(5_000) }   // cycles at 0, 5, 10, 15, 20 s
            awaitCancellation()
        }
        val events = collect(makePipeline(repo))

        advanceTimeBy(OBD_STALE_AFTER_MS + 20_000 - 100); runCurrent()   // just before 20 s + limit
        assertTrue("jeder Zyklus startet die Frist neu: $events", events.none { it == SessionEvent.Stale })

        advanceTimeBy(200); runCurrent()
        assertTrue(events.contains(SessionEvent.Stale))
    }

    @Test
    fun `a cycle after a stale phase brings the values back and restarts the limit`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow {
            emit(listOf(rpm)); delay(15_000); emit(listOf(rpm.copy(timestamp = 2000L))); awaitCancellation()
        }
        val events = collect(makePipeline(repo))

        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null), SessionEvent.Stale), events)
        advanceTimeBy(5_000); runCurrent()          // t = 15 s: the next cycle arrives
        assertTrue("wieder Werte: $events", events.last() is SessionEvent.Values)
        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        assertEquals("die Frist lief neu an", 2, events.count { it == SessionEvent.Stale })
    }

    @Test
    fun `a stale session does not re-merge its old readings when the GPS moves on`() = runTest {
        val gps = MutableStateFlow<Float?>(null)
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow { emit(listOf(rpm)); awaitCancellation() }
        val gpsSource: GpsSpeedSource = mockk { every { speedKmh } returns gps }
        val events = collect(makePipeline(repo, prefs = makePrefs(useGpsSpeed = true), gps = gpsSource))
        runCurrent()
        gps.value = 10f; runCurrent()
        assertEquals("solange sie frisch sind, ergaenzt GPS sie: $events", 2, events.count { it is SessionEvent.Values })

        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        val before = events.size
        gps.value = 20f; runCurrent()

        assertEquals("veraltete Werte duerfen nicht mit neuem GPS 'live' erscheinen: $events", before, events.size)
    }

    @Test
    fun `a new session starts from standing - the drive hysteresis does not carry over a reconnect`() = runTest {
        val isConnected = MutableStateFlow(true)
        val repo = makeRepo(connected = true)
        every { repo.isConnectedFlow } returns isConnected
        every { repo.livePids(any()) } returnsMany listOf(
            flow { emit(listOf(speed(60f))); awaitCancellation() },
            flow { emit(listOf(speed(4f))); awaitCancellation() },   // inside the hysteresis band
        )
        val calls = mutableListOf<Boolean>()
        every { repo.setMoving(any()) } answers { calls.add(firstArg()) }
        collect(makePipeline(repo))
        runCurrent()

        isConnected.value = false; runCurrent()
        isConnected.value = true; runCurrent()

        assertEquals("60 km/h fuehrt zu 'faehrt', die neue Sitzung bei 4 km/h beginnt bei 'steht'", listOf(true, false), calls)
    }

    @Test
    fun `after a stale phase a fresh cycle lets the GPS refresh merge again`() = runTest {
        val gps = MutableStateFlow<Float?>(null)
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow {
            emit(listOf(rpm)); delay(15_000); emit(listOf(rpm.copy(timestamp = 2000L))); awaitCancellation()
        }
        val gpsSource: GpsSpeedSource = mockk { every { speedKmh } returns gps }
        val events = collect(makePipeline(repo, prefs = makePrefs(useGpsSpeed = true), gps = gpsSource))
        advanceTimeBy(15_001); runCurrent()                 // stale at 10 s, fresh again at 15 s
        events.clear()

        gps.value = 12f; runCurrent()

        assertTrue("frische Werte werden wieder mit GPS ergaenzt: $events", events.any { it is SessionEvent.Values })
    }

    // --- B12: nothing of the previous connection survives into a new session ---

    @Test
    fun `after a reconnect a GPS update does not resurrect the readings of the previous connection`() = runTest {
        val isConnected = MutableStateFlow(true)
        val gps = MutableStateFlow<Float?>(null)
        val repo = makeRepo(connected = true)
        every { repo.isConnectedFlow } returns isConnected
        every { repo.livePids(any()) } returnsMany listOf(
            flow { emit(listOf(rpm)); awaitCancellation() },   // the first connection
            flow { awaitCancellation() },                       // after the reconnect: silent so far
        )
        val gpsSource: GpsSpeedSource = mockk { every { speedKmh } returns gps }
        val events = collect(makePipeline(repo, prefs = makePrefs(useGpsSpeed = true), gps = gpsSource))
        runCurrent()
        assertTrue("erste Verbindung liefert: $events", events.any { it is SessionEvent.Values })

        isConnected.value = false; runCurrent()
        isConnected.value = true; runCurrent()              // new session, no cycle yet
        events.clear()
        gps.value = 25f; runCurrent()

        assertTrue("alte Werte erscheinen nicht als live: $events", events.none { it is SessionEvent.Values })
    }

    // --- Test 15a: recording is best effort — a database failure must not end the live display ---

    @Test
    fun `a failing write does not end the live display and is logged once per session`() = runTest {
        val repo = makeRepo(connected = true)
        val second = rpm.copy(value = 1600f, timestamp = 2000L)
        every { repo.livePids(any()) } returns flowOf(listOf(rpm), listOf(second), listOf(second))
        val sessionRepo = makeSessionRepo()
        coEvery { sessionRepo.writeReadings(any(), any()) } throws IllegalStateException("database or disk is full")
        val logRepo: AppLogRepository = mockk(relaxed = true)
        val crashReporter: CrashReporter = mockk(relaxed = true)

        val events = makePipeline(repo, sessionRepo = sessionRepo, logRepo = logRepo, crashReporter = crashReporter)
            .events(backgroundScope).take(3).toList()

        assertTrue("every cycle still reaches the display: $events", events.all { it is SessionEvent.Values })
        assertEquals(3, events.size)
        // Einmal je Sitzung, nicht je Zyklus: ein Fehler mit 2 Hz wuerde sonst das (ebenfalls betroffene) Log fluten.
        coVerify(exactly = 1) { logRepo.log(LogLevel.WARN, match { it.contains("Recording failed") }) }
        verify(exactly = 1) { crashReporter.recordException(any()) }
        coVerify { sessionRepo.endSession(7L) }
    }

    @Test
    fun `a trip that cannot be created does not end the live display either`() = runTest {
        val sessionRepo = makeSessionRepo()
        coEvery { sessionRepo.startSession(any()) } throws IllegalStateException("database is locked")
        val logRepo: AppLogRepository = mockk(relaxed = true)

        val events = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo, logRepo = logRepo)
            .events(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify(exactly = 0) { sessionRepo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
        coVerify(exactly = 1) { logRepo.log(LogLevel.WARN, match { it.contains("Recording failed") }) }
    }

    // --- Test 15b: the fault-code read drops the link ---

    // readDtcs() trennt bei einer Zeitueberschreitung absichtlich (ein spaeter Rest der Antwort wuerde
    // sonst den naechsten Befehl verfaelschen). Vorher schluckte runSession() die Ausnahme wie jeden
    // Lesefehler: die Verbindung war weg, die Sitzung endete als "Idle", aber nichts loeste den
    // Reconnect aus — stiller Abbruch. Betroffen war auch jeder Slot-Wechsel, denn er startet
    // runSession() und damit das Auslesen neu.
    @Test
    fun `a fault-code read that drops the link asks for a reconnect`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            coEvery { repo.readDtcs() } coAnswers {
                isConnected.value = false // exactly what ObdRepository.readDtcs() does before it rethrows
                throw ObdException.Timeout("03")
            }
            val logRepo: AppLogRepository = mockk(relaxed = true) {
                coEvery { log(any(), any()) } coAnswers { delay(1) }
            }

            val event = makePipeline(repo, logRepo = logRepo).events(backgroundScope)
                .first { it == SessionEvent.ConnectionLost }

            assertEquals(SessionEvent.ConnectionLost, event)
        }

    @Test
    fun `a fault-code read that drops the link does not start a recording`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            coEvery { repo.readDtcs() } coAnswers {
                isConnected.value = false
                throw ObdException.ConnectionLost()
            }
            val sessionRepo = makeSessionRepo()

            makePipeline(repo, sessionRepo = sessionRepo).events(backgroundScope)
                .first { it == SessionEvent.ConnectionLost }

            coVerify(exactly = 0) { sessionRepo.startSession(any()) }
        }

    @Test
    fun `the lost-link warning is written even though the session is already being cancelled`() =
        runTest(UnconfinedTestDispatcher()) {
            val isConnected = MutableStateFlow(true)
            val repo = makeRepo(connected = true)
            every { repo.isConnectedFlow } returns isConnected
            coEvery { repo.readDtcs() } coAnswers {
                isConnected.value = false
                throw ObdException.Timeout("03")
            }
            var written = false
            val logRepo: AppLogRepository = mockk(relaxed = true) {
                // delay() ist ein Abbruchpunkt — ohne NonCancellable bliebe das Log unvollstaendig.
                coEvery { log(any(), any()) } coAnswers { delay(1); written = true }
            }

            makePipeline(repo, logRepo = logRepo).events(backgroundScope)
                .first { it == SessionEvent.ConnectionLost }
            testScheduler.advanceUntilIdle()

            assertTrue("the WARN line about the dropped link must be persisted", written)
        }

    @Test
    fun `a fault-code read that fails while the link stays up keeps the session running`() = runTest {
        val repo = makeRepo(connected = true)
        // Zeitueberschreitung, aber der Link steht noch (isConnectedFlow bleibt true): kein Grund,
        // die Sitzung zu beenden oder neu zu verbinden.
        coEvery { repo.readDtcs() } throws ObdException.Timeout("03")

        val events = makePipeline(repo).events(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
    }

    // --- Test 16: the local fault history ---

    @Test
    fun `the DTCs read at connect are recorded in the fault history`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", "Cylinder 1 Misfire Detected", false), DtcEntry("P0420", "Catalyst", true))
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns dtcs
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(repo, dtcHistory = history).events(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { history.record(dtcs, any()) }
    }

    @Test
    fun `an empty reading is passed on too - the repository ignores it`() = runTest {
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(makeRepo(connected = true), dtcHistory = history).events(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { history.record(emptyList(), any()) }
    }

    @Test
    fun `a failed DTC read records nothing`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } throws IllegalStateException("ELM327 timeout")
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(repo, dtcHistory = history).events(backgroundScope).take(1).toList()

        coVerify(exactly = 0) { history.record(any(), any()) }
    }

    @Test
    fun `a throwing fault history does not stop the session`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns listOf(DtcEntry("P0301", "x", false))
        val history = mockk<DtcHistoryRepository> {
            coEvery { record(any(), any()) } throws IllegalStateException("history exploded")
        }
        val sessionRepo = makeSessionRepo()

        val events = makePipeline(repo, sessionRepo = sessionRepo, dtcHistory = history)
            .events(backgroundScope).take(1).toList()

        // The history is a side record: whatever goes wrong there must not cost the user the live session.
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
    }
}
