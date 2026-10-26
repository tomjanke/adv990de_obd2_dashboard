package de.adv990de.obd2.data

import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.model.ObdException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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

    // --- Die einmalige Diagnose nach einem fehlgeschlagenen Auslesen von Modus 03 (Backlog 2, 2026-10-26; zweite Runde 2026-10-26 nach dem ersten Befund am Motorrad) ---

    /** Die Befehle, die der Controller der Diagnose in dieser Reihenfolge bekommen hat. */
    private val sent = mutableListOf<String>()

    private val defaultAnswers = mapOf(
        "ATI" to "ELM327 v1.5",
        "AT@1" to "OBDII to RS232 Interpreter",
        "ATRV" to "12.4V",
        "ATIGN" to "ON",
        "0100" to "41 00 BE 3F A8 13",
        "0105" to "41 05 5A",
        "0101" to "41 01 00 07 E5 00",
        "03" to "",
        "0900" to "NO DATA",
        "ATSTFF" to "OK",
        "07" to "NO DATA",
        "ATST32" to "OK",
        "ATH1" to "OK",
        "ATH0" to "OK",
    )

    /**
     * Ein Controller, der auf jeden Befehl der Diagnose antwortet; [answers] ueberschreibt einzelne Antworten (fuer jedes Vorkommen), [failure] liefert fuer das n-te Vorkommen
     * (ab 1) eines Befehls eine Ausnahme — einige Befehle stehen mehrmals in der Liste (`0100`, `0101`, `03`).
     */
    private fun diagnosticController(
        answers: Map<String, String> = emptyMap(),
        failure: (command: String, occurrence: Int) -> Throwable? = { _, _ -> null },
    ): ElmController = mockk {
        val table = defaultAnswers + answers
        coEvery { sendCommand(any()) } answers {
            val command = firstArg<String>()
            sent += command
            failure(command, sent.count { it == command })?.let { throw it }
            table.getValue(command)
        }
    }

    @Test
    fun `the diagnosis writes the raw answer to every command, in order, on one line`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController())

        assertEquals(
            "ATI='ELM327 v1.5' AT@1='OBDII to RS232 Interpreter' ATRV='12.4V' ATIGN='ON' 0100='41 00 BE 3F A8 13' 0105='41 05 5A' " +
                "0101='41 01 00 07 E5 00' 03='' 0900='NO DATA' ATSTFF='OK' 0101='41 01 00 07 E5 00' 03='' 07='NO DATA' ATST32='OK' " +
                "ATH1='OK' 0100='41 00 BE 3F A8 13' 03='' ATH0='OK'",
            text,
        )
        assertEquals("die Befehle gehen in der Reihenfolge der Liste raus", FAULT_READ_DIAGNOSTIC_COMMANDS, sent)
        assertTrue("eine Zeile", '\n' !in text)
        assertTrue("ein Erfolg trennt nie", brokenLinks.isEmpty())
    }

    @Test
    fun `an empty answer stays visible as two quotes - exactly what the first log line could not show`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController(mapOf("03" to "")))

        assertTrue(text, "03=''" in text)
    }

    @Test
    fun `a timeout before any setting was changed ends the diagnosis, is marked, sends no restore and never disconnects`() = runTest {
        val elm = diagnosticController(failure = { command, _ -> if (command == "0105") ObdException.Timeout(command) else null })

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "0105=<timeout>" in text)
        assertTrue("danach wird nichts mehr gefragt: $text", "0101=" !in text && "ATH1" !in text && "ATSTFF" !in text)
        assertEquals("nichts war veraendert, nichts wird zurueckgesetzt", listOf("ATI", "AT@1", "ATRV", "ATIGN", "0100", "0105"), sent)
        assertTrue("die weiche Form trennt bei einem Zeitlimit nie", brokenLinks.isEmpty())
    }

    @Test
    fun `a timeout with the long wait still set ends the diagnosis and puts the wait back first`() = runTest {
        // Das zweite 0101 steht hinter ATSTFF: scheitert es, bleibt das lange Zeitlimit im Adapter, bis die Diagnose es zuruecksetzt.
        val elm = diagnosticController(failure = { command, n -> if (command == "0101" && n == 2) ObdException.Timeout(command) else null })

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "0101=<timeout>" in text)
        assertEquals("ATST32", sent.last())
        assertTrue("die Kopfzeilen wurden nie angeschaltet: $sent", "ATH1" !in sent)
        assertTrue("die Rueckstellung steht im Log: $text", text.endsWith("ATST32='OK'"))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `a timeout with the headers on still ends the diagnosis and turns the headers off again`() = runTest {
        // Das dritte 03 steht hinter ATH1. Bliebe ATH1 stehen, kaemen Kopfzeilen vor jede Antwort und kein Parser koennte sie mehr lesen.
        val elm = diagnosticController(failure = { command, n -> if (command == "03" && n == 3) ObdException.Timeout(command) else null })

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "03=<timeout>" in text)
        assertEquals("ATH0", sent.last())
        assertEquals("genau eine Rueckstellung", 1, sent.count { it == "ATH0" })
        assertTrue(text, text.endsWith("ATH0='OK'"))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `a timeout of the setting command itself is restored too - the adapter may have obeyed it`() = runTest {
        // Das Zeitlimit sagt nur, dass die Antwort fehlt, nicht, dass ATH1 nicht gewirkt hat.
        val elm = diagnosticController(failure = { command, _ -> if (command == "ATH1") ObdException.Timeout(command) else null })

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "ATH1=<timeout>" in text)
        assertEquals("ATH0", sent.last())
        assertTrue(text, text.endsWith("ATH0='OK'"))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `the restore survives a real cancellation of the caller`() = runTest {
        // Die Attrappe haelt beim dritten 03 (Kopfzeilen an) an und hat an jedem Befehl einen abbrechbaren Suspendierungspunkt wie das echte sendCommand.
        val completed = mutableListOf<String>()
        val elm = mockk<ElmController> {
            coEvery { sendCommand(any()) } coAnswers {
                val command = firstArg<String>()
                sent += command
                if (command == "03" && sent.count { it == "03" } == 3) awaitCancellation()
                delay(1)
                completed += command
                "OK"
            }
        }

        val job = launch { reads.diagnoseFaultRead(elm) }
        advanceUntilIdle()
        job.cancelAndJoin()

        assertTrue("der Rueckstellbefehl lief trotz des Abbruchs zu Ende: $completed", "ATH0" in completed)
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `a restore that cannot be sent disconnects so that the reconnect initialises the adapter again`() = runTest {
        val elm = diagnosticController(failure = { command, n ->
            when {
                command == "03" && n == 3 -> ObdException.Timeout(command)
                command == "ATH0" -> ObdException.Timeout(command)
                else -> null
            }
        })

        val failure = runCatching { reads.diagnoseFaultRead(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $failure", failure is ObdException.ConnectionLost)
        assertEquals("der Adapter steht in einem unbekannten Zustand: trennen", listOf(elm), brokenLinks)
    }

    @Test
    fun `a failed restore is also a lost link when it happens after the whole list ran`() = runTest {
        // Die Rueckstellung ATH0 steht am Ende der Liste selbst: scheitert sie dort mit einem Zeitlimit, bleibt sie ausstehend und die Diagnose versucht es noch einmal — auch das scheitert.
        val elm = diagnosticController(failure = { command, _ -> if (command == "ATH0") ObdException.Timeout(command) else null })

        val failure = runCatching { reads.diagnoseFaultRead(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $failure", failure is ObdException.ConnectionLost)
        assertEquals(listOf(elm), brokenLinks)
        assertEquals("ein zweiter Versuch, dann trennen", 2, sent.count { it == "ATH0" })
    }

    @Test
    fun `a lost link is reported to the repository and passed on, without a restore attempt on the dead link`() = runTest {
        val elm = diagnosticController(failure = { command, n -> if (command == "0100" && n == 2) ObdException.ConnectionLost() else null })

        val failure = runCatching { reads.diagnoseFaultRead(elm) }.exceptionOrNull()

        assertTrue("ConnectionLost erwartet, war: $failure", failure is ObdException.ConnectionLost)
        assertEquals(listOf(elm), brokenLinks)
        assertTrue("auf der toten Verbindung wird nichts mehr gesendet: $sent", sent.last() == "0100" && "ATH0" !in sent)
    }

    @Test
    fun `a cancelled caller still puts the adapter settings back`() = runTest {
        // Ein Slot-Wechsel bricht die Sitzung ab, die Verbindung bleibt: mit ATH1 im Adapter wuerde die naechste Sitzung nichts mehr verstehen.
        val elm = diagnosticController(failure = { command, n -> if (command == "03" && n == 3) CancellationException("cancelled") else null })

        val thrown = runCatching { reads.diagnoseFaultRead(elm) }.exceptionOrNull()

        assertTrue("CancellationException erwartet, war: $thrown", thrown is CancellationException)
        assertEquals("ATH0", sent.last())
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `any other protocol error is noted for that command and the diagnosis goes on`() = runTest {
        val elm = diagnosticController(failure = { command, _ -> if (command == "AT@1") ObdException.UnknownResponse("?") else null })

        val text = reads.diagnoseFaultRead(elm)

        assertTrue(text, "AT@1=<UnknownResponse>" in text)
        assertTrue("die folgenden Befehle laufen weiter: $text", text.endsWith("ATH0='OK'"))
        assertTrue(brokenLinks.isEmpty())
    }

    @Test
    fun `a very long answer is cut so that one answer cannot flood the log`() = runTest {
        val text = reads.diagnoseFaultRead(diagnosticController(mapOf("0900" to "49 ".repeat(200))))

        val answer = Regex("0900='([^']*)'").find(text)!!.groupValues[1]
        assertTrue("Antwort ${answer.length} Zeichen", answer.length <= 200 + 3)
        assertTrue("gekuerzt markiert: $answer", answer.endsWith("..."))
    }

    @Test
    fun `the diagnosis only reads - adapter commands and modes 01, 03, 07 and 09, never a clear or a write`() {
        val allowedModes = setOf("01", "03", "07", "09")
        // Befehle an den Adapter, die nur etwas abfragen oder eine Einstellung der Antwort aendern (die zurueckgesetzt wird): nie ein Reset (ATZ), nie ein Protokollwechsel (ATSP).
        val allowedAdapterCommands = setOf("ATI", "AT@1", "ATRV", "ATIGN", "ATSTFF", "ATST32", "ATH1", "ATH0")
        FAULT_READ_DIAGNOSTIC_COMMANDS.forEach { command ->
            val isAdapterCommand = command.startsWith("AT")
            assertTrue(
                "Befehl $command ist weder erlaubter Adapter-Befehl noch ein Lesemodus",
                if (isAdapterCommand) command in allowedAdapterCommands else command.take(2) in allowedModes,
            )
        }
        assertTrue("Modus 04 (Fehlerspeicher loeschen) darf nie dabei sein", FAULT_READ_DIAGNOSTIC_COMMANDS.none { it.startsWith("04") })
        assertEquals(
            listOf("ATI", "AT@1", "ATRV", "ATIGN", "0100", "0105", "0101", "03", "0900", "ATSTFF", "0101", "03", "07", "ATST32", "ATH1", "0100", "03", "ATH0"),
            FAULT_READ_DIAGNOSTIC_COMMANDS,
        )
    }

    @Test
    fun `every adapter setting the diagnosis changes is put back in the list itself, to the state of the initialisation`() {
        // Nach dem Init gelten Standardzeitlimit (ATST32, 200 ms) und Kopfzeilen aus (ATH0).
        assertEquals(mapOf("ATSTFF" to "ATST32", "ATH1" to "ATH0"), FAULT_READ_DIAGNOSTIC_RESTORES)
        FAULT_READ_DIAGNOSTIC_RESTORES.forEach { (change, restore) ->
            val changeAt = FAULT_READ_DIAGNOSTIC_COMMANDS.indexOf(change)
            val restoreAt = FAULT_READ_DIAGNOSTIC_COMMANDS.indexOf(restore)
            assertTrue("$change muss in der Liste stehen", changeAt >= 0)
            assertTrue("$restore muss hinter $change stehen", restoreAt > changeAt)
        }
    }
}
