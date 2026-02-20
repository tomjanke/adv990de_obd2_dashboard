package de.adv990de.obd2.data

import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ObdConnection
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdRepositoryTest {

    private fun makeRepo(factory: ConnectionFactory = mockk()): ObdRepository =
        ObdRepository(factory, mockk<PidParser>(), mockk<DtcParser>())

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
}
