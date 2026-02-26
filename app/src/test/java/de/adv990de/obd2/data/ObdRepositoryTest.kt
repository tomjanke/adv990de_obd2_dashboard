package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.FakeObdConnection
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdRepositoryTest {

    private fun makeRepo(factory: ConnectionFactory = mockk()): ObdRepository =
        ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())

    @Test
    fun `isMovingFlow starts as false`() = runTest {
        val repo = makeRepo()
        assertEquals(false, repo.isMovingFlow.value)
    }

    @Test
    fun `isMovingFlow resets to false on disconnect`() = runTest {
        val repo = makeRepo()
        repo.setMoving(true)
        assertEquals(true, repo.isMovingFlow.value)
        repo.disconnect()
        assertEquals(false, repo.isMovingFlow.value)
    }

    @Test
    fun `isConnectedFlow starts as false`() = runTest {
        val repo = makeRepo()
        assertEquals(false, repo.isConnectedFlow.value)
    }

    @Test
    fun `isConnectedFlow emits true after connect`() = runTest {
        val device = mockk<ScannedDevice>()
        val connection = mockk<ObdConnection>()
        val elm = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        coEvery { factory.create(device) } returns connection
        coEvery { connection.connect() } returns elm
        coEvery { elm.initialize() } just Runs
        every { connection.isConnected } returns true

        val repo = makeRepo(factory)
        repo.connect(device)

        assertEquals(true, repo.isConnectedFlow.value)
    }

    @Test
    fun `isConnectedFlow emits false after disconnect`() = runTest {
        val device = mockk<ScannedDevice>()
        val connection = mockk<ObdConnection>()
        val elm = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        coEvery { factory.create(device) } returns connection
        coEvery { connection.connect() } returns elm
        coEvery { elm.initialize() } just Runs
        every { connection.isConnected } returns true
        every { connection.close() } just Runs

        val repo = makeRepo(factory)
        repo.connect(device)
        repo.disconnect()

        assertEquals(false, repo.isConnectedFlow.value)
    }

    @Test
    fun `connect closes previous connection before opening new one`() = runTest {
        val device1 = mockk<ScannedDevice>()
        val device2 = mockk<ScannedDevice>()
        val conn1 = mockk<ObdConnection>()
        val conn2 = mockk<ObdConnection>()
        val elm1 = mockk<ElmController>()
        val elm2 = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        coEvery { factory.create(device1) } returns conn1
        coEvery { factory.create(device2) } returns conn2
        coEvery { conn1.connect() } returns elm1
        coEvery { conn2.connect() } returns elm2
        coEvery { elm1.initialize() } just Runs
        coEvery { elm2.initialize() } just Runs
        every { conn1.close() } just Runs

        val repo = makeRepo(factory)
        repo.connect(device1)
        repo.connect(device2)

        verifyOrder { conn1.close(); factory.create(device2) }
        assertEquals(true, repo.isConnectedFlow.value)
    }

    @Test
    fun `connect closes new connection when initialize throws`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection>()
        val elm = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        coEvery { factory.create(device) } returns conn
        coEvery { conn.connect() } returns elm
        coEvery { elm.initialize() } throws Exception("init failed")
        every { conn.close() } just Runs

        val repo = makeRepo(factory)
        try { repo.connect(device) } catch (_: Exception) {}

        verify { conn.close() }
    }

    @Test
    fun `reconnect closes first connection before opening second`() = runTest {
        val device = mockk<ScannedDevice>()
        // Separate mocks so verifyOrder unambiguously checks conn1.close() before the
        // second factory.create() call — using a single mock would make the ordering
        // assertion pass even if close() ran after the second create().
        val conn1 = mockk<ObdConnection>()
        val conn2 = mockk<ObdConnection>()
        val elm1  = mockk<ElmController>()
        val elm2  = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        coEvery { factory.create(device) } returnsMany listOf(conn1, conn2)
        coEvery { conn1.connect() } returns elm1
        coEvery { conn2.connect() } returns elm2
        coEvery { elm1.initialize() } just Runs
        coEvery { elm2.initialize() } just Runs
        every { conn1.isConnected } returns true
        every { conn1.close() } just Runs

        val repo = makeRepo(factory)
        repo.connect(device)   // sets lastConnectedDevice, uses conn1
        repo.reconnect()       // must close conn1, then call factory.create() → conn2

        coVerify(exactly = 2) { factory.create(device) }
        verifyOrder { conn1.close(); factory.create(device) }
        assertEquals(true, repo.isConnectedFlow.value)
    }

    @Test
    fun `reconnect throws IllegalStateException when no previous device`() = runTest {
        val repo = makeRepo()
        var threw = false
        try { repo.reconnect() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    // ── DTC read ─────────────────────────────────────────────────────────────

    @Test
    fun `readDtcs disconnects when sendCommand times out to prevent stream desync`() = runTest {
        val elmCtrl = mockk<ElmController>()
        // "03" times out — stale response may arrive during the "07" command if we continue
        coEvery { elmCtrl.sendCommand("03") } throws ObdException.Timeout("03")
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try { repo.readDtcs() } catch (_: ObdException.Timeout) { threw = true }

        assertTrue(threw)
        // Repository must have disconnected to flush the stream
        assertEquals(false, repo.isConnectedFlow.value)
    }

    // ── Freeze frame ─────────────────────────────────────────────────────────

    @Test
    fun `readFreezeFrame sends correct mode-02 command format and parses response`() = runTest {
        val elmCtrl = mockk<ElmController>()
        // Throw NoData for every PID except RPM (id "010C" → pidByte "0C" → command "020C00")
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("XX")
        coEvery { elmCtrl.sendCommand("02 0C 00") } returns "42 0C 1A F8"
        // Use a real PidParser so parseFreezeFrame actually processes the response.
        val repo = makeConnectedRepo(elmCtrl, pidParser = PidParser())

        val result = repo.readFreezeFrame()

        coVerify { elmCtrl.sendCommand("02 0C 00") }  // space-separated for clone compatibility
        assertTrue("RPM" in result)
        // Value should use decimal precision (%.1f), not integer truncation.
        // RPM formula: (26*256 + 248) / 4 = 1726.0 → "1726.0 rpm"
        assertTrue("RPM value should contain decimal point: ${result["RPM"]}", result["RPM"]!!.contains("."))
    }

    // ── BusInit escalation ────────────────────────────────────────────────────

    private suspend fun makeConnectedRepo(
        elmCtrl: ElmController,
        pidParser: PidParser = mockk(),
        device: ScannedDevice = mockk()
    ): ObdRepository {
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        // Discovery stubs: thrown NoData is caught silently → discovery returns null.
        // Registered after test-local stubs so these specific matchers win for the
        // three range commands while test's own coEvery { sendCommand(any()) } still
        // handles all other commands (MockK LIFO: last-registered wins).
        coEvery { elmCtrl.sendCommand("0100") } throws ObdException.NoData("0100")
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.NoData("0120")
        coEvery { elmCtrl.sendCommand("0140") } throws ObdException.NoData("0140")
        return ObdRepository(factory, pidParser, mockk<DtcParser>()).also { it.connect(device) }
    }

    @Test
    fun `livePids does not emit partial values when BusInit interrupts a cycle`() = runTest {
        // Scenario: first PID in each cycle succeeds, second throws BusInit.
        // Without the !busInitOccurred guard, the first PID's stale value would be emitted,
        // mixing fresh and un-refreshed data in the same update. The fix suppresses the cycle.
        val pidList = PidRegistry.defaults.take(2)
        val pid1 = pidList[0]
        val pid2 = pidList[1]
        val elmCtrl = mockk<ElmController>()
        val pidParser = mockk<PidParser> {
            every { parse(pid1, any()) } returns mockk()
        }
        coEvery { elmCtrl.sendCommand(pid1.id) } returns "41 00 00 00 00"
        coEvery { elmCtrl.sendCommand(pid2.id) } throws ObdException.BusInit()
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        var emissionCount = 0
        try {
            repo.livePids(pidList, pollIntervalMs = 0).collect { emissionCount++ }
        } catch (_: ObdException.ConnectionLost) {}

        // 5 BusInit cycles → ConnectionLost, but zero emissions because every cycle is partial
        assertEquals(0, emissionCount)
    }

    @Test
    fun `livePids escalates to ConnectionLost after 5 consecutive BusInit cycles`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.BusInit()
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try {
            repo.livePids(listOf(PidRegistry.defaults.first()), pollIntervalMs = 0).collect {}
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
    }

    @Test
    fun `livePids does not escalate when BusInit count stays below threshold`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val pidParser = mockk<PidParser> { every { parse(any(), any()) } returns mockk() }

        // 4 BusInit cycles (counter stays at 4), then a successful cycle → no ConnectionLost
        var cycleCount = 0
        coEvery { elmCtrl.sendCommand(any()) } answers {
            if (++cycleCount < 5) throw ObdException.BusInit() else "41 0C 1A 00"
        }
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        val result = repo.livePids(listOf(PidRegistry.defaults.first()), pollIntervalMs = 0).first()
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `livePids BusInit counter resets after a successful cycle`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val pidParser = mockk<PidParser> { every { parse(any(), any()) } returns mockk() }

        // Cycles 1-4: BusInit (counter=4), cycle 5: success (counter resets to 0),
        // cycles 6-10: BusInit (counter reaches 5 again → ConnectionLost).
        var cycleCount = 0
        coEvery { elmCtrl.sendCommand(any()) } answers {
            when {
                ++cycleCount <= 4 -> throw ObdException.BusInit()
                cycleCount == 5   -> "41 0C 1A 00"
                else              -> throw ObdException.BusInit()
            }
        }
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        var emissionCount = 0
        var threw = false
        try {
            repo.livePids(listOf(PidRegistry.defaults.first()), pollIntervalMs = 0)
                .collect { emissionCount++ }
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertEquals(1, emissionCount)  // one emission on cycle 5
        assertTrue(threw)               // ConnectionLost after 5 more BusInit cycles (6-10)
    }

    // ── PID Discovery ─────────────────────────────────────────────────────────

    @Test
    fun `supportedPidIds is null before connect`() = runTest {
        val repo = makeRepo()
        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `supportedPidIds is null after disconnect`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        // 0100 returns two known PIDs; 0120/0140 return NoData
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.NoData("0120")
        coEvery { elmCtrl.sendCommand("0140") } throws ObdException.NoData("0140")

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)
        assertNotNull(repo.supportedPidIds.value)

        repo.disconnect()
        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `supportedPidIds is null when all ranges return NO DATA`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val repo = makeConnectedRepo(elmCtrl)
        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `discoverSupportedPids decodes bitmap correctly for range 0100`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        // 0x00180000: bit 20 → PID 0x0C (RPM), bit 19 → PID 0x0D (Speed)
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.NoData("0120")
        coEvery { elmCtrl.sendCommand("0140") } throws ObdException.NoData("0140")

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        val supported = repo.supportedPidIds.value
        assertNotNull(supported)
        assertTrue("010C (RPM) should be in supported", "010C" in supported!!)
        assertTrue("010D (Speed) should be in supported", "010D" in supported)
    }

    @Test
    fun `discoverSupportedPids returns null when bitmap matches no known PIDs`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        // Only bit 0 set → PID 0x60, which is the continuation flag and not in PidRegistry
        coEvery { elmCtrl.sendCommand("0100") } throws ObdException.NoData("0100")
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.NoData("0120")
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 00 00 00 01"

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `discoverSupportedPids is resilient when one range throws Timeout`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        // 0100 returns RPM + Speed; 0120 times out; 0140 returns NoData
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.Timeout("0120")
        coEvery { elmCtrl.sendCommand("0140") } throws ObdException.NoData("0140")

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        val supported = repo.supportedPidIds.value
        assertNotNull("partial discovery result should not be null", supported)
        assertTrue("010C should be discovered", "010C" in supported!!)
        assertTrue("010D should be discovered", "010D" in supported)
    }

    // ── Integration tests with FakeObdConnection ──────────────────────────────
    //
    // These tests exercise the full connect → discoverSupportedPids lifecycle using a
    // real ElmController backed by in-process piped streams (FakeObdConnection).
    // No mocking of the serial protocol occurs; commands flow through the same code
    // path as on a real Bluetooth device.
    //
    // initialize() sends exactly 5 AT commands: ATZ, ATE0, ATL0, ATS0, ATSP0.
    // discoverSupportedPids() sends 3 range commands: 0100, 0120, 0140.
    // Total responses needed per connect(): 5 + 3 = 8.

    /**
     * Queues the 5 init responses + 3 discovery responses needed for one connect() call.
     *
     * Bitmap 0xBE3FA813 ("BE 3F A8 13") encodes PID support for 0x01–0x20:
     *   bit 27 → PID 0x05 (Coolant Temp, "0105")
     *   bit 20 → PID 0x0C (RPM, "010C")
     *   bit 19 → PID 0x0D (Speed, "010D")
     * among others. 0120 and 0140 return "NO DATA" so only the 0x01–0x20 range is decoded.
     */
    private fun FakeObdConnection.queueConnectResponses() {
        // 5 init commands (ATZ returns the ELM version string; others return "OK")
        queueResponses(
            "ELM327 v1.5",  // ATZ
            "OK",           // ATE0
            "OK",           // ATL0
            "OK",           // ATS0
            "OK"            // ATSP0
        )
        // 3 discovery commands
        queueResponses(
            "41 00 BE 3F A8 13",  // 0100 — bitmap encodes RPM, Speed, Coolant Temp, and others
            "NO DATA",            // 0120
            "NO DATA"             // 0140
        )
    }

    @Test
    fun `integration - connect and discoverSupportedPids via FakeObdConnection`() = runTest {
        val fake    = FakeObdConnection()
        val device  = mockk<ScannedDevice>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns fake }

        fake.queueConnectResponses()

        val repo = ObdRepository(factory, PidParser(), mockk<DtcParser>())
        repo.connect(device)

        assertTrue("isConnectedFlow should be true after connect", repo.isConnectedFlow.value)

        val supported = repo.supportedPidIds.value
        assertNotNull("supportedPidIds should not be null after successful discovery", supported)
        assertTrue("RPM (010C) should be in supported PIDs",       "010C" in supported!!)
        assertTrue("Speed (010D) should be in supported PIDs",     "010D" in supported)
        assertTrue("Coolant Temp (0105) should be in supported PIDs", "0105" in supported)
    }

    // ── setMoving SIMULATOR guard ─────────────────────────────────────────────

    @Test
    fun `setMoving is ignored when SIMULATOR device is connected`() = runTest {
        val simulatorDevice = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        val elmCtrl = mockk<ElmController>()
        val repo = makeConnectedRepo(elmCtrl, device = simulatorDevice)

        repo.setMoving(true)

        assertEquals(false, repo.isMovingFlow.value)
    }

    // ── readVin ───────────────────────────────────────────────────────────────

    @Test
    fun `readVin returns null when not connected`() = runTest {
        val factory = mockk<ConnectionFactory>()
        val repo = makeRepo(factory)
        assertNull(repo.readVin())
        verify(exactly = 0) { factory.create(any()) }
    }

    @Test
    fun `integration - disconnect clears state after FakeObdConnection connect`() = runTest {
        val fake    = FakeObdConnection()
        val device  = mockk<ScannedDevice>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns fake }

        fake.queueConnectResponses()

        val repo = ObdRepository(factory, PidParser(), mockk<DtcParser>())
        repo.connect(device)

        assertTrue("should be connected before disconnect", repo.isConnectedFlow.value)
        assertNotNull("supportedPidIds should be set before disconnect", repo.supportedPidIds.value)

        repo.disconnect()

        assertFalse("isConnectedFlow should be false after disconnect", repo.isConnectedFlow.value)
        assertNull("supportedPidIds should be null after disconnect", repo.supportedPidIds.value)
    }
}
