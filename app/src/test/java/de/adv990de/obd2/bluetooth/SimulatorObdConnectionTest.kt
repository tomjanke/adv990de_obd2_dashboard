package de.adv990de.obd2.bluetooth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorObdConnectionTest {

    // ── State tests ───────────────────────────────────────────────────────────

    @Test
    fun `connect sets isConnected to true`() = runTest {
        val conn = SimulatorObdConnection()
        try {
            conn.connect()
            assertTrue("isConnected should be true after connect()", conn.isConnected)
        } finally {
            conn.close()
        }
    }

    @Test
    fun `double-connect throws IllegalStateException`() = runTest {
        val conn = SimulatorObdConnection()
        try {
            conn.connect()
            var threw = false
            try {
                conn.connect()
            } catch (_: IllegalStateException) {
                threw = true
            }
            assertTrue("Expected IllegalStateException on second connect()", threw)
        } finally {
            conn.close()
        }
    }

    @Test
    fun `close sets isConnected to false`() = runTest {
        val conn = SimulatorObdConnection()
        conn.connect()
        conn.close()
        assertFalse("isConnected should be false after close()", conn.isConnected)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val conn = SimulatorObdConnection()
        conn.connect()
        conn.close()
        conn.close() // should not throw
        assertFalse("isConnected should be false after close", conn.isConnected)
    }

    // ── Integration test ──────────────────────────────────────────────────────

    /**
     * Sends "0100" (supported PIDs range 01–20) through the real dispatcher loop
     * and verifies the response header matches OBD2 service 01 mode.
     *
     * The dispatcher responds with "41 00 <4 bitmap bytes>", so after ElmController
     * strips whitespace/newlines the result starts with "41 00".
     */
    @Test
    fun `sendCommand via connected ElmController returns valid response for 0100`() = runTest {
        val conn = SimulatorObdConnection()
        try {
            val elm = conn.connect()
            val response = elm.sendCommand("0100")
            assertTrue(
                "Expected response to start with '41 00', got: '$response'",
                response.startsWith("41 00")
            )
        } finally {
            conn.close()
        }
    }
}
