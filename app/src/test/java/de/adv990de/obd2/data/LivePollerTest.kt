package de.adv990de.obd2.data

import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TimeSource

/**
 * Die Abfrageschleife der Live-Werte ([LivePoller]) fuer sich: die Naht zum Repository (Controller je Zyklus erfragen, Rohantworten melden, eine
 * gebrochene Verbindung melden) und die Eskalation bei BusInit. Der Watchdog und das Zusammenspiel mit Verbindung und Lock stehen weiter in
 * `ObdRepositoryTest` (ueber `ObdRepository.livePids`).
 */
class LivePollerTest {

    private val rpm = PidRegistry.RPM
    private val coolant = PidRegistry.COOLANT_TEMP

    /** Die Verbindungen, die der Poller als gebrochen gemeldet hat — das Repository trennt daraufhin (nur wenn es noch die aktuelle ist). */
    private val brokenLinks = mutableListOf<ElmController>()
    private val rawCycles = mutableListOf<Map<String, String>>()

    private val parser = mockk<PidParser> {
        every { parse(any(), any()) } answers { SensorValue(firstArg(), 1f) }
    }

    private fun poller(controller: () -> ElmController?) = LivePoller(
        pidParser = parser,
        currentController = controller,
        onLinkBroken = { brokenLinks += it },
        onCycleRaw = { rawCycles += it },
    )

    /** Ohne Wartezeit zwischen den Zyklen und mit einem Watchdog, der in diesen Tests nie greift. */
    private fun LivePoller.pollWith(
        pids: List<PidDefinition> = listOf(rpm),
        slowPids: List<PidDefinition> = emptyList(),
        slowEveryCycles: Int = 4,
    ) = poll(pids, pollIntervalMs = 0, watchdogTimeoutMs = 60_000L, timeSource = TimeSource.Monotonic, slowPids = slowPids, slowEveryCycles = slowEveryCycles)

    private suspend fun thrownBy(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }

    @Test
    fun `without a controller the stream ends at once and emits nothing`() = runTest {
        val values = poller { null }.pollWith().toList()

        assertTrue(values.isEmpty())
        assertTrue(rawCycles.isEmpty())
    }

    @Test
    fun `a cycle emits the parsed values and hands the raw answers over per command`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(rpm.id) } returns "41 0C 1A F8"
        coEvery { elm.sendCommand(coolant.id) } returns "41 05 7B"

        val cycle = poller { elm }.pollWith(listOf(rpm, coolant)).first()

        assertEquals(listOf(rpm.id, coolant.id), cycle.map { it.pid.id })
        assertEquals(mapOf(rpm.id to "41 0C 1A F8", coolant.id to "41 05 7B"), rawCycles.first())
    }

    @Test
    fun `the controller is asked for again every cycle - a disconnect between two cycles ends the stream`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(rpm.id) } returns "41 0C 1A F8"
        var asked = 0

        val cycles = poller { if (asked++ == 0) elm else null }.pollWith().toList()

        assertEquals("ein Zyklus, dann ist die Verbindung weg", 1, cycles.size)
    }

    @Test
    fun `the slow pids join every nth cycle, the first one included`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(any()) } returns "41 00 00"

        val cycles = poller { elm }.pollWith(listOf(rpm), slowPids = listOf(coolant), slowEveryCycles = 2).take(3).toList()

        assertEquals(listOf(2, 1, 2), cycles.map { it.size })
    }

    @Test
    fun `a ConnectionLost from a command reports the controller and is rethrown`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(rpm.id) } throws ObdException.ConnectionLost()

        val thrown = thrownBy { poller { elm }.pollWith().toList() }

        assertTrue("der Verlust wird weitergereicht: $thrown", thrown is ObdException.ConnectionLost)
        assertSame(elm, brokenLinks.single())
    }

    @Test
    fun `five BusInit cycles in a row end with ConnectionLost and report the controller`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(rpm.id) } throws ObdException.BusInit()
        var asked = 0

        val thrown = thrownBy { poller { asked++; elm }.pollWith().toList() }

        assertTrue("der Bus gilt als haengend: $thrown", thrown is ObdException.ConnectionLost)
        assertEquals("genau fuenf Zyklen", 5, asked)
        assertSame(elm, brokenLinks.single())
    }

    @Test
    fun `a cycle interrupted by BusInit hands its partial answers over but emits nothing`() = runTest {
        val elm = mockk<ElmController>()
        coEvery { elm.sendCommand(rpm.id) } returns "41 0C 1A F8"
        coEvery { elm.sendCommand(coolant.id) } throws ObdException.BusInit()
        val emitted = mutableListOf<List<SensorValue>>()

        thrownBy { poller { elm }.pollWith(listOf(rpm, coolant)).collect { emitted += it } }

        assertTrue("ein Teilzyklus erreicht die Anzeige nie", emitted.isEmpty())
        assertEquals("der Teilzyklus steht trotzdem in den Rohantworten (Diagnose)", mapOf(rpm.id to "41 0C 1A F8"), rawCycles.first())
    }

    @Test
    fun `slowEveryCycles below one is refused`() = runTest {
        val thrown = thrownBy { poller { null }.pollWith(slowEveryCycles = 0).toList() }

        assertTrue("war: $thrown", thrown is IllegalArgumentException)
    }
}
