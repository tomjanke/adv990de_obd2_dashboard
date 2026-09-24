package de.adv990de.obd2.bluetooth

import java.io.PipedOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorObdConnectionTest {

    /**
     * Writes a raw line directly into [conn]'s dispatch-loop input, bypassing
     * [de.adv990de.obd2.obd.ElmController.sendCommand] (which always blocks waiting for a
     * response). Used to feed the dispatch loop a blank line without a response ever
     * being expected for it — exercising the `dispatchLoop()`'s `if (cmd.isBlank())
     * continue` branch, which real ELM327 adapters can trigger with stray line
     * terminators. Reaches [SimulatorObdConnection.toDispatcher] via reflection since
     * it is (rightly) a private implementation detail, not part of [ObdConnection].
     */
    private fun writeRawLine(conn: SimulatorObdConnection, line: String) {
        val field = SimulatorObdConnection::class.java.getDeclaredField("toDispatcher")
        field.isAccessible = true
        val toDispatcher = field.get(conn) as PipedOutputStream
        toDispatcher.write("$line\r".toByteArray(Charsets.US_ASCII))
        toDispatcher.flush()
    }

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

    // ── dispatchLoop() branch coverage ────────────────────────────────────────

    /**
     * A blank line ahead of a real command must be silently skipped by
     * `dispatchLoop()`'s `if (cmd.isBlank()) continue` guard — no response is written
     * for it, and the loop keeps reading until it hits the next real command. If the
     * guard were missing, the dispatcher would answer the blank line first and its
     * (bogus) response would be what `sendCommand("0100")` reads back instead of the
     * real "41 00 ..." answer.
     */
    @Test
    fun `blank line is skipped without producing a response and loop continues to next command`() = runTest {
        val conn = SimulatorObdConnection()
        try {
            val elm = conn.connect()
            writeRawLine(conn, "   ") // whitespace-only line: isBlank() == true, no command
            val response = elm.sendCommand("0100")
            assertTrue(
                "Expected response to start with '41 00' (blank line must not produce its own response), got: '$response'",
                response.startsWith("41 00")
            )
        } finally {
            conn.close()
        }
    }

    /**
     * Multiple distinct commands sent one after another over the same connection must
     * each get their own correct response, in order — not just a single request/response
     * cycle as covered by the existing '0100' integration test.
     */
    @Test
    fun `sequence of multiple commands over one connection each get correct distinct response in order`() = runTest {
        val conn = SimulatorObdConnection()
        try {
            val elm = conn.connect()

            val supportedPids = elm.sendCommand("0100")
            assertTrue(
                "Expected '0100' response to start with '41 00', got: '$supportedPids'",
                supportedPids.startsWith("41 00")
            )

            val rpm = elm.sendCommand("010C")
            assertTrue(
                "Expected '010C' response to start with '41 0C', got: '$rpm'",
                rpm.startsWith("41 0C")
            )

            val reset = elm.sendCommand("ATZ")
            assertEquals(
                "Expected 'ATZ' response to be the adapter banner",
                "ELM327 v1.5",
                reset
            )
        } finally {
            conn.close()
        }
    }
}
