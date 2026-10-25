package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Der Wechsel des Zustands der Fahrzeugwerte geht einmal ins Log und einmal als Zeile in die Fahrt — nie je Zyklus. */
class ValuesStateReporterTest {

    private val logged = mutableListOf<Pair<LogLevel, String>>()
    private val recorded = mutableListOf<List<SensorValue>>()
    private val clock = TestTimeSource()
    private val reporter = ValuesStateReporter(
        log = { level, message -> logged += level to message },
        record = { recorded += it },
    ).also { it.begin(clock) }

    private val held = listOf(
        SensorValue(PidRegistry.RPM, 1612f, 5L),
        SensorValue(PidRegistry.COOLANT_TEMP, 79f, 5L),
        SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 13.7f, 5L),
    )

    @Test
    fun `a session starts with the values being there and reports nothing`() {
        assertEquals(ValuesState.OK, reporter.state)
        assertTrue(logged.isEmpty())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `the freeze is logged as a warning with the held cycle and recorded as state 1`() = runTest {
        val changed = reporter.enter(ValuesState.FROZEN, timestamp = 1_000L, held = held)

        assertTrue(changed)
        assertEquals(ValuesState.FROZEN, reporter.state)
        assertEquals(1, logged.size)
        assertEquals(LogLevel.WARN, logged.single().first)
        assertEquals(
            "Values still frozen after the reconnect: holding them back (not shown, not recorded; GPS speed keeps being recorded). Held cycle: 010C=1612 0105=79 0142=13.7",
            logged.single().second,
        )
        val row = recorded.single().single()
        assertEquals(DerivedPids.VALUES_STATE, row.pid)
        assertEquals(VALUES_STATE_RECORD_FROZEN, row.value, 0f)
        assertEquals(1_000L, row.timestamp)
        assertEquals(ValueSource.DERIVED, row.source)
    }

    @Test
    fun `the same state again is no change - no log line and no row`() = runTest {
        reporter.enter(ValuesState.FROZEN, 1_000L, held)

        val again = reporter.enter(ValuesState.FROZEN, 2_000L, held)

        assertFalse(again)
        assertEquals(1, logged.size)
        assertEquals(1, recorded.size)
    }

    @Test
    fun `an unchanged first state OK is no change either`() = runTest {
        assertFalse(reporter.enter(ValuesState.OK, 1_000L))

        assertTrue(logged.isEmpty())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `no fresh values is an info line and state 2`() = runTest {
        reporter.enter(ValuesState.SILENT, timestamp = 7_000L)

        assertEquals(listOf(LogLevel.INFO to "No fresh values for 10 s (stale)"), logged)
        assertEquals(VALUES_STATE_RECORD_SILENT, recorded.single().single().value, 0f)
        assertEquals(7_000L, recorded.single().single().timestamp)
    }

    @Test
    fun `values moving again after a freeze say how long it lasted and that showing and recording resume`() = runTest {
        reporter.enter(ValuesState.FROZEN, 1_000L, held)
        logged.clear()
        clock += 480.seconds

        reporter.enter(ValuesState.OK, timestamp = 9_000L)

        assertEquals(listOf(LogLevel.INFO to "Values are moving again after 480 s: showing and recording resume"), logged)
        assertEquals(VALUES_STATE_RECORD_OK, recorded.last().single().value, 0f)
    }

    @Test
    fun `fresh values after a silent spell say how long it lasted`() = runTest {
        reporter.enter(ValuesState.SILENT, 1_000L)
        logged.clear()
        clock += 95.seconds

        reporter.enter(ValuesState.OK, 2_000L)

        assertEquals(listOf(LogLevel.INFO to "Fresh values again after 95 s"), logged)
    }

    @Test
    fun `whole numbers are written without a decimal point and fractions as they are`() = runTest {
        val cycle = listOf(
            SensorValue(PidRegistry.RPM, 1000f, 1L),
            SensorValue(PidRegistry.COOLANT_TEMP, 79.5f, 1L),
            SensorValue(PidRegistry.SPEED, 0f, 1L),
        )

        reporter.enter(ValuesState.FROZEN, 1L, cycle)

        assertTrue(logged.single().second.endsWith("Held cycle: 010C=1000 0105=79.5 010D=0"))
    }

    @Test
    fun `a freeze reported without a held cycle ends in an empty list`() = runTest {
        reporter.enter(ValuesState.FROZEN, 1L)

        assertTrue(logged.single().second.endsWith("Held cycle: "))
    }

    @Test
    fun `lasted counts from the last change`() = runTest {
        clock += 10.seconds
        reporter.enter(ValuesState.SILENT, 1L)
        clock += 42.seconds

        assertEquals(42.seconds, reporter.lasted())
    }

    @Test
    fun `begin starts over with the values being there, reports no change and uses the new clock`() = runTest {
        reporter.enter(ValuesState.FROZEN, 1L, held)
        logged.clear()
        recorded.clear()
        val newClock = TestTimeSource()

        reporter.begin(newClock)

        assertEquals(ValuesState.OK, reporter.state)
        assertTrue(logged.isEmpty())
        assertTrue(recorded.isEmpty())
        newClock += 33.seconds
        assertEquals(33.seconds, reporter.lasted())
    }
}
