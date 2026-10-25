package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

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
        // Die Fehlerwache liest erst nach 60 s; ein Test, dessen Sitzung so lange offen bleibt, braucht den Stub (die Attrappe ist streng).
        coEvery { readDtcsOrSkip() } returns null
    }

    private fun makeSessionRepo(): SessionRepository = mockk {
        coEvery { startSession(any(), any()) } returns 7L
        coEvery { writeReadings(any(), any()) } just Runs
        coEvery { endSession(any()) } just Runs
        coEvery { discardSession(any()) } just Runs
    }

    private fun makeGps(kmh: Float? = null): GpsSpeedSource = mockk {
        every { speedKmh } returns flowOf(kmh)
    }

    private fun makePipeline(
        repo: ObdRepository,
        sessionRepo: SessionRepository = makeSessionRepo(),
        prefs: AppPreferences = fakeAppPreferences(),
        gps: GpsSpeedSource = makeGps(),
        crashReporter: CrashReporter = mockk(relaxed = true),
        logRepo: AppLogRepository = mockk(relaxed = true),
        dtcHistory: DtcHistoryRepository = mockk(relaxed = true),
    ) = ReadingsPipeline(repo, sessionRepo, prefs, logRepo, crashReporter, gps, dtcHistory)

    /**
     * Die Sitzungsereignisse OHNE die Fehlercode-Meldungen ([SessionEvent.FaultCodes]): fast alle Tests fragen nach Werten und
     * Lebenszyklus der Sitzung und zaehlen mit `take(n)` — die Basis-Meldung des Auslesens beim Verbinden kaeme sonst als erstes Ereignis
     * dazwischen. Die Fehlerwache pruefen die Tests unten mit dem ungefilterten [ReadingsPipeline.events].
     */
    private fun ReadingsPipeline.sessionEvents(scope: CoroutineScope): Flow<SessionEvent> =
        events(scope).filterNot { it is SessionEvent.FaultCodes }

    // --- Test 1 ---

    @Test
    fun `without a connection the pipeline idles`() = runTest {
        val events = makePipeline(makeRepo(connected = false)).sessionEvents(backgroundScope).take(1).toList()
        assertEquals(listOf(SessionEvent.Idle), events)
    }

    // --- Test 2 ---

    @Test
    fun `a connected session emits its readings and records them`() = runTest {
        val sessionRepo = makeSessionRepo()
        val events = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo)
            .sessionEvents(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify { sessionRepo.startSession("VIN", emptyList()) }
        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
    }

    // --- Test 3 ---

    @Test
    fun `GPS fills in the speed the vehicle does not provide`() = runTest {
        val events = makePipeline(
            makeRepo(connected = true),
            prefs = fakeAppPreferences(useGpsSpeed = true),
            gps = makeGps(42f),
        ).sessionEvents(backgroundScope).take(1).toList()

        val values = (events.single() as SessionEvent.Values).values
        val speed = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
        assertTrue("Die GPS-Geschwindigkeit fehlt: $values", speed != null)
        assertEquals(42f, speed!!.value, 0.001f)
    }

    @Test
    fun `a recorded speed says where it came from - GPS fill-in versus the vehicle`() = runTest {
        // GPS_SPEED hat dieselbe PID-ID wie die Fahrzeug-Geschwindigkeit: ohne Herkunft waere die Aufzeichnung nicht auswertbar.
        val gpsFilled = slot<List<SensorValue>>()
        val sessionRepoGps = makeSessionRepo()
        coEvery { sessionRepoGps.writeReadings(7L, capture(gpsFilled)) } just Runs
        makePipeline(
            makeRepo(connected = true),
            sessionRepo = sessionRepoGps,
            prefs = fakeAppPreferences(useGpsSpeed = true),
            gps = makeGps(42f),
        ).sessionEvents(backgroundScope).take(1).toList()
        val gpsSpeed = gpsFilled.captured.single { it.pid.id == PidRegistry.SPEED.id }
        assertEquals(ValueSource.GPS, gpsSpeed.source)
        assertEquals(ValueSource.ECU, gpsFilled.captured.single { it.pid.id == PidRegistry.RPM.id }.source)

        val vehicleSpeed = SensorValue(pid = PidRegistry.SPEED, value = 55f, timestamp = 1000L)
        val ecuRecorded = slot<List<SensorValue>>()
        val sessionRepoEcu = makeSessionRepo()
        coEvery { sessionRepoEcu.writeReadings(7L, capture(ecuRecorded)) } just Runs
        makePipeline(
            makeRepo(connected = true, readings = listOf(rpm, vehicleSpeed)),
            sessionRepo = sessionRepoEcu,
            prefs = fakeAppPreferences(useGpsSpeed = true),
            gps = makeGps(42f),
        ).sessionEvents(backgroundScope).take(1).toList()
        val ecuSpeed = ecuRecorded.captured.single { it.pid.id == PidRegistry.SPEED.id }
        assertEquals("der Fahrzeugwert hat Vorrang", 55f, ecuSpeed.value, 0.001f)
        assertEquals(ValueSource.ECU, ecuSpeed.source)
    }

    @Test
    fun `with the GPS setting off the pipeline hands on no GPS speed, even when the source has a fix`() = runTest {
        val events = makePipeline(
            makeRepo(connected = true),
            prefs = fakeAppPreferences(useGpsSpeed = false),
            gps = makeGps(42f),
        ).sessionEvents(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
    }

    // --- Test 4 ---

    @Test
    fun `a moving vehicle is reported to the repository`() = runTest {
        val repo = makeRepo(connected = true)
        makePipeline(repo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = makeGps(42f))
            .sessionEvents(backgroundScope).take(1).toList()

        verify { repo.setMoving(true) }
    }

    // --- Test 5 ---

    @Test
    fun `a lost connection is reported, not swallowed`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns kotlinx.coroutines.flow.flow {
            throw ObdException.ConnectionLost()
        }

        val events = makePipeline(repo).sessionEvents(backgroundScope).take(1).toList()
        assertEquals(listOf(SessionEvent.ConnectionLost()), events)
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
            .sessionEvents(backgroundScope).take(1).toList()

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
            prefs = fakeAppPreferences(logOnlyDtcSessions = true),
        ).sessionEvents(backgroundScope).take(1).toList()

        // Die Entscheidung steht vor dem Start fest. Frueher wurde die Fahrt angelegt, aufgezeichnet und
        // am Ende verworfen: unnoetige Schreiblast, und ein Prozess-Kill dazwischen hinterliess eine
        // DTC-lose Fahrt entgegen der Einstellung.
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify(exactly = 0) { sessionRepo.startSession(any(), any()) }
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
            makePipeline(repo, sessionRepo = sessionRepo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gps)
                .sessionEvents(backgroundScope)
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

    @Test
    fun `only the OBD tick is flagged as an OBD cycle - a GPS refresh in between is not`() = runTest {
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

        val collected = mutableListOf<SessionEvent.Values>()
        val job = launch {
            makePipeline(repo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gps)
                .sessionEvents(backgroundScope)
                .filterIsInstance<SessionEvent.Values>()
                .toList(collected)
        }
        advanceTimeBy(150)
        job.cancel()

        assertEquals(listOf(true, false), collected.map { it.isObdCycle })
    }

    // --- Derived values in the recording (gear and oil bar are computed by the ViewModel, written here) ---

    @Test
    fun `the displayed gear is written into the running trip`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow { emit(listOf(rpm)); awaitCancellation() }
        val sessionRepo = makeSessionRepo()
        val pipeline = makePipeline(repo, sessionRepo = sessionRepo)
        val job = launch { pipeline.sessionEvents(backgroundScope).collect { } }
        advanceTimeBy(1)
        runCurrent()

        val gear = GearReading(gear = 3, isNeutral = false)
        pipeline.recordDerived(listOf(gearSensorValue(gear, timestamp = 2000L)))
        job.cancel()

        coVerify { sessionRepo.writeReadings(7L, listOf(gearSensorValue(gear, timestamp = 2000L))) }
    }

    @Test
    fun `the gear and the oil bar of one cycle are written together, in one database write`() = runTest {
        val repo = makeRepo(connected = true)
        every { repo.livePids(any()) } returns flow { emit(listOf(rpm)); awaitCancellation() }
        val sessionRepo = makeSessionRepo()
        val pipeline = makePipeline(repo, sessionRepo = sessionRepo)
        val job = launch { pipeline.sessionEvents(backgroundScope).collect { } }
        advanceTimeBy(1)
        runCurrent()

        val derived = listOf(
            gearSensorValue(GearReading(gear = 3, isNeutral = false), timestamp = 2000L),
            oilBarSensorValue(OilBarColor.Green, timestamp = 2000L),
        )
        pipeline.recordDerived(derived)
        job.cancel()

        coVerify(exactly = 1) { sessionRepo.writeReadings(7L, derived) }
    }

    @Test
    fun `no gear is written without a running trip`() = runTest {
        val sessionRepo = makeSessionRepo()
        val pipeline = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo)

        pipeline.recordDerived(listOf(gearSensorValue(GearReading(gear = 3, isNeutral = false), timestamp = 2000L)))

        coVerify(exactly = 0) { sessionRepo.writeReadings(any(), any()) }
    }

    @Test
    fun `no gear is written once the session has ended`() = runTest {
        val sessionRepo = makeSessionRepo()
        val pipeline = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo)
        pipeline.sessionEvents(backgroundScope).take(1).toList()   // the flow completes: the trip is closed

        pipeline.recordDerived(listOf(gearSensorValue(GearReading(gear = 3, isNeutral = false), timestamp = 2000L)))

        coVerify(exactly = 1) { sessionRepo.writeReadings(any(), any()) }   // only the cycle itself
    }

    @Test
    fun `a gear is not written to the trip of the next session by the one that ended`() = runTest {
        val isConnected = MutableStateFlow(true)
        val repo = makeRepo(connected = true)
        every { repo.isConnectedFlow } returns isConnected
        every { repo.livePids(any()) } returns flow { emit(listOf(rpm)); awaitCancellation() }
        val sessionRepo = makeSessionRepo()
        val pipeline = makePipeline(repo, sessionRepo = sessionRepo)
        val job = launch { pipeline.sessionEvents(backgroundScope).collect { } }
        advanceTimeBy(1)
        runCurrent()

        isConnected.value = false   // the session ends, the pipeline idles
        advanceTimeBy(1)
        runCurrent()
        pipeline.recordDerived(listOf(gearSensorValue(GearReading(gear = 2, isNeutral = false), timestamp = 3000L)))
        job.cancel()

        coVerify(exactly = 1) { sessionRepo.writeReadings(any(), any()) }
        coVerify { sessionRepo.endSession(7L) }
    }

    // --- Test 9 ---

    @Test
    fun `dynamic RPM threshold forces oil and coolant temp PIDs into the selection`() = runTest {
        val repo = makeRepo(connected = true)
        val pidsSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot)) } returns flowOf(listOf(rpm))
        val prefs = fakeAppPreferences(
            rpmDynamicThreshold = true,
            slotPids = listOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id),
        )

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

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
        val prefs = fakeAppPreferences(
            rpmDynamicThreshold = false,
            slotPids = listOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id),
        )

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

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
        val prefs = fakeAppPreferences(rpmDynamicThreshold = true, slotPids = AppPreferences.DEFAULT_SLOT_PIDS)

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

        val selectedIds = pidsSlot.captured.map { it.id }
        assertEquals(1, selectedIds.count { it == PidRegistry.COOLANT_TEMP.id })
        assertEquals(1, selectedIds.count { it == PidRegistry.RPM.id })
    }

    // --- Test 11 ---

    @Test
    fun `everything that is read is recorded - no filter against the list of the vehicle`() = runTest {
        val readings = listOf(rpm, coolantTemp)
        // Das Steuergeraet fuehrt nur die Drehzahl; die Aufzeichnung verwirft trotzdem nichts (bis 1.27.3 tat das ein Schalter).
        val repo = makeRepo(connected = true, readings = readings, supported = setOf(rpm.pid.id))
        val sessionRepo = makeSessionRepo()

        makePipeline(repo, sessionRepo = sessionRepo)
            .sessionEvents(backgroundScope).take(1).toList()

        coVerify { sessionRepo.writeReadings(7L, readings) }
    }

    // --- Test 12 ---

    @Test
    fun `the GPS speed is recorded when the vehicle does not list the speed`() = runTest {
        // Motorrad: die Geschwindigkeit liegt nicht auf dem OBD2-Bus, das Steuergeraet fuehrt 010D nicht. Mit GPS an gehoert sie trotzdem
        // in die Fahrt (frueher nahm der Schalter "nur vom Fahrzeug gemeldete Werte" sie heraus).
        val repo = makeRepo(connected = true, readings = listOf(rpm), supported = setOf(rpm.pid.id))
        val written = slot<List<SensorValue>>()
        val sessionRepo = makeSessionRepo()
        coEvery { sessionRepo.writeReadings(7L, capture(written)) } just Runs

        makePipeline(repo, sessionRepo = sessionRepo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = makeGps(42f))
            .sessionEvents(backgroundScope).take(1).toList()

        val speed = written.captured.single { it.pid.id == PidRegistry.SPEED.id }
        assertEquals(ValueSource.GPS, speed.source)
        assertEquals(42f, speed.value, 0.001f)
    }

    // --- Test 12b ---

    @Test
    fun `the speed is queried without a tile when the vehicle lists it`() = runTest {
        val repo = makeRepo(connected = true, supported = setOf(PidRegistry.SPEED.id, PidRegistry.RPM.id))
        val pidsSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot)) } returns flowOf(listOf(rpm))
        val prefs = fakeAppPreferences(slotPids = listOf(PidRegistry.ENGINE_LOAD.id, "", "", ""))

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

        assertTrue(PidRegistry.SPEED.id in pidsSlot.captured.map { it.id })
    }

    // --- Test 12c: langsame PIDs (Spannung fuer den Spannungsalarm) ---

    /**
     * Die `slowPids`, die die Pipeline an `livePids` gibt. Alle sechs Parameter offen: der Stub `livePids(any())` von [makeRepo] passt nur
     * auf die Standardwerte der uebrigen (MockK zeichnet Kotlin-Standardargumente als feste Werte auf) und wuerde eine nicht leere Liste
     * nicht beantworten.
     */
    private suspend fun TestScope.slowPidsPassedToLivePids(
        supported: Set<String>?,
        slotPids: List<String> = listOf(PidRegistry.ENGINE_LOAD.id, "", "", ""),
        alarms: AlarmSettings = AlarmSettings(),
    ): List<PidDefinition> {
        val repo = makeRepo(connected = true, supported = supported)
        val slowSlot = slot<List<PidDefinition>>()
        every { repo.livePids(any(), any(), any(), any(), capture(slowSlot), any()) } returns flowOf(listOf(rpm))
        val prefs = fakeAppPreferences(slotPids = slotPids, alarmSettings = alarms)

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

        return slowSlot.captured
    }

    @Test
    fun `the voltage is slow-polled when the alarm is on and the vehicle reports it`() = runTest {
        val slow = slowPidsPassedToLivePids(supported = setOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id))

        assertEquals(listOf(PidRegistry.CONTROL_MODULE_VOLTAGE), slow)
    }

    @Test
    fun `no slow PIDs when the voltage alarm is off`() = runTest {
        val slow = slowPidsPassedToLivePids(
            supported = setOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id),
            alarms = AlarmSettings(voltageEnabled = false),
        )

        assertEquals(emptyList<PidDefinition>(), slow)
    }

    @Test
    fun `no slow PIDs without a discovery result or when the vehicle does not report the voltage`() = runTest {
        assertEquals("keine Discovery", emptyList<PidDefinition>(), slowPidsPassedToLivePids(supported = null))
        assertEquals(
            "Fahrzeug meldet 0142 nicht",
            emptyList<PidDefinition>(),
            slowPidsPassedToLivePids(supported = setOf(PidRegistry.RPM.id)),
        )
    }

    @Test
    fun `no slow PIDs when the voltage is a tile, it is queried every cycle then`() = runTest {
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id)
        val repo = makeRepo(connected = true, supported = supported)
        val pidsSlot = slot<List<PidDefinition>>()
        val slowSlot = slot<List<PidDefinition>>()
        every { repo.livePids(capture(pidsSlot), any(), any(), any(), capture(slowSlot), any()) } returns flowOf(listOf(rpm))
        val prefs = fakeAppPreferences(slotPids = listOf(PidRegistry.CONTROL_MODULE_VOLTAGE.id, "", "", ""))

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(1).toList()

        assertTrue(PidRegistry.CONTROL_MODULE_VOLTAGE.id in pidsSlot.captured.map { it.id })
        assertEquals(emptyList<PidDefinition>(), slowSlot.captured)
    }

    @Test
    fun `the alarm switch is read once per session, a later change does not reach the running session`() = runTest {
        // Der Schalter kommt als feste Liste in die Sitzung (livePids bekommt keinen Fluss): eine Aenderung waehrend der Fahrt wirkt ab
        // der naechsten Verbindung. Gezaehlt wird, wie oft die Pipeline den Fluss abonniert — ein Lesen je Zyklus waere hier 3.
        var reads = 0
        val alarms = MutableStateFlow(AlarmSettings(voltageEnabled = true))
        val prefs = fakeAppPreferences()
        every { prefs.alarmSettings } returns flow { reads++; emit(alarms.value) }
        val repo = makeRepo(connected = true, supported = setOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id))
        val slowSlot = slot<List<PidDefinition>>()
        every { repo.livePids(any(), any(), any(), any(), capture(slowSlot), any()) } returns flow {
            emit(listOf(rpm))
            alarms.value = AlarmSettings(voltageEnabled = false)   // der Nutzer schaltet den Alarm waehrend der Sitzung aus
            emit(listOf(rpm))
            emit(listOf(rpm))
        }

        makePipeline(repo, prefs = prefs).sessionEvents(backgroundScope).take(3).toList()

        assertEquals(1, reads)
        assertEquals(listOf(PidRegistry.CONTROL_MODULE_VOLTAGE), slowSlot.captured)
        verify(exactly = 1) { repo.livePids(any(), any(), any(), any(), any(), any()) }
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
            prefs = fakeAppPreferences(logOnlyDtcSessions = true),
        ).sessionEvents(backgroundScope).take(1).toList()

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
        every { repo.livePids(any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(rpm))

        makePipeline(repo, prefs = fakeAppPreferences(obdWatchdogTimeoutSeconds = 45))
            .sessionEvents(backgroundScope).take(1).toList()

        verify {
            repo.livePids(
                pids = any(),
                pollIntervalMs = any(),
                watchdogTimeoutMs = 45_000L,
                timeSource = any(),
                slowPids = any(),
                slowEveryCycles = any(),
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

            val events = makePipeline(repo, logRepo = logRepo).sessionEvents(backgroundScope).take(1).toList()

            assertEquals(listOf(SessionEvent.ConnectionLost()), events)
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
            backgroundScope.launch { makePipeline(repo).sessionEvents(this).toList(events) }

            testScheduler.advanceUntilIdle()

            assertTrue("kein Reconnect nach Nutzer-Trennen: $events", events.none { it == SessionEvent.ConnectionLost() })
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
            backgroundScope.launch { makePipeline(repo).sessionEvents(this).toList(events) }

            testScheduler.advanceUntilIdle()

            assertTrue("kein Reconnect nach Nutzer-Trennen: $events", events.none { it == SessionEvent.ConnectionLost() })
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

            val event = makePipeline(repo).sessionEvents(backgroundScope).first { it == SessionEvent.ConnectionLost() }

            assertEquals(SessionEvent.ConnectionLost(), event)
        }

    // --- Eingefrorene Werte (Fahrten 52 und 60 des Nutzers, 2026-10-20): das Steuergeraet antwortet weiter, aber mit demselben Wert ---

    private val frozenClock = TestTimeSource()

    private fun frozenCycle(rpmValue: Float = 1612f) = listOf(
        SensorValue(PidRegistry.RPM, rpmValue, 1000L),
        SensorValue(PidRegistry.COOLANT_TEMP, 79f, 1000L),
        SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 13.7f, 1000L),
    )

    /** [n] Zyklen im Abstand von 1,5 s der [frozenClock] (der Takt der Fahrten), danach Stille. */
    private fun frozenStream(n: Int, rpmOf: (Int) -> Float = { 1612f }) = flow {
        repeat(n) { i -> frozenClock += 1_500.milliseconds; emit(frozenCycle(rpmOf(i))) }
        awaitCancellation()
    }

    private fun frozenPipeline(
        repo: ObdRepository,
        logRepo: AppLogRepository = mockk(relaxed = true),
        watchdogSeconds: Int = 20,
        sessionRepo: SessionRepository = makeSessionRepo(),
        gps: GpsSpeedSource = makeGps(),
        useGpsSpeed: Boolean = false,
    ) = makePipeline(
        repo,
        sessionRepo = sessionRepo,
        prefs = fakeAppPreferences(obdWatchdogTimeoutSeconds = watchdogSeconds, useGpsSpeed = useGpsSpeed),
        gps = gps,
        logRepo = logRepo,
    ).also { it.frozenTimeSource = frozenClock }

    private fun frozenRepo(connected: MutableStateFlow<Boolean>? = null): ObdRepository = makeRepo(connected = true).also { repo ->
        if (connected != null) every { repo.isConnectedFlow } returns connected
        coEvery { repo.dropFrozenLink() } answers { connected?.value = false; "raw: 010C=41 0C 19 31; ATRV 13.8V" }
    }

    @Test
    fun `values that stay frozen make the pipeline reconnect and write why into the log`() = runTest {
        val repo = frozenRepo()
        every { repo.livePids(any()) } returns frozenStream(25)
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        val events = collect(frozenPipeline(repo, logRepo))
        runCurrent()

        assertEquals("Zyklus 1 bis 19 laufen durch, der 20. ist der eingefrorene", 19, events.count { it is SessionEvent.Values })
        assertTrue("$events", events.contains(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES)))
        coVerify(exactly = 1) { repo.dropFrozenLink() }
        coVerify {
            logRepo.log(LogLevel.WARN, match { it.contains("values frozen") && it.contains("20 cycles") && it.contains("28 s") && it.contains("ATRV 13.8V") })
        }
    }

    @Test
    fun `frozen again after the reconnect, the values are held back and nothing reconnects in a loop`() = runTest {
        val connected = MutableStateFlow(true)
        val repo = frozenRepo(connected)
        every { repo.livePids(any()) } returnsMany listOf(frozenStream(25), frozenStream(40))

        val events = collect(frozenPipeline(repo))
        runCurrent()
        assertTrue("$events", events.contains(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES)))

        connected.value = true                          // das Neuverbinden gelingt, es ist dasselbe: wieder eingefroren
        runCurrent()

        coVerify(exactly = 1) { repo.dropFrozenLink() }
        assertEquals("19 + 19: die gehaltenen Zyklen erreichen die Oberflaeche nicht", 38, events.count { it is SessionEvent.Values })
        // ohne Zyklen zeigt die Oberflaeche nach der Frist "Keine aktuellen Werte"
        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        assertTrue("$events", events.contains(SessionEvent.Stale))
    }

    @Test
    fun `held values come back as soon as the vehicle moves again`() = runTest {
        val connected = MutableStateFlow(true)
        val repo = frozenRepo(connected)
        val second = flow {
            repeat(25) { frozenClock += 1_500.milliseconds; emit(frozenCycle()) }        // wieder eingefroren: gehalten
            frozenClock += 1_500.milliseconds; emit(frozenCycle(rpmValue = 2400f))       // das Steuergeraet lebt wieder
            awaitCancellation()
        }
        every { repo.livePids(any()) } returnsMany listOf(frozenStream(25), second)

        val events = collect(frozenPipeline(repo))
        runCurrent()
        connected.value = true
        runCurrent()

        val values = events.filterIsInstance<SessionEvent.Values>()
        assertEquals("19 + 19 + der lebendige Zyklus", 39, values.size)
        assertEquals(2400f, values.last().values.first { it.pid.id == PidRegistry.RPM.id }.value)
    }

    @Test
    fun `values that keep changing never reconnect`() = runTest {
        val repo = frozenRepo()
        every { repo.livePids(any()) } returns frozenStream(100) { 1500f + it }

        val events = collect(frozenPipeline(repo))
        runCurrent()

        assertEquals(100, events.count { it is SessionEvent.Values })
        assertTrue(events.none { it is SessionEvent.ConnectionLost })
        coVerify(exactly = 0) { repo.dropFrozenLink() }
    }

    @Test
    fun `a stopped engine repeats its values as long as it likes without a reconnect`() = runTest {
        val repo = frozenRepo()
        every { repo.livePids(any()) } returns frozenStream(60) { 0f }

        val events = collect(frozenPipeline(repo))
        runCurrent()

        assertEquals(60, events.count { it is SessionEvent.Values })
        coVerify(exactly = 0) { repo.dropFrozenLink() }
    }

    @Test
    fun `the watchdog setting is the time limit - 60 seconds need 60 seconds of frozen values`() = runTest {
        val repo = frozenRepo()
        // alle Parameter offen: mit der nicht voreingestellten Watchdog-Zeit passt `livePids(any())` nicht (s. der Test der Watchdog-Zeit oben)
        every { repo.livePids(any(), any(), any(), any(), any(), any()) } returns frozenStream(40)   // 40 Zyklen = 58,5 s zwischen erstem und letztem
        val events = collect(frozenPipeline(repo, watchdogSeconds = 60))
        runCurrent()
        assertEquals("alle 40 Zyklen laufen durch", 40, events.count { it is SessionEvent.Values })
        coVerify(exactly = 0) { repo.dropFrozenLink() }
    }

    @Test
    fun `the 60 second limit is reached with the 41st cycle`() = runTest {
        val repo = frozenRepo()
        every { repo.livePids(any(), any(), any(), any(), any(), any()) } returns frozenStream(41)
        val events = collect(frozenPipeline(repo, watchdogSeconds = 60))
        runCurrent()
        assertEquals("40 Zyklen laufen durch, der 41. bei genau 60 s ist der eingefrorene", 40, events.count { it is SessionEvent.Values })
        coVerify(exactly = 1) { repo.dropFrozenLink() }
    }

    @Test
    fun `a frozen link is still reported when isConnectedFlow flips synchronously (reentrancy race)`() =
        runTest(UnconfinedTestDispatcher()) {
            val connected = MutableStateFlow(true)
            val repo = frozenRepo(connected)
            every { repo.livePids(any()) } returns frozenStream(25)

            val event = frozenPipeline(repo).sessionEvents(backgroundScope).first { it is SessionEvent.ConnectionLost }

            assertEquals(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES), event)
        }

    @Test
    fun `a link that dies while the frozen link is being dropped still asks for the reconnect`() = runTest {
        // dropFrozenLink() fragt den Adapter (ATRV) und braucht Zeit; stirbt die Verbindung dabei, bricht flatMapLatest die Sitzung ab —
        // das Neuverbinden darf daran nicht scheitern (NonCancellable, wie beim Watchdog des Repositories)
        val connected = MutableStateFlow(true)
        val repo = frozenRepo(connected)
        coEvery { repo.dropFrozenLink() } coAnswers { delay(200); connected.value = false; "details" }
        every { repo.livePids(any()) } returns frozenStream(25)

        val events = collect(frozenPipeline(repo))
        runCurrent()                                     // der 20. Zyklus beginnt das Trennen (200 ms)
        connected.value = false                          // die Verbindung stirbt waehrenddessen
        advanceTimeBy(300); runCurrent()

        assertTrue("$events", events.contains(SessionEvent.ConnectionLost(ConnectionLostCause.FROZEN_VALUES)))
    }

    @Test
    fun `a frozen link the user disconnected on purpose is not reconnected`() = runTest {
        val repo = frozenRepo()
        val userDisconnects = MutableStateFlow(0)
        every { repo.userDisconnectCount } returns userDisconnects
        every { repo.livePids(any()) } returns flow {
            userDisconnects.value = 1                   // der Nutzer trennt waehrend der Sitzung
            repeat(25) { frozenClock += 1_500.milliseconds; emit(frozenCycle()) }
            awaitCancellation()
        }
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        val events = collect(frozenPipeline(repo, logRepo))
        runCurrent()

        assertTrue("$events", events.none { it is SessionEvent.ConnectionLost })
        coVerify(exactly = 0) { logRepo.log(LogLevel.WARN, match { it.contains("reconnecting") }) }
    }

    // --- Sperre bei eingefrorenen Werten: Ereignis, Log, Marker in der Fahrt, GPS-Geschwindigkeit (Spezifikation 2026-10-24) ---

    private class HoldRun(
        val events: MutableList<SessionEvent>,
        val logRepo: AppLogRepository,
        val rows: MutableList<List<SensorValue>>,
        val gps: MutableStateFlow<Float?>,
        val connected: MutableStateFlow<Boolean>,
        val moving: MutableList<Boolean>,
    )

    /** Zwei Sitzungen: die erste friert ein und wird neu verbunden, die zweite ([secondStream]) ist wieder eingefroren — die Sperre. */
    private fun TestScope.holdRun(secondStream: Flow<List<SensorValue>> = frozenStream(40), gpsKmh: Float? = 42f): HoldRun {
        val connected = MutableStateFlow(true)
        val repo = frozenRepo(connected)
        every { repo.livePids(any()) } returnsMany listOf(frozenStream(25), secondStream)
        val moving = mutableListOf<Boolean>()
        every { repo.setMoving(any()) } answers { moving.add(firstArg()) }
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val rows = mutableListOf<List<SensorValue>>()
        val sessionRepo = makeSessionRepo().also { coEvery { it.writeReadings(7L, capture(rows)) } just Runs }
        val gps = MutableStateFlow(gpsKmh)
        val gpsSource: GpsSpeedSource = mockk { every { speedKmh } returns gps }
        val events = collect(frozenPipeline(repo, logRepo, sessionRepo = sessionRepo, gps = gpsSource, useGpsSpeed = true))
        runCurrent()
        connected.value = true
        runCurrent()
        return HoldRun(events, logRepo, rows, gps, connected, moving)
    }

    private fun List<List<SensorValue>>.stateRows() = flatten().filter { it.pid.id == DerivedPids.VALUES_STATE_ID }

    private fun List<List<SensorValue>>.gpsOnlyRows() =
        filter { it.size == 1 && it[0].source == ValueSource.GPS && it[0].pid.id == PidRegistry.SPEED.id }

    @Test
    fun `the start of a hold is reported once, right when the first values are held back - not after the stale limit`() = runTest {
        val run = holdRun()

        assertEquals("ein Ereignis je Sperre, nicht je gehaltenem Zyklus", 1, run.events.count { it == SessionEvent.Frozen })
        assertEquals("zuletzt gemeldet, direkt hinter dem letzten durchgelassenen Zyklus", SessionEvent.Frozen, run.events.last())
        assertEquals(38, run.events.count { it is SessionEvent.Values })
        assertTrue("die Anzeige muss keine 10 s warten: $run", run.events.none { it == SessionEvent.Stale })
    }

    private fun AppLogRepository.messages(): List<String> {
        val messages = mutableListOf<String>()
        coVerify(atLeast = 0) { log(any(), capture(messages)) }
        return messages
    }

    @Test
    fun `the stale limit during a hold changes nothing - no second event, no log line, no marker`() = runTest {
        val run = holdRun()
        val markedBefore = run.rows.stateRows().size
        val loggedBefore = run.logRepo.messages()

        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()

        assertTrue("Stale kommt wie bisher", run.events.contains(SessionEvent.Stale))
        assertEquals(1, run.events.count { it == SessionEvent.Frozen })
        assertEquals("kein weiterer Marker", markedBefore, run.rows.stateRows().size)
        assertEquals("keine weitere Log-Zeile", loggedBefore, run.logRepo.messages())
    }

    @Test
    fun `a hold writes one log line with the held cycle, never one per cycle`() = runTest {
        val run = holdRun()

        coVerify(exactly = 1) {
            run.logRepo.log(
                LogLevel.WARN,
                match { it.contains("holding them back") && it.contains("010C=1612") && it.contains("0105=79") && it.contains("0142=13.7") },
            )
        }
    }

    @Test
    fun `a hold writes exactly one marker row into the trip - the state frozen with the time of the held cycle`() = runTest {
        val run = holdRun()

        val markers = run.rows.stateRows()
        assertEquals(1, markers.size)
        assertEquals(1f, markers.single().value, 0f)
        assertEquals(ValueSource.DERIVED, markers.single().source)
        assertEquals("die Zeit des Zyklus, der die Sperre ausloeste", 1000L, markers.single().timestamp)
    }

    @Test
    fun `while holding every held cycle records the GPS speed - and only that`() = runTest {
        val run = holdRun()

        val gpsRows = run.rows.gpsOnlyRows()
        assertEquals("Zyklus 20 bis 40 der zweiten Sitzung sind gehalten", 21, gpsRows.size)
        assertTrue(gpsRows.all { it.single().value == 42f })
        assertTrue("kein Fahrzeugwert aus einem gehaltenen Zyklus: ${run.rows.flatten().filter { it.pid.id == PidRegistry.RPM.id }.size} Drehzahlzeilen", run.rows.flatten().count { it.pid.id == PidRegistry.RPM.id } == 19 + 19)
    }

    @Test
    fun `without a GPS fix a hold records no speed row`() = runTest {
        val run = holdRun(gpsKmh = null)

        assertEquals(0, run.rows.gpsOnlyRows().size)
        assertEquals("der Marker steht trotzdem", 1, run.rows.stateRows().size)
    }

    @Test
    fun `from the hold start on, a GPS update reaches the display as the speed alone`() = runTest {
        val run = holdRun()
        val values = run.events.count { it is SessionEvent.Values }

        run.gps.value = 60f; runCurrent()

        assertEquals(SessionEvent.GpsSpeed(60f), run.events.last())
        assertEquals("die eingefrorenen Werte erscheinen nicht neu als live", values, run.events.count { it is SessionEvent.Values })
    }

    @Test
    fun `a hold counts as standing for the drive detection, like stale`() = runTest {
        val run = holdRun()

        assertEquals("die Zyklen mit 42 km/h meldeten 'faehrt', die Sperre 'steht'", false, run.moving.last())
        assertTrue(run.moving.contains(true))
    }

    @Test
    fun `when the vehicle moves again the display and the recording resume, with one log line and one marker`() = runTest {
        val second = flow {
            repeat(25) { frozenClock += 1_500.milliseconds; emit(frozenCycle()) }        // wieder eingefroren: Sperre ab dem 20.
            frozenClock += 1_500.milliseconds; emit(frozenCycle(rpmValue = 2400f))       // das Steuergeraet lebt wieder
            awaitCancellation()
        }
        val run = holdRun(secondStream = second)

        assertEquals(2400f, (run.events.last() as SessionEvent.Values).values.first { it.pid.id == PidRegistry.RPM.id }.value)
        assertEquals(listOf(1f, 0f), run.rows.stateRows().map { it.value })
        coVerify(exactly = 1) { run.logRepo.log(LogLevel.INFO, match { it.contains("moving again") && it.contains("showing and recording resume") }) }

        run.gps.value = 61f; runCurrent()
        assertTrue("danach wieder wie live: ${run.events.last()}", (run.events.last() as SessionEvent.Values).let { !it.isObdCycle })
    }

    @Test
    fun `a session that ends during a hold says so in the log`() = runTest {
        val run = holdRun()

        run.connected.value = false; runCurrent()

        coVerify(exactly = 1) { run.logRepo.log(LogLevel.INFO, match { it.contains("Session ended while values were frozen") }) }
        assertEquals("kein Marker fuer das Sitzungsende: die Fahrt endet", 1, run.rows.stateRows().size)
    }

    @Test
    fun `an adapter that falls silent is a stale state of its own - logged and marked once, ended by the next cycle`() = runTest {
        val repo = makeRepo(connected = true)
        val rows = mutableListOf<List<SensorValue>>()
        val sessionRepo = makeSessionRepo().also { coEvery { it.writeReadings(7L, capture(rows)) } just Runs }
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        every { repo.livePids(any()) } returns flow {
            emit(listOf(rpm)); delay(15_000); emit(listOf(rpm.copy(timestamp = 2000L))); awaitCancellation()
        }
        val events = collect(makePipeline(repo, sessionRepo = sessionRepo, logRepo = logRepo))

        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        assertTrue("$events", events.contains(SessionEvent.Stale))
        assertTrue("ohne Sperre gibt es kein Frozen: $events", events.none { it == SessionEvent.Frozen })
        assertEquals(listOf(2f), rows.stateRows().map { it.value })
        coVerify(exactly = 1) { logRepo.log(LogLevel.INFO, match { it.contains("No fresh values for 10 s") }) }

        advanceTimeBy(5_000); runCurrent()                  // t = 15 s: der naechste Zyklus
        assertEquals(listOf(2f, 0f), rows.stateRows().map { it.value })
        coVerify(exactly = 1) { logRepo.log(LogLevel.INFO, match { it.contains("Fresh values again") }) }
    }

    @Test
    fun `a new session starts with the values being there - the state of the one before does not leak into it`() = runTest {
        val connected = MutableStateFlow(true)
        val repo = makeRepo(connected = true).also { every { it.isConnectedFlow } returns connected }
        val rows = mutableListOf<List<SensorValue>>()
        val sessionRepo = makeSessionRepo().also { coEvery { it.writeReadings(7L, capture(rows)) } just Runs }
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        every { repo.livePids(any()) } returnsMany listOf(
            flow { emit(listOf(rpm)); awaitCancellation() },                                  // die erste Sitzung schweigt danach
            flow { emit(listOf(rpm.copy(timestamp = 5000L))); awaitCancellation() },          // die zweite beginnt mit einem frischen Zyklus
        )
        collect(makePipeline(repo, sessionRepo = sessionRepo, logRepo = logRepo))
        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        assertEquals("die erste Sitzung endet in 'keine aktuellen Werte'", listOf(2f), rows.stateRows().map { it.value })

        connected.value = false; runCurrent()
        connected.value = true; runCurrent()

        assertEquals("kein 'Werte da' fuer eine Sitzung, die ohnehin damit beginnt: ${rows.stateRows()}", listOf(2f), rows.stateRows().map { it.value })
        coVerify(exactly = 0) { logRepo.log(LogLevel.INFO, match { it.contains("Fresh values again") }) }
    }

    // --- "Keine aktuellen Werte" ohne Sperre: die GPS-Geschwindigkeit geht weiter in die Fahrt (Auftrag 2026-10-25, Punkt 3 der Spezifikation) ---

    private class SilentRun(
        val events: MutableList<SessionEvent>,
        val rows: MutableList<List<SensorValue>>,
        val gps: MutableStateFlow<Float?>,
    )

    /** Eine Sitzung, deren Adapter nach dem ersten Zyklus schweigt (ein zweiter Zyklus erst nach [resumeAfterMs], falls gesetzt), mit GPS und aufgezeichneter Fahrt. */
    private fun TestScope.silentRun(gpsKmh: Float? = null, resumeAfterMs: Long? = null): SilentRun {
        val repo = makeRepo(connected = true)
        val rows = mutableListOf<List<SensorValue>>()
        val sessionRepo = makeSessionRepo().also { coEvery { it.writeReadings(7L, capture(rows)) } just Runs }
        every { repo.livePids(any()) } returns flow {
            emit(listOf(rpm))
            if (resumeAfterMs != null) { delay(resumeAfterMs); emit(listOf(rpm.copy(timestamp = 2000L))) }
            awaitCancellation()
        }
        val gps = MutableStateFlow(gpsKmh)
        val gpsSource: GpsSpeedSource = mockk { every { speedKmh } returns gps }
        val events = collect(makePipeline(repo, sessionRepo = sessionRepo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gpsSource))
        runCurrent()
        return SilentRun(events, rows, gps)
    }

    @Test
    fun `while no fresh values come every GPS change is recorded as a speed row - and only that`() = runTest {
        val run = silentRun()
        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        val before = run.rows.size

        run.gps.value = 12f; runCurrent()
        run.gps.value = 18.5f; runCurrent()

        val written = run.rows.drop(before)
        assertEquals("je Aenderung eine Zeile: $written", 2, written.size)
        assertEquals(listOf(12f, 18.5f), written.map { it.single().value })
        assertTrue("Quelle GPS, Geschwindigkeits-PID, kein Fahrzeugwert", written.all { it.single().source == ValueSource.GPS && it.single().pid.id == PidRegistry.SPEED.id })
        assertEquals("die Anzeige bekommt dieselben Werte weiter", listOf<SessionEvent>(SessionEvent.GpsSpeed(12f), SessionEvent.GpsSpeed(18.5f)), run.events.takeLast(2))
    }

    @Test
    fun `before the stale limit a GPS change between two cycles is not recorded - the recording stays at one row per cycle`() = runTest {
        val run = silentRun()
        val before = run.rows.size

        run.gps.value = 12f; runCurrent()

        assertEquals("noch ist es ein normaler Zwischenwert der Anzeige: ${run.rows.drop(before)}", before, run.rows.size)
    }

    @Test
    fun `without a usable GPS value a silent adapter records no speed row`() = runTest {
        val run = silentRun()
        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        val before = run.rows.size

        run.gps.value = Float.NaN; runCurrent()
        run.gps.value = -3f; runCurrent()
        run.gps.value = null; runCurrent()

        assertEquals("nichts Unbelegtes in der Fahrt: ${run.rows.drop(before)}", before, run.rows.size)
    }

    @Test
    fun `a fresh cycle ends the speed-only recording - the GPS rides along with the cycle again`() = runTest {
        val run = silentRun(resumeAfterMs = 15_000)
        advanceTimeBy(15_001); runCurrent()                 // veraltet bei 10 s, frischer Zyklus bei 15 s
        val before = run.rows.size

        run.gps.value = 33f; runCurrent()

        assertEquals("zwischen zwei Zyklen schreibt der GPS-Wert wieder nichts: ${run.rows.drop(before)}", before, run.rows.size)
    }

    @Test
    fun `during a hold a GPS change between two cycles writes no second speed row - the held cycles already record it`() = runTest {
        val run = holdRun()
        val before = run.rows.gpsOnlyRows().size

        run.gps.value = 60f; runCurrent()

        assertEquals("sonst stuende die Geschwindigkeit doppelt in der Fahrt", before, run.rows.gpsOnlyRows().size)
    }

    @Test
    fun `values that never go stale write no state marker and no state log line`() = runTest {
        val repo = makeRepo(connected = true)
        val rows = mutableListOf<List<SensorValue>>()
        val sessionRepo = makeSessionRepo().also { coEvery { it.writeReadings(7L, capture(rows)) } just Runs }
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        every { repo.livePids(any()) } returns flow {
            repeat(5) { emit(listOf(rpm.copy(timestamp = 1000L + it))); delay(2_000) }
            awaitCancellation()
        }
        collect(makePipeline(repo, sessionRepo = sessionRepo, logRepo = logRepo))

        advanceTimeBy(9_000); runCurrent()

        assertEquals(0, rows.stateRows().size)
        coVerify(exactly = 0) { logRepo.log(any(), match { it.contains("fresh values", ignoreCase = true) || it.contains("frozen") }) }
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

        makePipeline(repo).sessionEvents(backgroundScope).take(speeds.size).toList()

        assertEquals(listOf(false, false, true, true, false, false), calls)
    }

    // --- B29: values nobody refreshes are stale ---

    private fun TestScope.collect(pipeline: ReadingsPipeline): MutableList<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { pipeline.sessionEvents(backgroundScope).toList(events) }
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
        val events = collect(makePipeline(repo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gpsSource))
        runCurrent()
        gps.value = 10f; runCurrent()
        assertEquals("solange sie frisch sind, ergaenzt GPS sie: $events", 2, events.count { it is SessionEvent.Values })

        advanceTimeBy(OBD_STALE_AFTER_MS + 1); runCurrent()
        val before = events.size
        gps.value = 20f; runCurrent()

        assertTrue("veraltete Werte duerfen nicht mit neuem GPS 'live' erscheinen: $events", events.drop(before).none { it is SessionEvent.Values })
        assertEquals("die GPS-Geschwindigkeit haengt nicht am Adapter: sie geht allein an die Anzeige", listOf<SessionEvent>(SessionEvent.GpsSpeed(20f)), events.drop(before))
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
        val events = collect(makePipeline(repo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gpsSource))
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
        val events = collect(makePipeline(repo, prefs = fakeAppPreferences(useGpsSpeed = true), gps = gpsSource))
        runCurrent()
        assertTrue("erste Verbindung liefert: $events", events.any { it is SessionEvent.Values })

        isConnected.value = false; runCurrent()
        isConnected.value = true; runCurrent()              // new session, no cycle yet
        events.clear()
        gps.value = 25f; runCurrent()

        assertTrue("alte Werte erscheinen nicht als live: $events", events.none { it is SessionEvent.Values })
    }

    // --- A9: a fault-code read without an answer is logged with the adapter's own words ---

    @Test
    fun `a fault-code read that gets no answer is written to the app log with the reason`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } throws ObdException.UnknownResponse("WHAT IS THIS")
        val logRepo: AppLogRepository = mockk(relaxed = true)

        makePipeline(repo, logRepo = logRepo).sessionEvents(backgroundScope).take(1).toList()

        coVerify {
            logRepo.log(LogLevel.WARN, match { it.contains("Fault-code read failed") && it.contains("WHAT IS THIS") })
        }
    }

    @Test
    fun `a fault-code read with NO DATA does not start a history entry and keeps the session running`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } throws ObdException.NoData("03")
        val dtcHistory: DtcHistoryRepository = mockk(relaxed = true)

        val events = makePipeline(repo, dtcHistory = dtcHistory).sessionEvents(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify(exactly = 0) { dtcHistory.record(any(), any()) }
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
            .sessionEvents(backgroundScope).take(3).toList()

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
        coEvery { sessionRepo.startSession(any(), any()) } throws IllegalStateException("database is locked")
        val logRepo: AppLogRepository = mockk(relaxed = true)

        val events = makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo, logRepo = logRepo)
            .sessionEvents(backgroundScope).take(1).toList()

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

            val event = makePipeline(repo, logRepo = logRepo).sessionEvents(backgroundScope)
                .first { it == SessionEvent.ConnectionLost() }

            assertEquals(SessionEvent.ConnectionLost(), event)
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

            makePipeline(repo, sessionRepo = sessionRepo).sessionEvents(backgroundScope)
                .first { it == SessionEvent.ConnectionLost() }

            coVerify(exactly = 0) { sessionRepo.startSession(any(), any()) }
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

            makePipeline(repo, logRepo = logRepo).sessionEvents(backgroundScope)
                .first { it == SessionEvent.ConnectionLost() }
            testScheduler.advanceUntilIdle()

            assertTrue("the WARN line about the dropped link must be persisted", written)
        }

    @Test
    fun `a fault-code read that fails while the link stays up keeps the session running`() = runTest {
        val repo = makeRepo(connected = true)
        // Zeitueberschreitung, aber der Link steht noch (isConnectedFlow bleibt true): kein Grund,
        // die Sitzung zu beenden oder neu zu verbinden.
        coEvery { repo.readDtcs() } throws ObdException.Timeout("03")

        val events = makePipeline(repo).sessionEvents(backgroundScope).take(1).toList()

        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
    }

    // --- Test 16: the local fault history ---

    @Test
    fun `the DTCs read at connect are recorded in the fault history`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", false), DtcEntry("P0420", true))
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns dtcs
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(repo, dtcHistory = history).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { history.record(dtcs, any()) }
    }

    @Test
    fun `the active DTCs are written to the app log by code, pending ones marked`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns listOf(DtcEntry("P0301", false), DtcEntry("P0420", true))
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        makePipeline(repo, logRepo = logRepo).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { logRepo.log(LogLevel.WARN, "Active DTCs on connect: P0301, P0420 (pending)") }
    }

    @Test
    fun `an empty reading is passed on too - the repository ignores it`() = runTest {
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(makeRepo(connected = true), dtcHistory = history).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { history.record(emptyList(), any()) }
    }

    @Test
    fun `a failed DTC read records nothing`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } throws IllegalStateException("ELM327 timeout")
        val history = mockk<DtcHistoryRepository>(relaxed = true)

        makePipeline(repo, dtcHistory = history).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 0) { history.record(any(), any()) }
    }

    @Test
    fun `a throwing fault history does not stop the session`() = runTest {
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns listOf(DtcEntry("P0301", false))
        val history = mockk<DtcHistoryRepository> {
            coEvery { record(any(), any()) } throws IllegalStateException("history exploded")
        }
        val sessionRepo = makeSessionRepo()

        val events = makePipeline(repo, sessionRepo = sessionRepo, dtcHistory = history)
            .sessionEvents(backgroundScope).take(1).toList()

        // The history is a side record: whatever goes wrong there must not cost the user the live session.
        assertEquals(listOf(SessionEvent.Values(listOf(rpm), gpsSpeedKmh = null)), events)
        coVerify { sessionRepo.writeReadings(7L, listOf(rpm)) }
    }

    // --- Test 17: the fault codes of a trip ---
    // Die Pipeline liest die Codes beim Verbinden ohnehin; dieselbe Antwort geht an die Fahrt (drei Zustaende: Liste, leer, nicht erfasst).

    @Test
    fun `the fault codes read at connect go to the trip as they were read`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", false), DtcEntry("P0420", true))
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns dtcs
        val sessionRepo = makeSessionRepo()

        makePipeline(repo, sessionRepo = sessionRepo).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { sessionRepo.startSession("VIN", dtcs) }
    }

    @Test
    fun `a read that found no codes goes to the trip as an empty list, not as not recorded`() = runTest {
        val sessionRepo = makeSessionRepo() // makeRepo: readDtcs() liefert emptyList()

        makePipeline(makeRepo(connected = true), sessionRepo = sessionRepo).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { sessionRepo.startSession("VIN", emptyList()) }
        coVerify(exactly = 0) { sessionRepo.startSession(any(), null) }
    }

    @Test
    fun `a failed read goes to the trip as not recorded, and the trip is still recorded`() = runTest {
        val repo = makeRepo(connected = true)
        // Keine positive Antwort (NO DATA): das Fahrzeug hat nichts gesagt — "nicht erfasst", kein "keine Fehler".
        coEvery { repo.readDtcs() } throws ObdException.NoData("03")
        val sessionRepo = makeSessionRepo()

        makePipeline(repo, sessionRepo = sessionRepo).sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { sessionRepo.startSession("VIN", null) }
    }

    @Test
    fun `with only trips with DTCs on, the trip that is recorded carries its codes`() = runTest {
        val dtcs = listOf(DtcEntry("P0171", true))
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcs() } returns dtcs
        val sessionRepo = makeSessionRepo()

        makePipeline(repo, sessionRepo = sessionRepo, prefs = fakeAppPreferences(logOnlyDtcSessions = true))
            .sessionEvents(backgroundScope).take(1).toList()

        coVerify(exactly = 1) { sessionRepo.startSession("VIN", dtcs) }
    }

    // --- Test 18: die Fehlerwache (Fehlercodes unterwegs) ---
    // Das Auslesen beim Verbinden meldet die Basis (isBaseline = true, auch bei einem Fehlschlag als null); danach liest die Wache jede Minute
    // weich nach (readDtcsOrSkip: ein Zeitlimit ueberspringt, trennt nie) und meldet jeden Erfolg. Eine Aenderung wird nach 10 s nachgeprueft.

    private val watchMs = ReadingsPipeline.FAULT_WATCH_INTERVAL_MS
    private val recheckMs = ReadingsPipeline.FAULT_RECHECK_MS
    private val p0301 = DtcEntry("P0301", false)
    private val p0420 = DtcEntry("P0420", false)

    /** Eine Sitzung, die offen bleibt: nur dann lebt die Wache ueber die ersten 60 s hinaus (ein beendeter `livePids`-Fluss beendet die Sitzung). */
    private fun openSessionRepo(): ObdRepository = makeRepo(connected = true).also { repo ->
        every { repo.livePids(any()) } returns flow { emit(listOf(rpm)); awaitCancellation() }
    }

    /** Sammelt ALLE Ereignisse im Hintergrund, auch die Fehlercodes — [sessionEvents] filtert sie weg. */
    private fun TestScope.collectAll(pipeline: ReadingsPipeline): MutableList<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { pipeline.events(backgroundScope).toList(events) }
        return events
    }

    private fun List<SessionEvent>.faultCodes() = filterIsInstance<SessionEvent.FaultCodes>()

    /** Rueckt die virtuelle Zeit auf [timeMs] seit dem Start und fuehrt alles aus, was genau dann ansteht. */
    private fun TestScope.advanceTo(timeMs: Long) {
        advanceTimeBy(timeMs - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `the cadence is one minute, and ten seconds after a change`() {
        assertEquals(60_000L, ReadingsPipeline.FAULT_WATCH_INTERVAL_MS)
        assertEquals(10_000L, ReadingsPipeline.FAULT_RECHECK_MS)
    }

    @Test
    fun `the read at connect is reported once as the baseline`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns listOf(p0301)
        val events = collectAll(makePipeline(repo))

        advanceTo(1)
        assertEquals(listOf(SessionEvent.FaultCodes(listOf(p0301), isBaseline = true)), events.faultCodes())

        advanceTo(3 * watchMs)   // die Wache liest (null, nichts zu melden): die Basis kommt nicht noch einmal
        assertEquals(1, events.faultCodes().size)
    }

    @Test
    fun `a failed read at connect is reported as the baseline too - as null, never as an all-clear`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } throws ObdException.NoData("03")
        val events = collectAll(makePipeline(repo))

        advanceTo(1)

        assertEquals(listOf(SessionEvent.FaultCodes(null, isBaseline = true)), events.faultCodes())
    }

    @Test
    fun `the first periodic read comes after one minute, then every minute - each success is reported without the baseline flag`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns listOf(p0301)
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)   // wie die Basis: keine Aenderung, also jede volle Minute
        val events = collectAll(makePipeline(repo))

        advanceTo(watchMs - 1)
        coVerify(exactly = 0) { repo.readDtcsOrSkip() }
        advanceTo(watchMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs - 1)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs)
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }

        assertEquals(listOf(true, false, false), events.faultCodes().map { it.isBaseline })
        assertEquals(listOf(p0301), events.faultCodes().last().codes)
    }

    @Test
    fun `a result that differs from the previous one is checked again after ten seconds, an unchanged one after a minute`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns emptyList()                                    // Basis: nichts
        val both = listOf(p0301, p0420)
        coEvery { repo.readDtcsOrSkip() } returnsMany listOf(listOf(p0301), listOf(p0301), both, both)
        collectAll(makePipeline(repo))

        advanceTo(watchMs)                       // 60 s: P0301 gegenueber der Basis neu -> in 10 s wieder
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(watchMs + recheckMs - 1)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(watchMs + recheckMs)           // 70 s: unveraendert -> in einer Minute
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs + recheckMs - 1)
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs + recheckMs)       // 130 s: P0420 kommt hinzu -> in 10 s wieder
        coVerify(exactly = 3) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs + 2 * recheckMs - 1)
        coVerify(exactly = 3) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs + 2 * recheckMs)   // 140 s: unveraendert -> in einer Minute
        coVerify(exactly = 4) { repo.readDtcsOrSkip() }
        advanceTo(3 * watchMs + 2 * recheckMs - 1)
        coVerify(exactly = 4) { repo.readDtcsOrSkip() }
        advanceTo(3 * watchMs + 2 * recheckMs)
        coVerify(exactly = 5) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `a code that turns from pending to confirmed is a change too`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns listOf(DtcEntry("P0301", isPending = true))
        coEvery { repo.readDtcsOrSkip() } returns listOf(DtcEntry("P0301", isPending = false))
        collectAll(makePipeline(repo))

        advanceTo(watchMs)
        advanceTo(watchMs + recheckMs)

        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `without a baseline there is nothing to compare, so the first result is not re-checked early`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } throws ObdException.NoData("03")   // keine Basis
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)
        collectAll(makePipeline(repo))

        advanceTo(watchMs)
        advanceTo(watchMs + recheckMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs)
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `a read without an answer reports nothing, waits a full minute and forgets nothing`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns emptyList()
        val both = listOf(p0301, p0420)
        // 60 s: P0301 (neu, Nachpruefung in 10 s) · 70 s: keine Antwort · 130 s: P0301 + P0420 — neu gegenueber P0301, NICHT gegenueber "nichts"
        coEvery { repo.readDtcsOrSkip() } returnsMany listOf(listOf(p0301), null, both, both)
        val events = collectAll(makePipeline(repo))

        advanceTo(watchMs + recheckMs)
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
        assertEquals("null meldet nichts: Basis und P0301", 2, events.faultCodes().size)
        advanceTo(watchMs + recheckMs + watchMs - 1)
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }                     // nach einer leeren Antwort eine volle Minute
        advanceTo(2 * watchMs + recheckMs)
        coVerify(exactly = 3) { repo.readDtcsOrSkip() }
        assertEquals(both, events.faultCodes().last().codes)
        advanceTo(2 * watchMs + 2 * recheckMs - 1)
        coVerify(exactly = 3) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs + 2 * recheckMs)                              // die Aenderung wurde bemerkt: Nachpruefung nach 10 s
        coVerify(exactly = 4) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `with the switch off nothing is read - the switch is looked at on every round`() = runTest {
        val alarms = MutableStateFlow(AlarmSettings(faultsEnabled = false))
        val prefs = fakeAppPreferences()
        every { prefs.alarmSettings } returns alarms
        val repo = openSessionRepo()
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)
        val events = collectAll(makePipeline(repo, prefs = prefs))

        advanceTo(2 * watchMs)
        coVerify(exactly = 0) { repo.readDtcsOrSkip() }

        alarms.value = AlarmSettings(faultsEnabled = true)         // das Einschalten wirkt beim naechsten Durchlauf
        advanceTo(3 * watchMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        assertEquals(listOf(true, false), events.faultCodes().map { it.isBaseline })

        alarms.value = AlarmSettings(faultsEnabled = false)        // ... und das Ausschalten ebenso
        advanceTo(5 * watchMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `every successful periodic read goes into the fault history, a read without an answer does not`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcs() } returns emptyList()
        coEvery { repo.readDtcsOrSkip() } returnsMany listOf(listOf(p0301), null, listOf(p0301))
        val history = mockk<DtcHistoryRepository>(relaxed = true)
        collectAll(makePipeline(repo, dtcHistory = history))

        advanceTo(watchMs)
        coVerify(exactly = 1) { history.record(listOf(p0301), any()) }
        advanceTo(watchMs + recheckMs)                              // keine Antwort
        coVerify(exactly = 1) { history.record(listOf(p0301), any()) }
        advanceTo(2 * watchMs + recheckMs)
        coVerify(exactly = 2) { history.record(listOf(p0301), any()) }
    }

    @Test
    fun `a throwing fault history does not stop the watch`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)
        val history = mockk<DtcHistoryRepository> {
            coEvery { record(any(), any()) } throws IllegalStateException("history exploded")
        }
        val events = collectAll(makePipeline(repo, dtcHistory = history))

        advanceTo(2 * watchMs)

        // Die Historie ist Beiwerk: die Meldung geht trotzdem hinaus, und die naechste Minute wird gelesen.
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
        assertEquals(listOf(true, false, false), events.faultCodes().map { it.isBaseline })
        assertTrue("keine Fehlermeldung der Sitzung: $events", events.none { it is SessionEvent.Failed })
    }

    @Test
    fun `an unexpected failure of a periodic read does not end the session or the watch, and is logged`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcsOrSkip() } throws IllegalStateException("parser exploded") andThen listOf(p0301)
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = collectAll(makePipeline(repo, logRepo = logRepo))

        advanceTo(2 * watchMs)

        coVerify(exactly = 2) { repo.readDtcsOrSkip() }
        assertEquals(listOf(true, false), events.faultCodes().map { it.isBaseline })
        assertTrue("die Sitzung lebt: $events", events.none { it is SessionEvent.Failed })
        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("Periodic fault-code read failed") && it.contains("parser exploded") }) }
    }

    @Test
    fun `a lost link during a periodic read asks for a reconnect`() = runTest(UnconfinedTestDispatcher()) {
        val isConnected = MutableStateFlow(true)
        val repo = openSessionRepo()
        every { repo.isConnectedFlow } returns isConnected
        coEvery { repo.readDtcsOrSkip() } coAnswers {
            isConnected.value = false           // genau das tut ObdRepository.readDtcsOrSkip() (onLinkBroken), bevor es weiterwirft
            throw ObdException.ConnectionLost()
        }
        val logRepo: AppLogRepository = mockk(relaxed = true) {
            // Ein echter Suspendierungspunkt wie beim Room-Insert des App-Logs (s. die Reentrancy-Tests oben): die Sitzung wird gerade abgebrochen.
            coEvery { log(any(), any()) } coAnswers { delay(1) }
        }
        val events = collectAll(makePipeline(repo, logRepo = logRepo))

        advanceTo(watchMs)

        assertEquals("genau ein Reconnect-Ausloeser: $events", 1, events.count { it == SessionEvent.ConnectionLost() })
    }

    @Test
    fun `a periodic read that fails because the user disconnected does not ask for a reconnect`() = runTest(UnconfinedTestDispatcher()) {
        val isConnected = MutableStateFlow(true)
        val userDisconnects = MutableStateFlow(0)
        val repo = openSessionRepo()
        every { repo.isConnectedFlow } returns isConnected
        every { repo.userDisconnectCount } returns userDisconnects
        coEvery { repo.readDtcsOrSkip() } coAnswers {
            userDisconnects.value += 1          // disconnectByUser(): erst der Zaehler ...
            isConnected.value = false           // ... dann das Trennen
            throw ObdException.ConnectionLost()
        }
        val events = collectAll(makePipeline(repo))

        advanceTo(watchMs)

        assertTrue("kein Reconnect nach Nutzer-Trennen: $events", events.none { it == SessionEvent.ConnectionLost() })
        assertTrue("die Anzeige geht auf getrennt: $events", events.contains(SessionEvent.Idle))
    }

    @Test
    fun `a lost link on a connection that stayed up does not end the watch - no reconnect, no log line, it reads again next minute`() = runTest {
        // readDtcsOrSkip() scheitert mit ConnectionLost, doch isConnectedFlow bleibt true und der Zaehler der Nutzer-Trennungen steht:
        // die Verbindung wurde ersetzt (ein anderer Leser trennte die alte). Faellt die Sitzung dadurch nicht ohnehin zusammen (das
        // Zwischen-"getrennt" eines StateFlow kann verschluckt werden), liest die Wache ueber die neue Verbindung weiter — ein Ende
        // liesse den Rest der Sitzung ohne Fehlerwache.
        val repo = openSessionRepo()
        coEvery { repo.readDtcsOrSkip() } throws ObdException.ConnectionLost()
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val events = collectAll(makePipeline(repo, logRepo = logRepo))

        advanceTo(watchMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        advanceTo(2 * watchMs)

        assertTrue("kein Reconnect-Ausloeser fuer eine ersetzte Verbindung: $events", events.none { it == SessionEvent.ConnectionLost() })
        coVerify(exactly = 0) { logRepo.log(any(), match { it.contains("Periodic fault-code read") }) }
        coVerify(exactly = 2) { repo.readDtcsOrSkip() }               // die Wache lebt: sie liest in der naechsten Minute wieder
        assertTrue("die Sitzung lebt weiter: $events", events.none { it is SessionEvent.Failed || it == SessionEvent.Idle })
    }

    @Test
    fun `cancelling the session during a periodic read is no failure and writes no log line`() = runTest {
        val repo = openSessionRepo()
        coEvery { repo.readDtcsOrSkip() } coAnswers { awaitCancellation() }
        val logRepo = mockk<AppLogRepository>(relaxed = true)
        val pipeline = makePipeline(repo, logRepo = logRepo)
        val job = backgroundScope.launch { pipeline.events(backgroundScope).toList(mutableListOf()) }

        advanceTo(watchMs)                                             // das Nachlesen haengt
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        coVerify(exactly = 0) { logRepo.log(LogLevel.WARN, match { it.contains("Periodic fault-code read failed") }) }
    }

    @Test
    fun `the watch ends with the connection - nothing is read after the session is gone`() = runTest {
        val isConnected = MutableStateFlow(true)
        val repo = openSessionRepo()
        every { repo.isConnectedFlow } returns isConnected
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)
        collectAll(makePipeline(repo))

        advanceTo(watchMs)
        coVerify(exactly = 1) { repo.readDtcsOrSkip() }

        isConnected.value = false
        advanceTo(10 * watchMs)

        coVerify(exactly = 1) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `the watch ends with the session even while the connection stays up`() = runTest {
        // makeRepo: livePids liefert einen Zyklus und endet — die Quelle der Sitzung ist zu, die Verbindung steht weiter.
        val repo = makeRepo(connected = true)
        coEvery { repo.readDtcsOrSkip() } returns listOf(p0301)
        collectAll(makePipeline(repo))

        advanceTo(10 * watchMs)

        coVerify(exactly = 0) { repo.readDtcsOrSkip() }
    }

    @Test
    fun `a new session starts without the fault set of the old one - a late first read is not compared with it`() =
        runTest {
            val isConnected = MutableStateFlow(true)
            val repo = openSessionRepo()
            every { repo.isConnectedFlow } returns isConnected
            // Sitzung 1: Basis P0301. Sitzung 2: das Auslesen beim Verbinden dauert laenger als die erste Minute der Wache.
            var connects = 0
            coEvery { repo.readDtcs() } coAnswers { if (connects++ == 0) listOf(p0301) else { delay(90_000); emptyList() } }
            coEvery { repo.readDtcsOrSkip() } returns listOf(p0420)
            collectAll(makePipeline(repo))
            advanceTo(1)

            isConnected.value = false
            advanceTo(10_000)
            isConnected.value = true
            advanceTo(10_000 + watchMs)          // Sitzung 2, erste Lesung der Wache — die Basis steht noch aus
            coVerify(exactly = 1) { repo.readDtcsOrSkip() }
            advanceTo(10_000 + watchMs + recheckMs)

            // Gegen den Stand der alten Sitzung (P0301) waere P0420 "neu" und die Nachpruefung schon nach 10 s dran.
            coVerify(exactly = 1) { repo.readDtcsOrSkip() }
        }
}
