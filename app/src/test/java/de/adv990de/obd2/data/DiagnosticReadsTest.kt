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

    // --- Die einmalige Diagnose nach einem fehlgeschlagenen Auslesen von Modus 03 (Backlog 2, 2026-10-26) ---

    /** Ein Controller, der auf jeden Befehl der Diagnose antwortet; [answers] ueberschreibt einzelne Antworten. */
    private fun diagnosticController(answers: Map<String, String> = emptyMap()): ElmController = mockk {
        val defaults = mapOf(
            "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            "ATDPN" to "A6",
            "0101" to "41 01 00 07 E5 00",
            "0100" to "41 00 BE 3F A8 13",
            "03" to "",
            "07" to "NO DATA",
            "0A" to "NO DATA",
        )
        (defaults + answers).forEach { (command, answer) -> coEvery { sendCommand(command) } returns answer }
    }

    @Test
    fun `the diagnosis writes the raw answer to every command, in order, on one line`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController())

        assertEquals(
            "ATDP='AUTO, ISO 15765-4 (CAN 11/500)' ATDPN='A6' 0101='41 01 00 07 E5 00' 0100='41 00 BE 3F A8 13' 03='' 07='NO DATA' 0A='NO DATA'",
            text,
        )
        assertTrue("eine Zeile", '\n' !in text)
        assertTrue("ein Erfolg trennt nie", brokenLinks.isEmpty())
    }

    @Test
    fun `an empty answer stays visible as two quotes - exactly what the first log line could not show`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController(mapOf("03" to "")))

        assertTrue(text, "03=''" in text)
    }

    @Test
    fun `a timeout ends the diagnosis, is marked, and never disconnects`() = runTest {
        val elm = diagnosticController()
        coEvery { elm.sendCommand("0100") } throws ObdException.Timeout("0100")

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "0100=<timeout>" in text)
        assertTrue("danach wird nichts mehr gefragt: $text", "03=" !in text && "07=" !in text && "0A=" !in text)
        assertTrue("die weiche Form trennt bei einem Zeitlimit nie", brokenLinks.isEmpty())
        coVerify(exactly = 0) { elm.sendCommand("03") }
        coVerify(exactly = 0) { elm.sendCommand("0A") }
    }

    @Test
    fun `a lost link is reported to the repository and passed on`() = runTest {
        val elm = diagnosticController()
        coEvery { elm.sendCommand("0101") } throws ObdException.ConnectionLost()

        val failure = runCatching { reads.diagnoseFaultRead(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $failure", failure is ObdException.ConnectionLost)
        assertEquals(listOf(elm), brokenLinks)
    }

    @Test
    fun `any other protocol error is noted for that command and the diagnosis goes on`() = runTest {
        val elm = diagnosticController()
        coEvery { elm.sendCommand("ATDPN") } throws ObdException.UnknownResponse("?")

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "ATDPN=<UnknownResponse>" in text)
        assertTrue("die folgenden Befehle laufen weiter: $text", "0A='NO DATA'" in text)
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `a very long answer is cut so that one answer cannot flood the log`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController(mapOf("0100" to "41 ".repeat(200))))

        val answer = Regex("0100='([^']*)'").find(text)!!.groupValues[1]
        assertTrue("Antwort ${answer.length} Zeichen", answer.length <= 200 + 3)
        assertTrue("gekuerzt markiert: $answer", answer.endsWith("..."))
    }

    @Test
    fun `the diagnosis only reads - adapter queries and modes 01, 03, 07 and 0A, never a clear or a write`() {
        val allowedModes = setOf("01", "03", "07", "0A")
        FAULT_READ_DIAGNOSTIC_COMMANDS.forEach { command ->
            val isAdapterQuery = command == "ATDP" || command == "ATDPN"
            assertTrue("Befehl $command ist weder Adapter-Abfrage noch ein Lesemodus", isAdapterQuery || command.take(2) in allowedModes)
        }
        assertTrue("Modus 04 (Fehlerspeicher loeschen) darf nie dabei sein", FAULT_READ_DIAGNOSTIC_COMMANDS.none { it.startsWith("04") })
        assertEquals(listOf("ATDP", "ATDPN", "0101", "0100", "03", "07", "0A"), FAULT_READ_DIAGNOSTIC_COMMANDS)
    }
}
