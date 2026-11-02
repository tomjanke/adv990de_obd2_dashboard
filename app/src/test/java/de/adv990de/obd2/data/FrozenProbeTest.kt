package de.adv990de.obd2.data

import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.model.ObdException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [probeUnusedPids], die Probe beim Erkennen eingefrorener Werte: jede der unbenutzten PIDs wird zweimal gelesen, und die Zeile sagt je PID, ob sich der Wert zwischen den
 * Lesungen bewegt hat. Eine einzelne Lesung sagt nichts — erst der Vergleich zeigt, ob das Steuergeraet hinter diesen PIDs lebt, waehrend die abgefragten stehen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrozenProbeTest {

    private fun elm(answer: (command: String, call: Int) -> String): ElmController {
        val calls = mutableMapOf<String, Int>()
        val controller = mockk<ElmController>()
        coEvery { controller.sendCommand(any()) } answers {
            val command = firstArg<String>()
            val call = calls.merge(command, 1, Int::plus)!!
            answer(command, call)
        }
        return controller
    }

    @Test
    fun `without PIDs there is nothing to read and the line says so`() = runTest {
        val controller = mockk<ElmController>()

        val line = probeUnusedPids(controller, emptyList())

        assertEquals("none", line)
        coVerify(exactly = 0) { controller.sendCommand(any()) }
    }

    @Test
    fun `every PID is read twice - the whole first round before the second`() = runTest {
        val controller = elm { _, _ -> "41 04 2A" }

        probeUnusedPids(controller, listOf("0104", "0111"), pauseMs = 1_000L)

        coVerifyOrder {
            controller.sendCommand("0104")
            controller.sendCommand("0111")
            controller.sendCommand("0104")
            controller.sendCommand("0111")
        }
        coVerify(exactly = 4) { controller.sendCommand(any()) }
    }

    @Test
    fun `the two rounds are one pause apart so a moving value has time to move`() = runTest {
        val controller = elm { _, _ -> "41 04 2A" }

        probeUnusedPids(controller, listOf("0104"), pauseMs = 1_000L)

        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `a value that changed between the rounds is moved`() = runTest {
        val controller = elm { _, call -> if (call == 1) "41 04 2A" else "41 04 2B" }

        val line = probeUnusedPids(controller, listOf("0104"))

        assertEquals("0104=41 04 2A -> 41 04 2B (moved)", line)
    }

    @Test
    fun `a value that stayed the same is same - not a statement about the cause`() = runTest {
        val controller = elm { _, _ -> "41 11 33" }

        val line = probeUnusedPids(controller, listOf("0111"))

        assertEquals("0111=41 11 33 -> 41 11 33 (same)", line)
    }

    @Test
    fun `each PID gets its own verdict in the order given`() = runTest {
        val controller = elm { command, call ->
            when (command) {
                "0104" -> if (call == 1) "41 04 2A" else "41 04 2B"
                "0111" -> "41 11 33"
                else -> "41 0B 63"
            }
        }

        val line = probeUnusedPids(controller, listOf("0104", "0111", "010B"))

        assertEquals(
            "0104=41 04 2A -> 41 04 2B (moved); 0111=41 11 33 -> 41 11 33 (same); 010B=41 0B 63 -> 41 0B 63 (same)",
            line,
        )
    }

    @Test
    fun `NO DATA twice is unclear - it is no answer and must not look like a frozen value`() = runTest {
        val controller = elm { _, _ -> "NO DATA" }

        val line = probeUnusedPids(controller, listOf("0104"))

        assertEquals("0104=NO DATA -> NO DATA (unclear)", line)
    }

    @Test
    fun `an answer for another PID is no answer for this one`() = runTest {
        // Ein verspaetetes Echo eines frueheren Befehls waere sonst "gleich" und sagte nichts ueber diese PID.
        val controller = elm { _, _ -> "41 0C 19 31" }

        val line = probeUnusedPids(controller, listOf("0104"))

        assertTrue(line, line.endsWith("(unclear)"))
    }

    @Test
    fun `a failed read is named, the other reads go on, and the verdict is unclear`() = runTest {
        val controller = elm { command, call ->
            if (command == "0104" && call == 1) throw ObdException.Timeout(command)
            "41 ${command.drop(2)} 10"
        }

        val line = probeUnusedPids(controller, listOf("0104", "0111"))

        assertEquals("0104=n/a (Timeout) -> 41 04 10 (unclear); 0111=41 11 10 -> 41 11 10 (same)", line)
    }

    @Test
    fun `answers are kept short and on one line`() = runTest {
        val controller = elm { _, _ -> "41 04 2A\r\n\r\n" + "x".repeat(200) }

        val line = probeUnusedPids(controller, listOf("0104"))

        assertFalse(line, line.contains('\n') || line.contains('\r'))
        assertTrue("jede Antwort ist begrenzt: ${line.length}", line.length < 120)
    }

    @Test
    fun `a stuck adapter ends the probe at the budget and the line shows what it got`() = runTest {
        val controller = elm { _, _ -> "41 04 2A" }
        // haengt: die Antwort kaeme nach zehn Sekunden, die Probe darf nicht so lange warten
        coEvery { controller.sendCommand("0111") } coAnswers {
            delay(10_000L)
            "41 11 33"
        }

        val line = probeUnusedPids(controller, listOf("0104", "0111"), pauseMs = 1_000L, budgetMs = 4_000L)

        assertEquals("die Probe endet an der Grenze, nicht erst nach der Antwort", 4_000L, currentTime)
        assertEquals("0104=41 04 2A -> - (unclear); 0111=- -> - (unclear)", line)
    }

    @Test
    fun `the budget also holds inside NonCancellable where the pipeline calls it`() = runTest {
        // dropFrozenLink() laeuft in NonCancellable (das Trennen bricht die Sitzung ab, die es ruft): die Grenze darf davon nicht aufgehoben werden.
        val controller = mockk<ElmController>()
        coEvery { controller.sendCommand(any()) } coAnswers {
            delay(60_000L)
            "41 04 2A"
        }

        val line = withContext(NonCancellable) { probeUnusedPids(controller, listOf("0104"), budgetMs = 4_000L) }

        assertEquals(4_000L, currentTime)
        assertEquals("0104=- -> - (unclear)", line)
    }
}
