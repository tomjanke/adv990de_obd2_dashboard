package de.adv990de.obd2.data

import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.model.ObdException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das weiche Auslesen der Fehlercodes ([DiagnosticReads.readDtcsOrSkip]): dieselben Befehle wie [DiagnosticReads.readDtcs], aber ein Zeitlimit
 * (und jede andere Nicht-Antwort) ergibt `null` und trennt nie — die vierte Form der Zeitlimit-Richtlinie (s. KDoc an `ObdRepository`).
 * Das harte `readDtcs` bleibt ueber das Repository in `ObdRepositoryTest` gesichert.
 */
class DiagnosticReadsTest {

    /** Die Verbindungen, die [DiagnosticReads] als gebrochen gemeldet hat (`onLinkBroken`) — das Repository trennt daraufhin. */
    private val brokenLinks = mutableListOf<ElmController>()

    private val reads = DiagnosticReads(mockk<PidParser>(), DtcParser(), onLinkBroken = { brokenLinks += it })

    private fun controller(confirmed: String? = "43 00", pending: String? = "47 00"): ElmController = mockk {
        confirmed?.let { coEvery { sendCommand("03") } returns it }
        pending?.let { coEvery { sendCommand("07") } returns it }
    }

    @Test
    fun `a successful soft read returns the same list as the hard read - confirmed and pending, one row per code`() = runTest {
        // P0134 steht in beiden Antworten: bestaetigt gewinnt und kommt einmal vor (die Liste dient als Schluessel).
        val elm = controller(confirmed = "43 01 33 01 34", pending = "47 01 34 01 35")

        val soft = reads.readDtcsOrSkip(elm)
        val hard = reads.readDtcs(elm)

        assertEquals(hard, soft)
        assertEquals(listOf("P0133", "P0134", "P0135"), soft!!.map { it.code })
        assertEquals(listOf(false, false, true), soft.map { it.isPending })
        assertTrue("ein Erfolg trennt nie", brokenLinks.isEmpty())
    }

    @Test
    fun `a real all-clear is an empty list, not null - only a missing answer is null`() = runTest {
        val soft = reads.readDtcsOrSkip(controller(confirmed = "43 00", pending = "NO DATA"))

        assertEquals(emptyList<Any>(), soft)
    }

    @Test
    fun `a timeout in mode 03 gives null and never disconnects`() = runTest {
        val elm = controller(confirmed = null)
        coEvery { elm.sendCommand("03") } throws ObdException.Timeout("03")

        val soft = reads.readDtcsOrSkip(elm)

        assertNull(soft)
        assertTrue("die weiche Form trennt bei einem Zeitlimit NIE (das harte readDtcs tut es)", brokenLinks.isEmpty())
        coVerify(exactly = 0) { elm.sendCommand("07") }
    }

    @Test
    fun `a timeout in mode 07 gives null as well, not the confirmed codes alone, and never disconnects`() = runTest {
        // Ein Zeitlimit laesst die Antwort-Reihenfolge im Strom offen: das Ergebnis waere unsicher, also keine Aussage.
        val elm = controller(confirmed = "43 01 33", pending = null)
        coEvery { elm.sendCommand("07") } throws ObdException.Timeout("07")

        assertNull(reads.readDtcsOrSkip(elm))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `NO DATA in mode 03 gives null, not an all-clear`() = runTest {
        val elm = controller(confirmed = "NO DATA", pending = "NO DATA")

        assertNull(reads.readDtcsOrSkip(elm))
        assertTrue(brokenLinks.isEmpty())
        coVerify(exactly = 0) { elm.sendCommand("07") }
    }

    @Test
    fun `a bus error in mode 03 gives null`() = runTest {
        val elm = controller(confirmed = "UNABLE TO CONNECT")

        assertNull(reads.readDtcsOrSkip(elm))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `an answer that is not a fault-code answer gives null`() = runTest {
        val elm = controller(confirmed = "?")

        assertNull(reads.readDtcsOrSkip(elm))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `silence in mode 07 stays an empty pending list - the confirmed codes are kept`() = runTest {
        val elm = controller(confirmed = "43 01 33", pending = "NO DATA")

        val soft = reads.readDtcsOrSkip(elm)

        assertEquals(listOf("P0133"), soft!!.map { it.code })
        assertEquals(listOf(false), soft.map { it.isPending })
    }

    @Test
    fun `a bus error in mode 07 gives null - only silence is tolerated there`() = runTest {
        val elm = controller(confirmed = "43 00", pending = "CAN ERROR")

        assertNull(reads.readDtcsOrSkip(elm))
    }

    @Test
    fun `a lost link disconnects with this controller and is passed on - like the hard read`() = runTest {
        val elm = controller(confirmed = null)
        coEvery { elm.sendCommand("03") } throws ObdException.ConnectionLost()

        val thrown = runCatching { reads.readDtcsOrSkip(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $thrown", thrown is ObdException.ConnectionLost)
        assertEquals("genau dieser Controller wird gemeldet (PRO-11)", 1, brokenLinks.size)
        assertSame(elm, brokenLinks.single())
    }

    @Test
    fun `a lost link in mode 07 disconnects and is passed on as well`() = runTest {
        val elm = controller(confirmed = "43 00", pending = null)
        coEvery { elm.sendCommand("07") } throws ObdException.ConnectionLost()

        val thrown = runCatching { reads.readDtcsOrSkip(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $thrown", thrown is ObdException.ConnectionLost)
        assertEquals(1, brokenLinks.size)
    }

    @Test
    fun `cancellation is never swallowed and never disconnects`() = runTest {
        val elm = controller(confirmed = null)
        coEvery { elm.sendCommand("03") } throws CancellationException("cancelled")

        val thrown = runCatching { reads.readDtcsOrSkip(elm) }.exceptionOrNull()

        assertTrue("CancellationException erwartet, war: $thrown", thrown is CancellationException)
        assertTrue(brokenLinks.isEmpty())
    }

    // --- Das harte readDtcs bleibt, wie es war (das gemeinsame Lesen wurde nur herausgezogen) ---

    @Test
    fun `the hard read still disconnects at a timeout and passes it on`() = runTest {
        val elm = controller(confirmed = null)
        coEvery { elm.sendCommand("03") } throws ObdException.Timeout("03")

        val thrown = runCatching { reads.readDtcs(elm) }.exceptionOrNull()

        assertTrue("Timeout erwartet, war: $thrown", thrown is ObdException.Timeout)
        assertEquals(listOf(elm), brokenLinks)
    }
}
