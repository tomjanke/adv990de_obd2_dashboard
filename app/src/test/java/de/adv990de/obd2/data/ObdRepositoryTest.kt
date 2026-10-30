package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.withDefaultLocale
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.FakeObdConnection
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.InitResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
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
        coEvery { elm.initialize() } returns InitResult("OK", 0)
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
        coEvery { elm.initialize() } returns InitResult("OK", 0)
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
        coEvery { elm1.initialize() } returns InitResult("OK", 0)
        coEvery { elm2.initialize() } returns InitResult("OK", 0)
        every { conn1.close() } just Runs

        val repo = makeRepo(factory)
        repo.connect(device1)
        repo.connect(device2)

        verifyOrder { conn1.close(); factory.create(device2) }
        assertEquals(true, repo.isConnectedFlow.value)
    }

    // --- adapterInit: die Antwort des Adapters auf ATZ gehoert zur aktuellen Verbindung ---

    private fun connectionFor(init: InitResult): Pair<ObdConnection, ElmController> {
        val elm = mockk<ElmController>()
        coEvery { elm.initialize() } returns init
        val conn = mockk<ObdConnection> {
            coEvery { connect() } returns elm
            every { close() } just Runs
        }
        return conn to elm
    }

    @Test
    fun `adapterInit is empty before a connection and carries the ATZ answer after it`() = runTest {
        val device = mockk<ScannedDevice>()
        val (conn, _) = connectionFor(InitResult("ELM327 v1.0", 940))
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        val repo = makeRepo(factory)
        assertNull(repo.adapterInit)

        repo.connect(device)

        assertEquals(InitResult("ELM327 v1.0", 940), repo.adapterInit)
    }

    @Test
    fun `adapterInit is forgotten when the connection ends`() = runTest {
        val device = mockk<ScannedDevice>()
        val (conn, _) = connectionFor(InitResult("OK", 3))
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        val repo = makeRepo(factory)
        repo.connect(device)

        repo.disconnect()

        assertNull(repo.adapterInit)
    }

    @Test
    fun `adapterInit follows the adapter that replaces the connection`() = runTest {
        val deviceA = mockk<ScannedDevice>()
        val deviceB = mockk<ScannedDevice>()
        val (connA, _) = connectionFor(InitResult("ELM327 v1.0", 940))
        val (connB, _) = connectionFor(InitResult("OK", 4))
        val factory = mockk<ConnectionFactory> {
            coEvery { create(deviceA) } returns connA
            coEvery { create(deviceB) } returns connB
        }
        val repo = makeRepo(factory)
        repo.connect(deviceA)

        repo.connect(deviceB)

        assertEquals(InitResult("OK", 4), repo.adapterInit)
    }

    @Test
    fun `a failed connect leaves no adapterInit behind, not even the one of the connection it replaced`() = runTest {
        val deviceA = mockk<ScannedDevice>()
        val deviceB = mockk<ScannedDevice>()
        val (connA, _) = connectionFor(InitResult("ELM327 v1.0", 940))
        val elmB = mockk<ElmController>()
        coEvery { elmB.initialize() } throws Exception("init failed")
        val connB = mockk<ObdConnection> {
            coEvery { connect() } returns elmB
            every { close() } just Runs
        }
        val factory = mockk<ConnectionFactory> {
            coEvery { create(deviceA) } returns connA
            coEvery { create(deviceB) } returns connB
        }
        val repo = makeRepo(factory)
        repo.connect(deviceA)

        try { repo.connect(deviceB) } catch (_: Exception) {}

        assertNull(repo.adapterInit)
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
    fun `reconnect after a lost link closes the dead connection before opening a new one`() = runTest {
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
        coEvery { elm1.initialize() } returns InitResult("OK", 0)
        coEvery { elm2.initialize() } returns InitResult("OK", 0)
        every { conn1.isConnected } returns true
        every { conn1.close() } just Runs

        val repo = makeRepo(factory)
        repo.connect(device)   // sets lastConnectedDevice, uses conn1
        repo.disconnect()      // the link is gone (what link loss and the watchdog do before they report)
        repo.reconnect()       // opens a fresh connection → conn2 (while connected it is a no-op, see below)

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

    // ── B5: discovery must not swallow a cancelled caller or a dead link ──────

    /** A connection whose three discovery commands behave as given by [onDiscovery]. */
    private class Rig(onDiscovery: suspend (String) -> String) {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elm = mockk<ElmController>()
        val factory = mockk<ConnectionFactory>()

        init {
            coEvery { factory.create(device) } returns conn
            coEvery { conn.connect() } returns elm
            coEvery { elm.initialize() } returns InitResult("OK", 0)
            coEvery { elm.sendCommand(any()) } coAnswers { onDiscovery(firstArg()) }
        }
    }

    @Test
    fun `connect fails and closes the connection when the link is lost during discovery`() = runTest {
        val rig = Rig { throw ObdException.ConnectionLost() }
        val repo = makeRepo(rig.factory)

        val failure = runCatching { repo.connect(rig.device) }.exceptionOrNull()

        assertTrue("ConnectionLost muss durchschlagen: $failure", failure is ObdException.ConnectionLost)
        assertFalse("Ein toter Link darf nicht als verbunden gelten", repo.isConnectedFlow.value)
        verify { rig.conn.close() }
        assertNull(repo.lastConnectedDevice.value)
    }

    @Test
    fun `a caller cancelled during discovery leaves no connection behind`() = runTest {
        val rig = Rig { awaitCancellation() }
        val repo = makeRepo(rig.factory)

        val job = launch { repo.connect(rig.device) }
        runCurrent()                // connect() is now suspended inside discovery
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse("Abgebrochen heisst nicht verbunden", repo.isConnectedFlow.value)
        verify { rig.conn.close() }
        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `discovery still tolerates silent ranges and unknown answers`() = runTest {
        val rig = Rig { throw ObdException.NoData(it) }
        val repo = makeRepo(rig.factory)

        repo.connect(rig.device)

        assertTrue(repo.isConnectedFlow.value)
        assertNull(repo.supportedPidIds.value)
    }

    @Test
    fun `discovery skips a range answered with an unreadable text or a bus error and still uses the others`() = runTest {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
        coEvery { elmCtrl.sendCommand("0100") } throws ObdException.UnknownResponse("?")
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.BusInit("BUS INIT: ERROR")
        // Bereich 0140: Bit 30 des ersten Bytes = PID 0x42 (Steuergeraetespannung) — der einzige bekannte PID, den der letzte Bereich meldet
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 40 00 00 00"
        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())

        repo.connect(device)

        assertTrue("die Verbindung steht trotz der zwei Fehlantworten", repo.isConnectedFlow.value)
        assertEquals("die Schleife lief nach beiden Fehlern weiter und nahm den letzten Bereich", setOf("0142"), repo.supportedPidIds.value)
    }

    // ── B6: a connect that hangs is bounded and abortable ─────────────────────

    /**
     * Eine Verbindung, deren connect() blockierend haengt, wie BluetoothSocket.connect(): Es blockiert den Thread und
     * laesst sich nicht abbrechen — nur close() von anderer Stelle gibt es frei (und es endet dann mit einem Fehler).
     */
    private class HangingConnection : ObdConnection {
        private val released = java.util.concurrent.CountDownLatch(1)
        @Volatile var closeCalls = 0
        /** true = ein close() von aussen hat den blockierenden connect() freigegeben; false = nur die Test-Grenze. */
        @Volatile var releasedByClose: Boolean? = null
        private val outcomeKnown = java.util.concurrent.CountDownLatch(1)
        private val entered = java.util.concurrent.CountDownLatch(1)
        override val isConnected: Boolean = false
        override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
            entered.countDown()
            // Begrenzt: ein Fehler (oder eine Mutation), die das close() weglaesst, soll den Test scheitern lassen,
            // statt den Test-JVM fuer immer zu blockieren — `runTest(timeout)` kann einen blockierten Thread nicht abbrechen.
            releasedByClose = released.await(5, java.util.concurrent.TimeUnit.SECONDS)
            outcomeKnown.countDown()
            throw BluetoothException.ConnectionFailed("socket closed")
        }
        /**
         * Ergebnis der Wartezeit in [connect]. `withContext` kehrt bei einem Abbruch SOFORT zurueck, der IO-Thread
         * schreibt sein Ergebnis erst danach — ein Test, der nach `join()` liest, wuerde sonst um dieses Rennen streiten.
         */
        /** Wartet, bis der IO-Thread wirklich in [connect] blockiert — erst dann ist ein Abbruch ein Abbruch DES Blockierens. */
        fun awaitEntered(): Boolean = entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
        fun awaitReleasedByClose(): Boolean? { outcomeKnown.await(5, java.util.concurrent.TimeUnit.SECONDS); return releasedByClose }
        override fun close() { closeCalls++; released.countDown() }
    }

    private fun repoWith(conn: ObdConnection, deadlineMs: Long): Pair<ObdRepository, ScannedDevice> {
        val device = mockk<ScannedDevice>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        // Long.MAX_VALUE = die Frist laeuft nie ab. runTest ueberspringt virtuelle Zeit, sobald nichts mehr zu tun ist —
        // auch waehrend ein ECHTER Thread blockiert; ein Test, der die Frist NICHT meint, schaltet sie deshalb ab.
        return makeRepo(factory).also { it.connectTimeoutMs = deadlineMs } to device
    }

    @Test
    fun `a connect that hangs is aborted at the deadline - Timeout, connection closed, lock free`() = runTest(timeout = 10.seconds) {
        val conn = HangingConnection()
        val (repo, device) = repoWith(conn, deadlineMs = 60_000)

        val failure = runCatching { repo.connect(device) }.exceptionOrNull()

        assertTrue("Timeout erwartet, war: $failure", failure is ObdException.Timeout)
        assertTrue("die haengende Verbindung wurde geschlossen", conn.closeCalls >= 1)
        assertEquals("der Waechter schliesst die Verbindung — nicht erst das Ende des blockierenden Aufrufs", true, conn.awaitReleasedByClose())
        assertFalse(repo.isConnectedFlow.value)
        repo.disconnect()    // kehrt zurueck: der Lock ist frei, "Trennen" haengt nicht dahinter
    }

    @Test
    fun `an initialize that blocks is covered by the same deadline`() = runTest(timeout = 10.seconds) {
        val released = java.util.concurrent.CountDownLatch(1)
        val releasedByClose = java.util.concurrent.atomic.AtomicBoolean(false)
        val elm = mockk<ElmController>()
        coEvery { elm.initialize() } coAnswers {
            // begrenzt, s. HangingConnection: ein fehlendes close() soll scheitern, nicht haengen
            withContext(Dispatchers.IO) { releasedByClose.set(released.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            throw ObdException.ConnectionLost()
        }
        val conn = mockk<ObdConnection> {
            coEvery { connect() } returns elm
            every { close() } answers { released.countDown() }
        }
        val (repo, device) = repoWith(conn, deadlineMs = 60_000)

        val failure = runCatching { repo.connect(device) }.exceptionOrNull()

        assertTrue("Timeout erwartet, war: $failure", failure is ObdException.Timeout)
        assertTrue("der Waechter schliesst die Verbindung, solange initialize() blockiert", releasedByClose.get())
        verify(atLeast = 1) { conn.close() }
        assertFalse(repo.isConnectedFlow.value)
    }

    @Test
    fun `a connect that finishes in time is not touched by the deadline`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.initialize() } returns InitResult("OK", 0)
        coEvery { elm.sendCommand(any()) } throws ObdException.NoData("x")
        val conn = mockk<ObdConnection> {
            coEvery { connect() } returns elm
            every { close() } just Runs
        }
        val (repo, device) = repoWith(conn, deadlineMs = 60_000)

        repo.connect(device)
        advanceTimeBy(120_000); runCurrent()      // die Frist ist laengst "abgelaufen", wenn sie noch liefe

        assertTrue(repo.isConnectedFlow.value)
        verify(exactly = 0) { conn.close() }
    }

    @Test
    fun `cancelling the caller closes the connection that is stuck in connect`() = runTest(timeout = 10.seconds) {
        val conn = HangingConnection()
        val (repo, device) = repoWith(conn, deadlineMs = Long.MAX_VALUE)
        val connecting = launch { repo.connect(device) }
        runCurrent()                                 // connect() wird gestartet ...
        assertTrue("connect() blockiert im IO-Thread", conn.awaitEntered())

        connecting.cancel()
        connecting.join()

        assertTrue("ein blockierendes connect() sieht den Abbruch nicht — nur close() gibt es frei", conn.closeCalls >= 1)
        assertEquals("close() kam vom Waechter, waehrend connect() noch blockierte", true, conn.awaitReleasedByClose())
        assertTrue(connecting.isCancelled)
        assertFalse(repo.isConnectedFlow.value)
    }

    @Test
    fun `a user disconnect aborts a connect in progress at once instead of waiting for it`() = runTest(timeout = 10.seconds) {
        val conn = HangingConnection()
        val (repo, device) = repoWith(conn, deadlineMs = Long.MAX_VALUE)
        val connecting = launch { runCatching { repo.connect(device) } }
        runCurrent()
        assertTrue("connect() blockiert im IO-Thread", conn.awaitEntered())

        val user = launch { repo.disconnectByUser() }
        runCurrent()

        assertTrue("sofort geschlossen, ohne auf irgendeine Frist zu warten", conn.closeCalls >= 1)
        connecting.join(); user.join()
        assertEquals(true, conn.awaitReleasedByClose())
        assertFalse(repo.isConnectedFlow.value)
        assertEquals(1, repo.userDisconnectCount.value)
    }

    // ── B2: the "already connected?" decision is made inside the lock ─────────

    private fun twoConnections(): Triple<ConnectionFactory, ObdConnection, ObdConnection> {
        val conn1 = mockk<ObdConnection> { every { isConnected } returns true; every { close() } just Runs }
        val conn2 = mockk<ObdConnection> { every { isConnected } returns true; every { close() } just Runs }
        val factory = mockk<ConnectionFactory>()
        return Triple(factory, conn1, conn2)
    }

    private fun stubElm(conn: ObdConnection): ElmController = mockk<ElmController>().also { elm ->
        coEvery { conn.connect() } returns elm
        coEvery { elm.initialize() } returns InitResult("OK", 0)
        coEvery { elm.sendCommand(any()) } throws ObdException.NoData("x")
    }

    @Test
    fun `reconnect is a no-op while a connection is up - it must not tear it down`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, conn2) = twoConnections()
        coEvery { factory.create(device) } returnsMany listOf(conn1, conn2)
        stubElm(conn1); stubElm(conn2)
        val repo = makeRepo(factory)
        repo.connect(device)

        repo.reconnect()

        coVerify(exactly = 1) { factory.create(device) }
        verify(exactly = 0) { conn1.close() }
        assertTrue(repo.isConnectedFlow.value)
    }

    @Test
    fun `a reconnect queued behind a running manual connect does not close the fresh connection`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, _) = twoConnections()
        coEvery { factory.create(device) } returns conn1
        val release = CompletableDeferred<Unit>()
        val elm = mockk<ElmController>()
        coEvery { conn1.connect() } coAnswers { release.await(); elm }   // the manual connect is "in progress"
        coEvery { elm.initialize() } returns InitResult("OK", 0)
        coEvery { elm.sendCommand(any()) } throws ObdException.NoData("x")
        val repo = makeRepo(factory)

        val manual = launch { repo.connect(device) }                    // user taps "Verbinden"
        runCurrent()
        val auto = launch { repo.reconnect() }                          // the loop's attempt queues on the mutex
        runCurrent()
        release.complete(Unit)                                          // manual connect finishes
        manual.join(); auto.join()

        assertTrue(repo.isConnectedFlow.value)
        coVerify(exactly = 1) { factory.create(device) }                // the manual connect only, NOT a second one
        verify(exactly = 0) { conn1.close() }
    }

    @Test
    fun `connectIfDisconnected connects when nothing is connected`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, _) = twoConnections()
        coEvery { factory.create(device) } returns conn1
        stubElm(conn1)
        val repo = makeRepo(factory)

        repo.connectIfDisconnected(device)

        assertTrue(repo.isConnectedFlow.value)
        assertEquals(device, repo.lastConnectedDevice.value)
    }

    @Test
    fun `connectIfDisconnected leaves an existing connection alone`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, conn2) = twoConnections()
        coEvery { factory.create(device) } returnsMany listOf(conn1, conn2)
        stubElm(conn1); stubElm(conn2)
        val repo = makeRepo(factory)
        repo.connect(device)

        repo.connectIfDisconnected(device)

        coVerify(exactly = 1) { factory.create(device) }
        verify(exactly = 0) { conn1.close() }
    }

    // ── B3: a user disconnect is told apart from a lost link ──────────────────

    @Test
    fun `disconnectByUser disconnects and counts`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, _) = twoConnections()
        coEvery { factory.create(device) } returns conn1
        stubElm(conn1)
        val repo = makeRepo(factory)
        repo.connect(device)
        assertEquals(0, repo.userDisconnectCount.value)

        repo.disconnectByUser()

        assertFalse(repo.isConnectedFlow.value)
        assertEquals(1, repo.userDisconnectCount.value)
        verify { conn1.close() }
    }

    @Test
    fun `a plain disconnect - the internal link-loss path - does not count as a user disconnect`() = runTest {
        val device = mockk<ScannedDevice>()
        val (factory, conn1, _) = twoConnections()
        coEvery { factory.create(device) } returns conn1
        stubElm(conn1)
        val repo = makeRepo(factory)
        repo.connect(device)

        repo.disconnect()

        assertEquals(0, repo.userDisconnectCount.value)
    }

    @Test
    fun `the count is raised before the disconnect waits for the lock`() = runTest {
        // A reconnect attempt holds the lock (connect in progress). The user's disconnect must be VISIBLE at once,
        // not only after the attempt is over — that is what lets the loop notice it.
        val device = mockk<ScannedDevice>()
        val (factory, conn1, _) = twoConnections()
        coEvery { factory.create(device) } returns conn1
        val release = CompletableDeferred<Unit>()
        val elm = mockk<ElmController>()
        coEvery { conn1.connect() } coAnswers { release.await(); elm }
        coEvery { elm.initialize() } returns InitResult("OK", 0)
        coEvery { elm.sendCommand(any()) } throws ObdException.NoData("x")
        val repo = makeRepo(factory)
        val connecting = launch { repo.connect(device) }
        runCurrent()                                  // holds the mutex, suspended in conn.connect()

        val user = launch { repo.disconnectByUser() }
        runCurrent()

        assertEquals("sofort sichtbar, obwohl der Lock noch belegt ist", 1, repo.userDisconnectCount.value)
        release.complete(Unit)
        connecting.join(); user.join()
        assertFalse("danach ist die Verbindung wieder getrennt", repo.isConnectedFlow.value)
    }

    // ── PRO-11: ein veralteter Leser darf eine neuere Verbindung nicht trennen ─

    /**
     * Ein Lesevorgang wartet auf der Verbindung A auf eine Antwort, waehrend B aufgebaut wird (ein manuelles "Verbinden" zu einem
     * anderen Adapter schliesst A — das laesst das blockierte Lesen auf A scheitern). [read] ruft den Lesepfad; er scheitert mit
     * [failure], NACHDEM B den Lock haelt, und will dann trennen. Rueckgabe: das Repository, B und ob der Leser den Fehler sah.
     */
    private suspend fun TestScope.readOnReplacedConnection(
        failure: ObdException,
        read: suspend (ObdRepository) -> Unit,
    ): Triple<ObdRepository, ObdConnection, Boolean> {
        val deviceA = mockk<ScannedDevice>()
        val deviceB = mockk<ScannedDevice>()
        val (factory, connA, connB) = twoConnections()
        coEvery { factory.create(deviceA) } returns connA
        coEvery { factory.create(deviceB) } returns connB
        val elmA = stubElm(connA)
        val repo = makeRepo(factory)
        repo.connect(deviceA)
        // Erst NACH der Discovery von A: ab jetzt wartet jeder Befehl auf A, bis A "geschlossen" wird.
        val connAClosed = CompletableDeferred<Unit>()
        coEvery { elmA.sendCommand(any()) } coAnswers { connAClosed.await(); throw failure }
        val holdB = CompletableDeferred<Unit>()
        val elmB = mockk<ElmController>()
        coEvery { connB.connect() } coAnswers { holdB.await(); elmB }
        coEvery { elmB.initialize() } returns InitResult("OK", 0)
        coEvery { elmB.sendCommand(any()) } throws ObdException.NoData("x")

        var readerSawFailure = false
        val reader = launch {
            try { read(repo) } catch (e: ObdException) { if (e::class == failure::class) readerSawFailure = true }
        }
        runCurrent()                       // der Leser wartet auf A
        val switching = launch { repo.connect(deviceB) }
        runCurrent()                       // B haelt den Lock, haengt in connect()
        connAClosed.complete(Unit)
        runCurrent()                       // der Leser scheitert und will trennen: er steht hinter dem Lock
        holdB.complete(Unit)
        switching.join(); reader.join()
        return Triple(repo, connB, readerSawFailure)
    }

    @Test
    fun `a poll on the replaced connection that loses its link leaves the new connection up`() = runTest {
        val (repo, connB, readerSawFailure) = readOnReplacedConnection(ObdException.ConnectionLost()) {
            it.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 10L).collect { }
        }

        assertTrue("der alte Leser meldet den Verlust weiter", readerSawFailure)
        assertTrue("die neue Verbindung steht weiter", repo.isConnectedFlow.value)
        verify(exactly = 0) { connB.close() }
    }

    @Test
    fun `a fault-code read on the replaced connection that times out leaves the new connection up`() = runTest {
        val (repo, connB, readerSawFailure) = readOnReplacedConnection(ObdException.Timeout("03")) { it.readDtcs() }

        assertTrue("der alte Leser meldet den Fehler weiter", readerSawFailure)
        assertTrue("die neue Verbindung steht weiter", repo.isConnectedFlow.value)
        verify(exactly = 0) { connB.close() }
    }

    @Test
    fun `a freeze-frame read on the replaced connection that loses its link leaves the new connection up`() = runTest {
        val (repo, connB, readerSawFailure) = readOnReplacedConnection(ObdException.ConnectionLost()) { it.readFreezeFrame() }

        assertTrue("der alte Leser meldet den Fehler weiter", readerSawFailure)
        assertTrue("die neue Verbindung steht weiter", repo.isConnectedFlow.value)
        verify(exactly = 0) { connB.close() }
    }

    @Test
    fun `a connect that replaces a connection and then fails leaves nothing of the old one behind`() = runTest {
        // Der alte Leser trennt nicht mehr (er ist nicht mehr der aktuelle) — dann muss das Ersetzen selbst aufraeumen.
        val deviceA = ScannedDevice(device = null, type = BtType.CLASSIC, name = "A")
        val deviceB = ScannedDevice(device = null, type = BtType.CLASSIC, name = "B")
        val (factory, connA, connB) = twoConnections()
        coEvery { factory.create(deviceA) } returns connA
        coEvery { factory.create(deviceB) } returns connB
        stubElm(connA)
        coEvery { connB.connect() } throws ObdException.ConnectionLost()
        val repo = makeRepo(factory)
        repo.connect(deviceA)
        repo.setMoving(true)
        repo.updateLatestReadings(listOf(SensorValue(PidRegistry.RPM, 1f)))

        try { repo.connect(deviceB) } catch (_: ObdException.ConnectionLost) { }

        assertFalse(repo.isConnectedFlow.value)
        assertFalse("die Fahrterkennung der alten Verbindung gilt nicht weiter", repo.isMovingFlow.value)
        assertTrue("die Werte der alten Verbindung sind weg", repo.latestReadings.value.isEmpty())
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

    // ── A9 (Teil 1): keine Antwort ist keine Entwarnung ───────────────────────────────────────────────────

    @Test
    fun `readDtcs throws NoData when the ECU does not answer mode 03 - it must not look like an all-clear`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "NO DATA"
        coEvery { elmCtrl.sendCommand("07") } returns "NO DATA"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val failure = runCatching { repo.readDtcs() }.exceptionOrNull()

        assertTrue("NoData erwartet, war: $failure", failure is ObdException.NoData)
        assertTrue("der Adapter bleibt verbunden - kein Verbindungsverlust", repo.isConnectedFlow.value)
    }

    @Test
    fun `readDtcs throws on a bus error in mode 03`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "UNABLE TO CONNECT"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val failure = runCatching { repo.readDtcs() }.exceptionOrNull()

        assertTrue("BusInit erwartet, war: $failure", failure is ObdException.BusInit)
    }

    @Test
    fun `readDtcs does not ask for pending codes when the confirmed read already failed`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "NO DATA"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        runCatching { repo.readDtcs() }

        coVerify(exactly = 0) { elmCtrl.sendCommand("07") }
    }

    @Test
    fun `readDtcs tolerates NO DATA for the optional pending codes - confirmed codes are still reported`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "43 01 33"
        coEvery { elmCtrl.sendCommand("07") } returns "NO DATA"      // many ECUs stay silent when nothing is pending
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val result = repo.readDtcs()

        assertEquals(listOf("P0133"), result.map { it.code })
        assertFalse(result[0].isPending)
    }

    @Test
    fun `readDtcs reports a real all-clear - positive answer without codes and silent pending read`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "43 00"
        coEvery { elmCtrl.sendCommand("07") } returns "NO DATA"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        assertTrue(repo.readDtcs().isEmpty())
    }

    @Test
    fun `readDtcs still throws for a bus error in the pending read - only silence is tolerated there`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "43 00"
        coEvery { elmCtrl.sendCommand("07") } returns "CAN ERROR"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val failure = runCatching { repo.readDtcs() }.exceptionOrNull()

        assertTrue("BusInit erwartet, war: $failure", failure is ObdException.BusInit)
    }

    @Test
    fun `readDtcs merges a code that is both confirmed and pending into one confirmed entry`() = runTest {
        val elmCtrl = mockk<ElmController>()
        // P0134 is stored (mode 03) and still pending (mode 07) — typical for a persisting fault.
        coEvery { elmCtrl.sendCommand("03") } returns "43 01 33 01 34"
        coEvery { elmCtrl.sendCommand("07") } returns "47 01 34 01 35"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val result = repo.readDtcs()

        // One row per code: the DTC page keys its LazyColumn by code, a duplicate would crash it.
        assertEquals(listOf("P0133", "P0134", "P0135"), result.map { it.code })
        assertFalse("confirmed wins over pending", result.first { it.code == "P0134" }.isPending)
        assertTrue(result.first { it.code == "P0135" }.isPending)
    }

    @Test
    fun `readDtcs drops a code that two ECUs report in the same answer`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "43 01 33 01 33"
        coEvery { elmCtrl.sendCommand("07") } returns "47"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        assertEquals(listOf("P0133"), repo.readDtcs().map { it.code })
    }

    @Test
    fun `readDtcs disconnects and rethrows when the link is lost while reading`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } throws ObdException.ConnectionLost()
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        var threw = false
        try { repo.readDtcs() } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
        // Wie livePids() und readFreezeFrame(): ein verlorener Link wird nie als "verbunden" stehen gelassen.
        assertEquals(false, repo.isConnectedFlow.value)
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

    // ── Fehlercodes unterwegs: weich lesen, nie trennen (vierte Form der Zeitlimit-Richtlinie) ─────────

    @Test
    fun `readDtcsOrSkip is null without a connection - not an all-clear - and does not touch the factory`() = runTest {
        val factory = mockk<ConnectionFactory>()
        val repo = makeRepo(factory)

        assertNull(repo.readDtcsOrSkip())
        verify(exactly = 0) { factory.create(any()) }
    }

    @Test
    fun `readDtcsOrSkip passes the soft read through when connected`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } returns "43 01 33"
        coEvery { elmCtrl.sendCommand("07") } returns "47 01 34"
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val result = repo.readDtcsOrSkip()

        assertEquals(listOf("P0133" to false, "P0134" to true), result!!.map { it.code to it.isPending })
    }

    @Test
    fun `readDtcsOrSkip skips a timeout and keeps the connection - unlike readDtcs`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } throws ObdException.Timeout("03")
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val result = repo.readDtcsOrSkip()

        assertNull(result)
        assertTrue("ein Zeitlimit unterwegs darf die Verbindung nicht kosten", repo.isConnectedFlow.value)
    }

    @Test
    fun `readDtcsOrSkip disconnects and rethrows when the link is lost`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("03") } throws ObdException.ConnectionLost()
        val repo = makeConnectedRepo(elmCtrl, dtcParser = DtcParser())

        val thrown = runCatching { repo.readDtcsOrSkip() }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $thrown", thrown is ObdException.ConnectionLost)
        assertFalse("ein verlorener Link steht nie als verbunden da", repo.isConnectedFlow.value)
    }

    // ── Freeze frame ─────────────────────────────────────────────────────────

    @Test
    fun `readFreezeFrame returns empty map when not connected`() = runTest {
        val factory = mockk<ConnectionFactory>()
        val repo = makeRepo(factory)

        assertTrue(repo.readFreezeFrame().isEmpty())
        verify(exactly = 0) { factory.create(any()) }
    }

    private fun freezeFrameRpmResult(): Pair<Map<String, String>, ElmController> = runBlocking {
        val elmCtrl = mockk<ElmController>()
        // Throw NoData for every PID except RPM (id "010C" → pidByte "0C" → command "020C00")
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("XX")
        coEvery { elmCtrl.sendCommand("02 0C 00") } returns "42 0C 1A F8"
        // Use a real PidParser so parseFreezeFrame actually processes the response.
        val repo = makeConnectedRepo(elmCtrl, pidParser = PidParser())
        repo.readFreezeFrame() to elmCtrl
    }

    @Test
    fun `readFreezeFrame sends correct mode-02 command format and parses response`() = withDefaultLocale(Locale.US) {
        val (result, elmCtrl) = freezeFrameRpmResult()

        coVerify { elmCtrl.sendCommand("02 0C 00") }  // space-separated for clone compatibility
        // Keyed by PID id ("010C"), not the English PidDefinition.name — the UI resolves the
        // localized label from the id via PidRegistry, so the repository must not bake in English.
        // Value uses one decimal (%.1f), not integer truncation. RPM formula: (26*256 + 248) / 4 = 1726.0.
        // EXACT, not contains("."): that held only by accident of the machine's locale (B35).
        assertEquals(mapOf("010C" to "1726.0 rpm"), result.filterKeys { it == "010C" })
    }

    @Test
    fun `readFreezeFrame formats the decimal with a comma on a German-locale device`() = withDefaultLocale(Locale.GERMANY) {
        val (result, _) = freezeFrameRpmResult()

        assertEquals("1726,0 rpm", result["010C"])
    }

    @Test
    fun `readFreezeFrame formats a percent PID without throwing`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("XX")
        // Engine load (0104) has the unit "%": it must not be read as a format specifier.
        coEvery { elmCtrl.sendCommand("02 04 00") } returns "42 04 80"
        val repo = makeConnectedRepo(elmCtrl, pidParser = PidParser())

        val result = repo.readFreezeFrame()

        val load = PidRegistry.all["0104"]!!.formula(byteArrayOf(0x80.toByte()))
        // Same default-locale formatting as production, so the test holds under a decimal comma too.
        assertEquals(String.format("%.1f", load) + " %", result["0104"])
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

    // ── Timeout-Richtlinie der einmaligen Lesevorgaenge (PRO-13) ──────────────
    //
    // Ein Zeitlimit laesst den Datenstrom in unbekanntem Zustand (die verspaetete Antwort kann beim naechsten Befehl eintreffen).
    // Fehlercodes UND Freeze-Frame brechen deshalb beim ersten Zeitlimit ab und trennen (der Reconnect setzt den Strom zurueck); die
    // Live-Abfrage ueberspringt den PID und wird vom Watchdog ueberwacht; die FIN trennt bewusst NICHT (s. unten).

    @Test
    fun `readFreezeFrame stops at the first timeout, disconnects and rethrows - like readDtcs`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.Timeout("02 xx 00")
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try { repo.readFreezeFrame() } catch (_: ObdException.Timeout) { threw = true }

        assertTrue("das Zeitlimit wird gemeldet, nicht als leeres Ergebnis verschluckt", threw)
        assertEquals(false, repo.isConnectedFlow.value)
        // Vorher lief die Schleife mit jedem der ~40 PIDs weiter — bei einem stillen Adapter 40 x 5 s, und jede spaete Antwort
        // landete beim naechsten Befehl.
        // (Die drei Befehle der Discovery beim Verbinden zaehlen nicht mit.)
        coVerify(exactly = 1) { elmCtrl.sendCommand(match { it.startsWith("02 ") }) }
    }

    @Test
    fun `readFreezeFrame still skips a PID the ECU answers with NO DATA - only a timeout aborts`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("XX")
        coEvery { elmCtrl.sendCommand("02 0C 00") } returns "42 0C 1A F8"
        val repo = makeConnectedRepo(elmCtrl, pidParser = PidParser())

        assertTrue(repo.readFreezeFrame().containsKey("010C"))
        assertEquals("der Adapter hat geantwortet: die Verbindung bleibt", true, repo.isConnectedFlow.value)
    }

    @Test
    fun `readFreezeFrame skips a PID with an unreadable answer or a bus error and keeps the connection`() = runTest {
        // Der Adapter HAT geantwortet: ein einmaliger Lesevorgang eskaliert das nicht zu ConnectionLost, die uebrigen PIDs kommen weiter durch.
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("XX")
        coEvery { elmCtrl.sendCommand("02 05 00") } throws ObdException.UnknownResponse("?")
        coEvery { elmCtrl.sendCommand("02 0D 00") } throws ObdException.BusInit("BUS INIT: ERROR")
        coEvery { elmCtrl.sendCommand("02 0C 00") } returns "42 0C 1A F8"
        val repo = makeConnectedRepo(elmCtrl, pidParser = PidParser())

        val result = repo.readFreezeFrame()

        assertEquals(setOf("010C"), result.keys)
        assertEquals("der Adapter hat geantwortet: die Verbindung bleibt", true, repo.isConnectedFlow.value)
    }

    @Test
    fun `readVin does not disconnect when the adapter stays silent - the session asks for it at every connect`() = runTest {
        // Die Pipeline liest die FIN bei JEDEM Verbinden. Ein Motorrad, das auf Modus 09 schweigt, wuerde bei "trennen" in eine
        // Schleife aus Verbinden, Zeitlimit und Trennen geraten; die FIN ist ausserdem der erste Befehl nach der Initialisierung,
        // vor dem es keine verspaetete Antwort geben kann. Das Zeitlimit wird gemeldet, die Verbindung bleibt.
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("0902") } throws ObdException.Timeout("0902")
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try { repo.readVin() } catch (_: ObdException.Timeout) { threw = true }

        assertTrue(threw)
        assertEquals(true, repo.isConnectedFlow.value)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
    fun `livePids escalates to ConnectionLost after exactly 5 consecutive BusInit cycles`() = runTest {
        val elmCtrl = mockk<ElmController>()
        val pid = PidRegistry.defaults.first()
        var pidCommands = 0
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.BusInit()
        coEvery { elmCtrl.sendCommand(pid.id) } answers { pidCommands++; throw ObdException.BusInit() }
        val repo = makeConnectedRepo(elmCtrl)

        var threw = false
        try {
            repo.livePids(listOf(pid), pollIntervalMs = 0).collect {}
        } catch (_: ObdException.ConnectionLost) { threw = true }

        assertTrue(threw)
        // Nach unten stuetzt der Test "bleibt unter der Schwelle" (4 genuegen nicht); hier die Obergrenze: nicht erst nach 6 oder 50.
        assertEquals("ein PID je Zyklus, fuenf Zyklen", 5, pidCommands)
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

    // ── Eingefrorene Werte: Diagnose und Trennen (dropFrozenLink, s. FrozenValuesGuard) ─────

    private fun answeringElm(atrv: () -> String = { "13.8V" }): ElmController {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } answers {
            when (firstArg<String>()) {
                "010C" -> "41 0C 19 31"
                "0105" -> "41 05 77"
                "ATRV" -> atrv()
                else -> throw ObdException.NoData(firstArg())
            }
        }
        return elmCtrl
    }

    @Test
    fun `dropFrozenLink names the last raw answers and the adapter voltage and drops the link`() = runTest {
        val repo = makeConnectedRepo(answeringElm(), PidParser())
        repo.livePids(listOf(PidRegistry.RPM, PidRegistry.COOLANT_TEMP), pollIntervalMs = 1_000L, timeSource = testTimeSource).first()

        val details = repo.dropFrozenLink()

        assertTrue(details, details.contains("010C=41 0C 19 31"))
        assertTrue(details, details.contains("0105=41 05 77"))
        assertTrue(details, details.contains("ATRV 13.8V"))
        assertEquals("die Verbindung ist getrennt", false, repo.isConnectedFlow.value)
    }

    @Test
    fun `dropFrozenLink still drops the link when the adapter does not answer the voltage`() = runTest {
        val repo = makeConnectedRepo(answeringElm { throw ObdException.Timeout("ATRV") }, PidParser())
        repo.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 1_000L, timeSource = testTimeSource).first()

        val details = repo.dropFrozenLink()

        assertTrue(details, details.contains("ATRV n/a (Timeout)"))
        assertEquals(false, repo.isConnectedFlow.value)
    }

    @Test
    fun `dropFrozenLink keeps the details short and on one line`() = runTest {
        val repo = makeConnectedRepo(answeringElm { "13.8V\r\n\r\n" + "x".repeat(200) }, PidParser())
        repo.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 1_000L, timeSource = testTimeSource).first()

        val details = repo.dropFrozenLink()

        assertFalse(details, details.contains('\n') || details.contains('\r'))
        assertTrue("jede Antwort ist begrenzt: ${details.length}", details.length < 200)
    }

    @Test
    fun `dropFrozenLink without a link has nothing to drop`() = runTest {
        val repo = makeRepo()
        assertEquals("no adapter", repo.dropFrozenLink())
    }

    @Test
    fun `dropFrozenLink does not count as a disconnect by the user`() = runTest {
        val repo = makeConnectedRepo(answeringElm(), PidParser())
        repo.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 1_000L, timeSource = testTimeSource).first()
        val before = repo.userDisconnectCount.value

        repo.dropFrozenLink()

        assertEquals("sonst kaeme die Verbindung nicht von selbst zurueck", before, repo.userDisconnectCount.value)
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
        slowPids: List<PidDefinition> = emptyList(),
    ): Long? {
        val pollStart = currentTime
        return withTimeoutOrNull(windowMs) {
            try {
                repo.livePids(
                    pids,
                    pollIntervalMs = 1_000L,
                    watchdogTimeoutMs = 3_000L,
                    timeSource = testTimeSource,
                    slowPids = slowPids,
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

    // ── Langsame PIDs: jeder n-te Zyklus (Spannung fuer den Spannungsalarm) ─────────

    private val rpmAnswer = PidRegistry.RPM.id to "41 0C 1A F8"
    private val voltageAnswer = PidRegistry.CONTROL_MODULE_VOLTAGE.id to "41 42 30 39"

    /**
     * Ein verbundenes Repository, dessen Adapter [answer] auf jeden Befehl antwortet (Standard: Drehzahl und Spannung gueltig); alle
     * Befehle landen in [sent]. Die Discovery-Befehle zaehlen nicht mit (makeConnectedRepo registriert sie danach).
     */
    private suspend fun slowPidRepo(
        sent: MutableList<String> = mutableListOf(),
        answer: (String) -> String = { mapOf(rpmAnswer, voltageAnswer).getValue(it) },
    ): ObdRepository {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } answers {
            val command = firstArg<String>()
            sent += command
            answer(command)
        }
        return makeConnectedRepo(elmCtrl, PidParser())
    }

    /** Die PID-IDs der Messwerte der ersten [cycles] Zyklen, je Zyklus eine Liste. */
    private suspend fun ObdRepository.idsPerCycle(
        cycles: Int,
        slowPids: List<PidDefinition> = listOf(PidRegistry.CONTROL_MODULE_VOLTAGE),
        slowEveryCycles: Int = 4,
    ): List<List<String>> = livePids(
        listOf(PidRegistry.RPM), pollIntervalMs = 0L, slowPids = slowPids, slowEveryCycles = slowEveryCycles,
    ).take(cycles).toList().map { values -> values.map { it.pid.id } }

    @Test
    fun `livePids asks the slow PIDs in the first cycle and then every fourth one`() = runTest {
        val repo = slowPidRepo()

        val cycles = repo.idsPerCycle(cycles = 6)

        val withVoltage = listOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id)
        val rpmOnly = listOf(PidRegistry.RPM.id)
        assertEquals(
            "Zyklus 0 und 4 mit Spannung (der Zaehler beginnt bei 0: die erste Spannung kommt sofort), 1-3 und 5 ohne",
            listOf(withVoltage, rpmOnly, rpmOnly, rpmOnly, withVoltage, rpmOnly),
            cycles,
        )
    }

    @Test
    fun `livePids sends the slow PID command only in those cycles`() = runTest {
        val sent = mutableListOf<String>()
        val repo = slowPidRepo(sent)

        repo.idsPerCycle(cycles = 5)

        val rpm = PidRegistry.RPM.id
        val voltage = PidRegistry.CONTROL_MODULE_VOLTAGE.id
        assertEquals(listOf(rpm, voltage, rpm, rpm, rpm, rpm, voltage), sent)
    }

    @Test
    fun `livePids without slow PIDs queries exactly what it queried before`() = runTest {
        val sent = mutableListOf<String>()
        val repo = slowPidRepo(sent)

        val cycles = repo.livePids(listOf(PidRegistry.RPM), pollIntervalMs = 0L).take(5).toList()
            .map { values -> values.map { it.pid.id } }

        assertEquals(List(5) { listOf(PidRegistry.RPM.id) }, cycles)
        assertEquals(List(5) { PidRegistry.RPM.id }, sent)
    }

    @Test
    fun `livePids slow PIDs default to every fourth cycle`() = runTest {
        // slowEveryCycles bewusst weggelassen: der Standard ist ObdRepository.SLOW_PID_EVERY_CYCLES = 4.
        val repo = slowPidRepo()

        val cycles = repo.livePids(
            listOf(PidRegistry.RPM), pollIntervalMs = 0L, slowPids = listOf(PidRegistry.CONTROL_MODULE_VOLTAGE),
        ).take(5).toList().map { values -> values.size }

        assertEquals(4, ObdRepository.SLOW_PID_EVERY_CYCLES)
        assertEquals(listOf(2, 1, 1, 1, 2), cycles)
    }

    @Test
    fun `livePids with slowEveryCycles 1 asks the slow PIDs in every cycle`() = runTest {
        val repo = slowPidRepo()

        val cycles = repo.idsPerCycle(cycles = 3, slowEveryCycles = 1)

        assertEquals(
            List(3) { listOf(PidRegistry.RPM.id, PidRegistry.CONTROL_MODULE_VOLTAGE.id) },
            cycles,
        )
    }

    @Test
    fun `livePids rejects a slowEveryCycles below 1 instead of dividing by zero`() = runTest {
        val repo = slowPidRepo()

        for (invalid in listOf(0, -1)) {
            val error = runCatching { repo.idsPerCycle(cycles = 1, slowEveryCycles = invalid) }.exceptionOrNull()
            assertTrue("slowEveryCycles = $invalid must be rejected, got $error", error is IllegalArgumentException)
        }
    }

    @Test
    fun `a NoData of the slow PID costs nothing and is no watchdog event, the other values still come`() = runTest {
        // Die Spannung antwortet nie (NO DATA); Drehzahl laeuft. Jeder Zyklus liefert die Drehzahl, die Verbindung bleibt stehen.
        val repo = slowPidRepo(answer = { command ->
            if (command == PidRegistry.CONTROL_MODULE_VOLTAGE.id) throw ObdException.NoData(command)
            rpmAnswer.second
        })

        val cycles = repo.idsPerCycle(cycles = 9)

        assertEquals(List(9) { listOf(PidRegistry.RPM.id) }, cycles)
        assertTrue(repo.isConnectedFlow.value)
    }

    @Test
    fun `cycles of nothing but NoData, slow PIDs included, never trip the watchdog`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand(any()) } throws ObdException.NoData("pid")
        val repo = makeConnectedRepo(elmCtrl)

        assertNull(
            "NoData alone must never trip the watchdog, also when a slow PID is in the cycle (60 s window)",
            msUntilWatchdogEscalates(repo, listOf(PidRegistry.RPM), slowPids = listOf(PidRegistry.CONTROL_MODULE_VOLTAGE)),
        )
    }

    @Test
    fun `a BusInit on the slow PID suppresses that partial cycle like on any other PID`() = runTest {
        // Zyklus 0: die Drehzahl antwortet, die Spannung meldet BusInit — der Zyklus wird nicht halb ausgegeben. Die Zyklen 1-3 fragen die
        // Spannung nicht, liefern also wieder; der BusInit-Zaehler (5 in Folge) wird dazwischen zurueckgesetzt.
        val repo = slowPidRepo(answer = { command ->
            if (command == PidRegistry.CONTROL_MODULE_VOLTAGE.id) throw ObdException.BusInit()
            rpmAnswer.second
        })

        val cycles = repo.idsPerCycle(cycles = 3)

        assertEquals(List(3) { listOf(PidRegistry.RPM.id) }, cycles)
        assertTrue(repo.isConnectedFlow.value)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
        // Only bit 0 set → PID 0x60, which is the continuation flag and not in PidRegistry
        coEvery { elmCtrl.sendCommand("0100") } throws ObdException.NoData("0100")
        coEvery { elmCtrl.sendCommand("0120") } throws ObdException.NoData("0120")
        coEvery { elmCtrl.sendCommand("0140") } returns "41 40 00 00 00 01"

        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)

        assertNull(repo.supportedPidIds.value)
    }

    // B38/B8: Discovery mit den Meldungen, die ein ELM327 vor die Antwort stellt. Der erste OBD-Befehl nach ATSP0 (das
    // ist 0100) loest die Protokollsuche aus; "SEARCHING..." steht dann vor "41 00 ...". Bis 1.24.5 verlangte die
    // Auswertung "41" als allererstes Token — der ganze Bereich 01-20 ging verloren (keine Geschwindigkeit, Last, Drossel).

    private suspend fun discover(vararg answers: Pair<String, String>): Set<String>? {
        val device = mockk<ScannedDevice>()
        val conn = mockk<ObdConnection> {
            every { isConnected } returns true
            every { close() } just Runs
        }
        val elmCtrl = mockk<ElmController>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns conn }
        coEvery { conn.connect() } returns elmCtrl
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
        for (range in listOf("0100", "0120", "0140")) {
            coEvery { elmCtrl.sendCommand(range) } throws ObdException.NoData(range)
        }
        for ((command, answer) in answers) coEvery { elmCtrl.sendCommand(command) } returns answer
        val repo = ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())
        repo.connect(device)
        return repo.supportedPidIds.value
    }

    @Test
    fun `discovery tolerates the SEARCHING banner in front of the first answer`() = runTest {
        val supported = discover("0100" to "SEARCHING... 41 00 00 18 00 00")

        assertNotNull("der Bereich darf nicht verloren gehen", supported)
        assertTrue("010C (RPM)", "010C" in supported!!)
        assertTrue("010D (Speed)", "010D" in supported)
    }

    @Test
    fun `discovery tolerates SEARCHING and BUS INIT banners together`() = runTest {
        val supported = discover("0100" to "SEARCHING... BUS INIT: OK 41 00 00 18 00 00")

        assertTrue("010C" in supported!!)
    }

    @Test
    fun `discovery tolerates lower-case hex after a banner`() = runTest {
        val supported = discover("0100" to "searching... 41 00 00 18 00 00")

        assertTrue("010C" in supported!!)
    }

    @Test
    fun `discovery ignores a late answer that belongs to another range`() = runTest {
        // Die Antwort auf 0120 trifft verspaetet als Antwort auf 0100 ein: ihre Bits gehoeren zu PIDs 21-40.
        val supported = discover("0100" to "41 20 00 18 00 00")

        assertNull("kein Bereich-Verwechseln: 41 20 ist nicht die Antwort auf 0100", supported)
    }

    @Test
    fun `discovery gives up on a truncated answer instead of guessing`() = runTest {
        // Nur DREI statt vier Bitmasken-Bytes. Als Teilwert gelesen ergaebe 18 00 00 die PIDs 0C und 0D (RPM, Speed) —
        // eine geratene Antwort waere also nicht harmlos, sondern behauptete Faehigkeiten, die niemand gemeldet hat.
        val supported = discover("0100" to "SEARCHING... 41 00 18 00 00")

        assertNull(supported)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
        coEvery { elmCtrl.initialize() } returns InitResult("OK", 0)
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
    // initialize() sends exactly 5 AT commands: ATZ, ATE0, ATL0, ATS1, ATSP0.
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
            "OK",           // ATS1
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
        // Echte Threads (FakeObdConnection) unter runTest: Die virtuelle Zeit springt weiter, sobald der Test nur wartet —
        // die 60-s-Frist des Verbindungsaufbaus liefe sonst sofort ab. Hier ist nicht die Frist das Thema.
        repo.connectTimeoutMs = Long.MAX_VALUE
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
    fun `readVin decodes the mode 09 answer into the 17 character VIN`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("0902") } returns
            "014 0: 49 02 01 57 50 30 1: 5A 5A 5A 39 39 5A 54 2: 53 33 39 32 31 32 34"
        val repo = makeConnectedRepo(elmCtrl)

        assertEquals("WP0ZZZ99ZTS392124", repo.readVin())
    }

    @Test
    fun `readVin returns null instead of the raw adapter text when the vehicle has no mode 09`() = runTest {
        val elmCtrl = mockk<ElmController>()
        coEvery { elmCtrl.sendCommand("0902") } returns "NO DATA"
        val repo = makeConnectedRepo(elmCtrl)

        // Vorher stand "NO DATA" als FIN in der Fahrt und in der Verlaufsliste.
        assertNull(repo.readVin())
    }

    @Test
    fun `integration - disconnect clears state after FakeObdConnection connect`() = runTest {
        val fake    = FakeObdConnection()
        val device  = mockk<ScannedDevice>()
        val factory = mockk<ConnectionFactory> { coEvery { create(device) } returns fake }

        fake.queueConnectResponses()

        val repo = ObdRepository(factory, PidParser(), mockk<DtcParser>())
        repo.connectTimeoutMs = Long.MAX_VALUE   // echte Threads unter runTest, s. oben
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
