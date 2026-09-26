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
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// testTimeSource/currentTime (kotlinx-coroutines-test) are @ExperimentalCoroutinesApi.
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `readDtcs returns empty list when not connected`() = runTest {
        val factory = mockk<ConnectionFactory>()
        val repo = makeRepo(factory)

        assertTrue(repo.readDtcs().isEmpty())
        verify(exactly = 0) { factory.create(any()) }
    }

    @Test
    fun `readDtcs parses confirmed and pending DTCs when connected`() = runTest {
        val elmCtrl = mockk<ElmController>()
        // "43 01 33" → confirmed DTC P0133; "47 01 34" → pending DTC P0134
        coEvery { elmCtrl.sendCommand("03") } returns "43 01 33"
        coEvery { elmCtrl.sendCommand("07") } returns "47 01 34"
        // Use a real DtcParser so the actual parsing logic is exercised end-to-end.
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val result = repo.readDtcs()

        assertEquals(2, result.size)
        assertEquals("P0133", result[0].code)
        assertFalse(result[0].isPending)
        assertEquals("P0134", result[1].code)
        assertTrue(result[1].isPending)
    }

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
    fun `readFreezeFrame returns empty map when not connected`() = runTest {
        val factory = mockk<ConnectionFactory>()
        val repo = makeRepo(factory)

        assertTrue(repo.readFreezeFrame().isEmpty())
        verify(exactly = 0) { factory.create(any()) }
    }

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
        // Keyed by PID id ("010C"), not the English PidDefinition.name — the UI resolves the
        // localized label from the id via PidRegistry, so the repository must not bake in English.
        assertTrue("010C" in result)
        // Value should use decimal precision (%.1f), not integer truncation.
        // RPM formula: (26*256 + 248) / 4 = 1726.0 → "1726.0 rpm"
        assertTrue("RPM value should contain decimal point: ${result["010C"]}", result["010C"]!!.contains("."))
    }

    @Test
    fun `readFreezeFrame disconnects and rethrows when sendCommand throws ConnectionLost`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.ConnectionLost()
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try { repo.readFreezeFrame() } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
        // Same fatal handling as the identical catch block elsewhere: disconnect before rethrow
        assertEquals(false, repo.isConnectedFlow.value)
    }

    // ── BusInit escalation ────────────────────────────────────────────────────

    private suspend fun makeConnectedRepo(
        elmCtrl: ElmController,
        pidParser: PidParser = mockk(),
        device: ScannedDevice = mockk(),
        dtcParser: DtcParser = mockk()
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
        return ObdRepository(factory, pidParser, dtcParser).also { it.connect(device) }
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

    @Test
    fun `livePids disconnects and rethrows when sendCommand throws ConnectionLost directly`() = runTest {
        // Distinct from the BusInit-escalation path above: here sendCommand throws
        // ConnectionLost itself on the very first cycle, hitting livePids' own
        // `catch (e: ObdException.ConnectionLost)` block directly rather than via the
        // 5-consecutive-BusInit escalation that synthesizes a ConnectionLost.
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.ConnectionLost()
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try {
            repo.livePids(listOf(PidRegistry.defaults.first()), pollIntervalMs = 0).collect {}
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
        assertEquals(false, repo.isConnectedFlow.value)
    }

    // ── OBD-Watchdog (Timeout ohne Eskalation, s. obd-watchdog-bugfix-2026-09-26) ─────
    //
    // All watchdog tests pass runTest's virtual clock (`testTimeSource`) explicitly: livePids()
    // measures real elapsed time since the last successful cycle, and only the virtual clock
    // advances in lockstep with the virtual delay()s inside runTest. The mocked Timeouts below
    // throw instantly, so virtual time advances only by pollIntervalMs per cycle — unless a test
    // delays inside sendCommand on purpose.

    @Test
    fun `livePids escalates to ConnectionLost when timeouts persist past the watchdog threshold`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.Timeout("pid")
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try {
            repo.livePids(
                listOf(PidRegistry.defaults.first()),
                pollIntervalMs = 1_000L,
                watchdogTimeoutMs = 3_000L,
                timeSource = testTimeSource,
            ).collect {}
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
        // Same fatal handling as the BusInit escalation: disconnect before throwing, so the
        // reconnect loop sees isConnectedFlow drop.
        assertEquals(false, repo.isConnectedFlow.value)
    }

    @Test
    fun `livePids does not escalate on timeout while still under the watchdog threshold`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val pidParser = mockk<PidParser> { every { parse(any(), any()) } returns mockk() }

        // Cycles 1-3 time out at virtual t=0/1000/2000 ms — the last check sees 2000 ms elapsed,
        // one poll interval under the 3000 ms threshold. Cycle 4 (t=3000) succeeds.
        var cycleCount = 0
        coEvery { elmCtrl.sendCommand(any()) } answers {
            if (++cycleCount <= 3) throw ObdException.Timeout("pid") else "41 0C 1A 00"
        }
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        val result = repo.livePids(
            listOf(PidRegistry.defaults.first()),
            pollIntervalMs = 1_000L,
            watchdogTimeoutMs = 3_000L,
            timeSource = testTimeSource,
        ).first()

        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `livePids resets the watchdog counter after a successful cycle`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val pidParser = mockk<PidParser> { every { parse(any(), any()) } returns mockk() }

        // Cycles 1-2 time out (t=0/1000), cycle 3 succeeds at t=2000 and resets the mark.
        // Cycles 4-5 time out again (t=3000/4000): only 1000/2000 ms since the reset, under the
        // 3000 ms threshold. Without the reset, cycle 4 would already see 3000 ms since the start
        // and escalate.
        var cycleCount = 0
        coEvery { elmCtrl.sendCommand(any()) } answers {
            when {
                ++cycleCount <= 2 -> throw ObdException.Timeout("pid")
                cycleCount == 3   -> "41 0C 1A 00"
                cycleCount <= 5   -> throw ObdException.Timeout("pid")
                else              -> "41 0C 1A 00"
            }
        }
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        var emissionCount = 0
        var threw = false
        try {
            repo.livePids(
                listOf(PidRegistry.defaults.first()),
                pollIntervalMs = 1_000L,
                watchdogTimeoutMs = 3_000L,
                timeSource = testTimeSource,
            ).take(2).collect { emissionCount++ }
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertEquals(2, emissionCount)
        assertFalse(threw)
    }

    @Test
    fun `livePids watchdog measures real elapsed time, not poll intervals, when timeouts block`() = runTest {
        // Final-review finding C1. A real ElmController Timeout only surfaces after its 5 s read
        // timeout, so one empty cycle over 3 PIDs lasts 15 s. The old implementation added only
        // pollIntervalMs (500 ms) per empty cycle and needed 40 such cycles (~10 min) to reach a
        // 20 s threshold. Measured on the clock, cycle 1 ends at 15_000 ms (under 20_000 ms) and
        // cycle 2 at 30_500 ms, which must escalate.
        val pids = listOf(PidRegistry.RPM, PidRegistry.SPEED, PidRegistry.COOLANT_TEMP)
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } coAnswers {
            delay(5_000L)  // ElmController's READ_TIMEOUT_MS, in virtual time
            throw ObdException.Timeout(firstArg<String>())
        }
        // Registered after the stub above, so the discovery stubs in makeConnectedRepo win for
        // 0100/0120/0140 and connect() itself costs no virtual time.
        val repo = makeConnectedRepo(elmCtrl)

        val pollStart = currentTime
        var escalatedAfterMs: Long? = null
        try {
            repo.livePids(
                pids,
                pollIntervalMs = 500L,
                watchdogTimeoutMs = 20_000L,
                timeSource = testTimeSource,
            ).collect {}
        } catch (_: ObdException.ConnectionLost) {
            escalatedAfterMs = currentTime - pollStart
        }

        assertNotNull("the watchdog must escalate", escalatedAfterMs)
        assertTrue(
            "must not escalate before the 20_000 ms threshold, escalated after $escalatedAfterMs ms",
            escalatedAfterMs!! >= 20_000L,
        )
        // Threshold plus at most one full cycle (3 x 5_000 ms + 500 ms poll interval).
        assertTrue(
            "must escalate within one cycle after the threshold, escalated after $escalatedAfterMs ms",
            escalatedAfterMs <= 20_000L + 15_500L,
        )
        assertEquals(false, repo.isConnectedFlow.value)
    }

    /**
     * Collects [ObdRepository.livePids] on the virtual clock with the timing the tests below share
     * (1 s poll interval, 3 s watchdog threshold) for at most [windowMs] of virtual time.
     * Returns the virtual ms from the start of polling until the watchdog escalated, or null if
     * the loop was still polling when the window closed. The loop ending on its own fails the
     * test, so a null can never come from a flow that simply stopped.
     */
    private suspend fun TestScope.msUntilWatchdogEscalates(
        repo: ObdRepository,
        pids: List<PidDefinition>,
        windowMs: Long = 60_000L,
    ): Long? {
        val pollStart = currentTime
        return withTimeoutOrNull(windowMs) {
            try {
                repo.livePids(
                    pids,
                    pollIntervalMs = 1_000L,
                    watchdogTimeoutMs = 3_000L,
                    timeSource = testTimeSource,
                ).collect {}
                throw AssertionError("the poll loop ended on its own instead of polling or escalating")
            } catch (_: ObdException.ConnectionLost) {
                currentTime - pollStart
            }
        }
    }

    @Test
    fun `livePids watchdog counts Timeout and UnknownResponse, even next to NoData, but never NoData alone`() = runTest {
        // Final-review finding I2, revised after concerns A and B. NoData is a well-formed answer:
        // the ECU has nothing to report for this PID (e.g. ignition off with a powered adapter),
        // so cycles of nothing but NoData must never trip the watchdog, however long they last.
        // Timeout (adapter silent) and UnknownResponse (adapter answers, but nothing parseable)
        // both mean no usable data gets through, so they must escalate once they persist, also
        // when another PID in the same cycle answers NO DATA.
        suspend fun msUntilEscalationWhenSpeedThrows(speedError: ObdException): Long? {
            val elmCtrl = mockk<ElmController>()
            coEvery { elmCtrl.sendCommand(PidRegistry.RPM.id) } throws ObdException.NoData(PidRegistry.RPM.id)
            coEvery { elmCtrl.sendCommand(PidRegistry.SPEED.id) } throws speedError
            val repo = makeConnectedRepo(elmCtrl)
            return msUntilWatchdogEscalates(repo, listOf(PidRegistry.RPM, PidRegistry.SPEED))
        }

        assertNull(
            "NoData alone must never trip the watchdog (60 s window, 20x the threshold)",
            msUntilEscalationWhenSpeedThrows(ObdException.NoData(PidRegistry.SPEED.id)),
        )
        assertNotNull(
            "UnknownResponse must count for the watchdog, even next to NoData",
            msUntilEscalationWhenSpeedThrows(ObdException.UnknownResponse("?")),
        )
        assertNotNull(
            "Timeout must count for the watchdog, even next to NoData",
            msUntilEscalationWhenSpeedThrows(ObdException.Timeout(PidRegistry.SPEED.id)),
        )
    }

    @Test
    fun `livePids watchdog escalates on persistent UnknownResponse, as after an adapter reset with echo on`() = runTest {
        // Final-review concern A. An adapter that resets itself mid-ride (e.g. on a voltage dip
        // while cranking) is back on its power-on defaults, echo on among them; initialize()
        // switches it off with ATE0. ElmController joins the echoed command and the answer into
        // one line ("010C 41 0C 1A F8"), which PidParser rejects as UnknownResponse, for every PID
        // in every cycle. The adapter keeps answering, yet no reading gets through: the watchdog
        // must escalate as it does for Timeouts, so the reconnect re-runs initialize().
        // Uses the real PidParser to cover that chain end to end.
        val pids = listOf(PidRegistry.RPM, PidRegistry.SPEED)
        val ecuAnswers = mapOf(PidRegistry.RPM.id to "41 0C 1A F8", PidRegistry.SPEED.id to "41 0D 32")
        val pidParser = PidParser()
        // Sanity check: without the echo, the same answers parse, so the escalation below is down
        // to the echo alone.
        pids.forEach { pidParser.parse(it, ecuAnswers.getValue(it.id)) }
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } answers {
            val command = firstArg<String>()
            "$command ${ecuAnswers.getValue(command)}"  // echo + answer, joined as ElmController does
        }
        // Registered after the stub above, so the discovery stubs win for 0100/0120/0140.
        val repo = makeConnectedRepo(elmCtrl, pidParser)

        val escalatedAfterMs = msUntilWatchdogEscalates(repo, pids)

        assertNotNull("persistent UnknownResponse must trip the watchdog", escalatedAfterMs)
        assertTrue(
            "must not escalate before the 3_000 ms threshold, escalated after $escalatedAfterMs ms",
            escalatedAfterMs!! >= 3_000L,
        )
        assertTrue(
            "must escalate within one poll cycle after the threshold, escalated after $escalatedAfterMs ms",
            escalatedAfterMs <= 3_000L + 1_000L,
        )
        assertFalse(repo.isConnectedFlow.value)
    }

    @Test
    fun `livePids watchdog does not escalate on the first timeout after a long NoData stretch, only once timeouts persist`() = runTest {
        // Final-review concern B. Parked with the ignition off, a powered adapter answers NO DATA
        // for every PID: no readings, but the link demonstrably works. Such cycles must restart
        // the watchdog's clock like a successful one. Otherwise the first Timeout afterwards is
        // measured against the last real reading, arbitrarily long ago, and escalates at once.
        //
        // Timeline (1 PID, instant mocks, 1 s poll interval, 3 s threshold): reads 1-30 answer
        // NO DATA at t = 0..29_000 ms; the last one restarts the clock at 29_000. From t = 30_000
        // on, every read times out. The checks at 30_000 and 31_000 see 1_000 and 2_000 ms, under
        // the threshold; the check at 32_000 sees 3_000 ms and escalates.
        var reads = 0
        var timeouts = 0
        var lastNoDataAt = -1L
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } answers {
            if (++reads <= 30) {
                lastNoDataAt = currentTime
                throw ObdException.NoData(firstArg())
            }
            timeouts++
            throw ObdException.Timeout(firstArg())
        }
        // Registered after the stub above, so the discovery stubs win for 0100/0120/0140 and
        // don't count as reads.
        val repo = makeConnectedRepo(elmCtrl)

        val pollStart = currentTime
        val escalatedAfterMs = msUntilWatchdogEscalates(repo, listOf(PidRegistry.RPM))

        assertNotNull("timeouts that persist past the threshold must still escalate", escalatedAfterMs)
        assertTrue(
            "a single timeout right after the NoData stretch must not escalate, escalated after $timeouts timeout(s)",
            timeouts > 1,
        )
        val sinceLastNoDataMs = pollStart + escalatedAfterMs!! - lastNoDataAt
        assertTrue(
            "must not escalate before timeouts persisted for the 3_000 ms threshold since the last " +
                "NoData cycle, escalated after $sinceLastNoDataMs ms",
            sinceLastNoDataMs >= 3_000L,
        )
        assertTrue(
            "must escalate within one poll cycle after the threshold, escalated after $sinceLastNoDataMs ms",
            sinceLastNoDataMs <= 3_000L + 1_000L,
        )
        assertFalse(repo.isConnectedFlow.value)
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

    @Test
    fun `discoverSupportedPids skips range when response has too few tokens`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        // Malformed: only 3 tokens, below the "41 XX B1 B2 B3 B4" minimum of 6 — the
        // tokens.size < 6 guard must skip this range silently instead of throwing.
        coEvery { elmCtrl.sendCommand("0120") } returns "41 20 00"
        // Bitmap 0x40000000 → bit 30 → pidNum 0x40 + 2 = 0x42 → "0142" (Control Module Voltage)
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 40 00 00 00"

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        val supported = repo.supportedPidIds.value
        assertNotNull("discovery should still succeed using the other two ranges", supported)
        assertTrue("010C (RPM) from range 0100 should be in supported", "010C" in supported!!)
        assertTrue("010D (Speed) from range 0100 should be in supported", "010D" in supported)
        assertTrue(
            "0142 from range 0140 should still be discovered after the malformed 0120 response",
            "0142" in supported
        )
    }

    @Test
    fun `discoverSupportedPids skips range when response header byte is not 41`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        // 6 tokens (passes the size check) but the header byte is not "41" — the
        // tokens[0] != "41" guard must skip this range silently instead of decoding garbage.
        coEvery { elmCtrl.sendCommand("0120") } returns "51 20 00 00 00 00"
        // Bitmap 0x40000000 → bit 30 → pidNum 0x40 + 2 = 0x42 → "0142" (Control Module Voltage)
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 40 00 00 00"

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        val supported = repo.supportedPidIds.value
        assertNotNull("discovery should still succeed using the other two ranges", supported)
        assertTrue("010C (RPM) from range 0100 should be in supported", "010C" in supported!!)
        assertTrue("010D (Speed) from range 0100 should be in supported", "010D" in supported)
        assertTrue(
            "0142 from range 0140 should still be discovered after the malformed 0120 response",
            "0142" in supported
        )
    }

    @Test
    fun `discoverSupportedPids skips range when bitmap bytes are not valid hex`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } just Runs
        coEvery { elmCtrl.sendCommand("0100") } returns "41 00 00 18 00 00"
        // "ZZ" is not a valid hex byte — the bitmap fold's toLong(16) throws
        // NumberFormatException, which the guard must catch and skip, not propagate
        // (an uncaught throw here would also wipe out 0100's already-discovered PIDs,
        // since the outer catch in connectLocked() discards the whole result on failure).
        coEvery { elmCtrl.sendCommand("0120") } returns "41 20 ZZ 00 00 00"
        // Bitmap 0x40000000 → bit 30 → pidNum 0x40 + 2 = 0x42 → "0142" (Control Module Voltage)
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 40 00 00 00"

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        val supported = repo.supportedPidIds.value
        assertNotNull("discovery should still succeed using the other two ranges", supported)
        assertTrue("010C (RPM) from range 0100 should be in supported", "010C" in supported!!)
        assertTrue("010D (Speed) from range 0100 should be in supported", "010D" in supported)
        assertTrue(
            "0142 from range 0140 should still be discovered after the malformed 0120 response",
            "0142" in supported
        )
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
    fun `readVin returns raw sendCommand response when connected`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("0902") } returns "49 02 01 57 50 30"
        val repo = makeConnectedRepo(elmCtrl)

        assertEquals("49 02 01 57 50 30", repo.readVin())
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

    // ── latestReadings ────────────────────────────────────────────────────────

    @Test
    fun `latestReadings starts empty`() = runTest {
        val repo = makeRepo()
        assertTrue(repo.latestReadings.value.isEmpty())
    }

    @Test
    fun `updateLatestReadings updates the exposed latestReadings flow`() = runTest {
        val repo = makeRepo()
        val pid = PidRegistry.defaults.first()
        val readings = listOf(SensorValue(pid = pid, value = 42f))

        repo.updateLatestReadings(readings)

        assertEquals(readings, repo.latestReadings.value)
    }
}
