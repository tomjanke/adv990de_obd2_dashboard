package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Der Zustand der Fahrzeugwerte (da / eingefroren / keine aktuellen) als Zeile der Fahrt und als Wechsel mit Dauer fuers Log. */
class ValuesStateTest {

    @Test
    fun `the numbers of the recording never change - the evaluation reads them`() {
        assertEquals(0f, valuesStateRecordValue(ValuesState.OK), 0f)
        assertEquals(1f, valuesStateRecordValue(ValuesState.FROZEN), 0f)
        assertEquals(2f, valuesStateRecordValue(ValuesState.SILENT), 0f)
        assertEquals(listOf("OK", "FROZEN", "SILENT"), ValuesState.entries.map { it.name })
    }

    @Test
    fun `the recording row is a derived value with the time of the change`() {
        val row = valuesStateSensorValue(ValuesState.FROZEN, timestamp = 123_456L)

        assertEquals(DerivedPids.VALUES_STATE, row.pid)
        assertEquals(1f, row.value, 0f)
        assertEquals(123_456L, row.timestamp)
        assertEquals(ValueSource.DERIVED, row.source)
    }

    @Test
    fun `a session starts with the values being there, and the same state again is no change`() {
        val tracker = ValuesStateTracker(TestTimeSource())

        assertEquals(ValuesState.OK, tracker.state)
        assertNull(tracker.enter(ValuesState.OK))
    }

    @Test
    fun `a change names where it came from, where it went and how long the previous state lasted`() {
        val clock = TestTimeSource()
        val tracker = ValuesStateTracker(clock)
        clock += 7.seconds

        val toFrozen = tracker.enter(ValuesState.FROZEN)!!
        assertEquals(ValuesStateChange(ValuesState.OK, ValuesState.FROZEN, 7.seconds), toFrozen)
        assertEquals(ValuesState.FROZEN, tracker.state)
        assertNull("noch einmal derselbe Zustand ist kein Wechsel", tracker.enter(ValuesState.FROZEN))

        clock += 480.seconds
        val back = tracker.enter(ValuesState.OK)!!
        assertEquals(ValuesStateChange(ValuesState.FROZEN, ValuesState.OK, 480.seconds), back)
    }

    @Test
    fun `reset puts the values back to there without a change to report`() {
        val clock = TestTimeSource()
        val tracker = ValuesStateTracker(clock)
        tracker.enter(ValuesState.SILENT)

        tracker.reset()

        assertEquals(ValuesState.OK, tracker.state)
        clock += 30.seconds
        assertEquals("die Dauer zaehlt ab dem Zuruecksetzen", 30.seconds, tracker.enter(ValuesState.FROZEN)!!.previousFor)
    }
}
