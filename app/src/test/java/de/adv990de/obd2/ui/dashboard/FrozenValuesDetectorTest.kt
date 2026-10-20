package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Eingefrorene Werte (Fahrten 52 und 60 des Nutzers): das Steuergeraet oder der Adapter antwortet weiter, aber mit demselben Wert, minutenlang.
 * Die Tests nennen ihre Zahlen absolut — 20 Zyklen und 20 s — statt gegen die Konstanten zu rechnen; die Daten der echten Fahrten erlauben
 * hoechstens 5 identische Zyklen am Stueck (rund 6 s).
 */
class FrozenValuesDetectorTest {

    private val clock = TestTimeSource()

    private fun v(pid: de.adv990de.obd2.obd.model.PidDefinition, value: Float) = SensorValue(pid, value, timestamp = 1_000L)

    private fun cycle(rpm: Float? = 1612f, coolant: Float? = 79f, voltage: Float? = 13.7f): List<SensorValue> = listOfNotNull(
        rpm?.let { v(PidRegistry.RPM, it) },
        coolant?.let { v(PidRegistry.COOLANT_TEMP, it) },
        voltage?.let { v(PidRegistry.CONTROL_MODULE_VOLTAGE, it) },
    )

    private fun detector(minDuration: Duration = 20.seconds, minCycles: Int = 20) = FrozenValuesDetector(clock, minDuration, minCycles)

    /** Fuettert [n] gleiche Zyklen im Abstand [step] (der erste ohne Wartezeit davor) und liefert den Zustand des letzten. */
    private fun feed(d: FrozenValuesDetector, n: Int, values: List<SensorValue> = cycle(), step: Duration = 1_500.milliseconds): FrozenState {
        var state = FrozenState.IGNORED
        repeat(n) { i ->
            if (i > 0) clock += step
            state = d.update(values)
        }
        return state
    }

    @Test
    fun `an unchanging stream is frozen once both the cycles and the time are reached`() {
        val d = detector()
        assertEquals(FrozenState.UNCHANGED, feed(d, 19))   // 19 Zyklen, 27 s: Zeit genug, Zyklen noch nicht
        clock += 1_500.milliseconds
        assertEquals(FrozenState.FROZEN, d.update(cycle())) // der 20. Zyklus bei 28,5 s
    }

    @Test
    fun `enough time but too few cycles is not frozen`() {
        val d = detector()
        // 5 s Abstand: nach 4 Zyklen sind 20 s um, aber erst der 20. Zyklus zaehlt
        assertEquals(FrozenState.UNCHANGED, feed(d, 19, step = 5.seconds))
        clock += 5.seconds
        assertEquals(FrozenState.FROZEN, d.update(cycle()))
    }

    @Test
    fun `enough cycles but too short a time is not frozen`() {
        val d = detector()
        // 0,5 s Abstand: 20 Zyklen sind nach 9,5 s da, die 20 s erst mit dem 41. Zyklus
        assertEquals(FrozenState.UNCHANGED, feed(d, 40, step = 500.milliseconds))   // 40 Zyklen, 19,5 s
        clock += 500.milliseconds
        assertEquals(FrozenState.FROZEN, d.update(cycle()))                         // genau 20,0 s: die Grenze zaehlt
    }

    @Test
    fun `a stream that changes in any one value is never frozen and every change restarts the run`() {
        for (changed in listOf(cycle(rpm = 1700f), cycle(coolant = 80f), cycle(voltage = 13.8f))) {
            val d = detector()
            assertEquals(FrozenState.UNCHANGED, feed(d, 19))
            clock += 1_500.milliseconds
            assertEquals(FrozenState.CHANGED, d.update(changed))
            assertEquals(1, d.cycles)
            // die veraenderte Zeile ist der erste Zyklus des neuen Laufs: es fehlen noch 19, nicht 20
            assertEquals(FrozenState.UNCHANGED, feed(d.also { clock += 1_500.milliseconds }, 18, values = changed))
            clock += 1_500.milliseconds
            assertEquals(FrozenState.FROZEN, d.update(changed))
        }
    }

    @Test
    fun `the first cycle starts a run`() {
        val d = detector()
        assertEquals(FrozenState.UNCHANGED, d.update(cycle()))
        assertEquals(1, d.cycles)
        assertEquals(Duration.ZERO, d.unchangedFor)
    }

    @Test
    fun `a frozen stream stays frozen and the counts keep growing`() {
        val d = detector()
        feed(d, 20)
        repeat(3) {
            clock += 1_500.milliseconds
            assertEquals(FrozenState.FROZEN, d.update(cycle()))
        }
        assertEquals(23, d.cycles)
        assertEquals(33_000.milliseconds, d.unchangedFor)   // 23 Zyklen im Abstand von 1,5 s: 22 Abstaende seit dem ersten
    }

    @Test
    fun `a PID that is missing from a cycle does not break the run - the voltage is read only now and then`() {
        val d = detector()
        var last = FrozenState.IGNORED
        repeat(25) { i ->
            if (i > 0) clock += 1_500.milliseconds
            last = d.update(cycle(voltage = if (i % 4 == 0) 13.7f else null))
        }
        assertEquals(FrozenState.FROZEN, last)
    }

    @Test
    fun `a PID that appears again with another value ends the run`() {
        val d = detector()
        feed(d, 10, values = cycle(voltage = 13.7f))
        clock += 1_500.milliseconds
        d.update(cycle(voltage = null))
        clock += 1_500.milliseconds
        assertEquals(FrozenState.CHANGED, d.update(cycle(voltage = 13.2f)))
    }

    @Test
    fun `a value that is not a number is no evidence either way`() {
        val d = detector()
        feed(d, 5)
        clock += 1_500.milliseconds
        // NaN zaehlt weder als Aenderung noch setzt es den Lauf zurueck; der Wert davor bleibt der Vergleich
        assertEquals(FrozenState.UNCHANGED, d.update(cycle(voltage = Float.NaN)))
        assertEquals(6, d.cycles)
        clock += 1_500.milliseconds
        assertEquals(FrozenState.UNCHANGED, d.update(cycle(voltage = 13.7f)))
        assertEquals(7, d.cycles)
        clock += 1_500.milliseconds
        assertEquals(FrozenState.CHANGED, d.update(cycle(voltage = 13.6f)))
    }

    @Test
    fun `a cycle without a rpm value is ignored and leaves the run as it was`() {
        val d = detector()
        feed(d, 10)
        clock += 1_500.milliseconds
        assertEquals(FrozenState.IGNORED, d.update(cycle(rpm = null)))
        assertEquals(10, d.cycles)
        clock += 1_500.milliseconds
        assertEquals(FrozenState.IGNORED, d.update(listOf(v(PidRegistry.RPM, Float.NaN))))
        assertEquals(10, d.cycles)
        clock += 1_500.milliseconds
        assertEquals(FrozenState.UNCHANGED, d.update(cycle()))
        assertEquals(11, d.cycles)
    }

    @Test
    fun `a stopped engine is never frozen - ignition on repeats its values for as long as it likes`() {
        val d = detector()
        repeat(100) { i ->
            if (i > 0) clock += 1_500.milliseconds
            assertEquals(FrozenState.ENGINE_OFF, d.update(cycle(rpm = 0f, voltage = 11.9f)))
        }
        assertEquals(0, d.cycles)
    }

    @Test
    fun `500 rpm is running, anything below is stopped`() {
        assertEquals(FrozenState.UNCHANGED, detector().update(cycle(rpm = 500f)))
        assertEquals(FrozenState.ENGINE_OFF, detector().update(cycle(rpm = 499.9f)))
    }

    @Test
    fun `stopping the engine ends the run and starting it again begins a new one`() {
        val d = detector()
        feed(d, 30)                                   // eingefroren
        clock += 1_500.milliseconds
        assertEquals(FrozenState.ENGINE_OFF, d.update(cycle(rpm = 0f)))
        clock += 1_500.milliseconds
        // dieselben Werte wie vor dem Abstellen: trotzdem ein neuer Lauf, keine Fortsetzung
        assertEquals(FrozenState.UNCHANGED, d.update(cycle()))
        assertEquals(1, d.cycles)
    }

    @Test
    fun `the first running cycle after a stop is the start of a run, not a change`() {
        // CHANGED heisst "der Strom lebt" — ein Neustart mit anderen Werten als vor dem Abstellen beweist das nicht
        val d = detector()
        feed(d, 5, values = cycle(rpm = 1612f))
        clock += 1_500.milliseconds
        d.update(cycle(rpm = 0f))
        clock += 1_500.milliseconds
        assertEquals(FrozenState.UNCHANGED, d.update(cycle(rpm = 2000f)))
        assertEquals(1, d.cycles)
    }

    @Test
    fun `a lively stream never freezes however long it runs`() {
        val d = detector()
        repeat(1_000) { i ->
            if (i > 0) clock += 1_300.milliseconds
            val state = d.update(cycle(rpm = 1500f + (i % 7) * 0.25f + (i % 11), voltage = 13.4f + (i % 3) * 0.1f))
            assertFalse("Zyklus $i: $state", state == FrozenState.FROZEN)
        }
    }

    @Test
    fun `repeats as long as the real rides show are not frozen`() {
        // Aus den 15 aufgezeichneten Fahrten: hoechstens 5 identische Zyklen am Stueck (rund 6 s), dann eine Aenderung
        val d = detector()
        repeat(200) { i ->
            if (i > 0) clock += 1_200.milliseconds
            val state = d.update(cycle(rpm = 1500f + (i / 6) * 25f))
            assertTrue("Zyklus $i: $state", state != FrozenState.FROZEN)
        }
    }
}
