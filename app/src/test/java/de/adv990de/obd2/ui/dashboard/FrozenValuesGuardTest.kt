package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Die Entscheidung bei eingefrorenen Werten: einmal je Fall neu verbinden (vielleicht heilt der Adapter), danach — wenn es danach
 * wieder dasselbe ist — nicht in einer Schleife weiter verbinden, sondern die Werte zurueckhalten, damit die Oberflaeche "keine aktuellen
 * Werte" zeigt statt Zahlen, die niemand mehr misst.
 */
class FrozenValuesGuardTest {

    private val clock = TestTimeSource()
    private val min = 20.seconds

    private fun cycle(rpm: Float = 1612f, voltage: Float = 13.7f) = listOf(
        SensorValue(PidRegistry.RPM, rpm, timestamp = 1_000L),
        SensorValue(PidRegistry.COOLANT_TEMP, 79f, timestamp = 1_000L),
        SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, voltage, timestamp = 1_000L),
    )

    private fun newGuard() = FrozenValuesGuard().also { it.beginSession(clock, min) }

    /** Fuettert [n] gleiche Zyklen im Abstand von 1,5 s und liefert die Entscheidung fuer den letzten. */
    private fun feed(g: FrozenValuesGuard, n: Int, values: List<SensorValue> = cycle()): FrozenAction {
        var action: FrozenAction = FrozenAction.Pass
        repeat(n) { clock += 1_500.milliseconds; action = g.onCycle(values) }
        return action
    }

    @Test
    fun `values that change pass`() {
        val g = newGuard()
        repeat(100) { i ->
            clock += 1_500.milliseconds
            assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1500f + i)))
        }
    }

    @Test
    fun `the first frozen cycle asks for a reconnect and says how long the values have not moved`() {
        val g = newGuard()
        assertEquals(FrozenAction.Pass, feed(g, 19))
        clock += 1_500.milliseconds
        val action = g.onCycle(cycle())
        assertTrue("$action", action is FrozenAction.Reconnect)
        action as FrozenAction.Reconnect
        assertEquals(20, action.cycles)
        assertEquals(28_500.milliseconds, action.unchangedFor)   // 19 Abstaende von 1,5 s
    }

    @Test
    fun `a second frozen cycle in the same episode does not ask again`() {
        val g = newGuard()
        feed(g, 20)                                   // Reconnect
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Hold, g.onCycle(cycle()))
    }

    @Test
    fun `frozen again after the reconnect holds the values back instead of reconnecting in a loop`() {
        val g = newGuard()
        feed(g, 20)                                   // Reconnect, die Sitzung endet
        g.beginSession(clock, min)                    // die neue Sitzung nach dem Reconnect
        // die ersten Zyklen der neuen Sitzung laufen durch: bis zur Grenze weiss niemand, ob es wieder haengt
        assertEquals(FrozenAction.Pass, feed(g, 19))
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Hold, g.onCycle(cycle()))
    }

    @Test
    fun `held values come back as soon as one value moves, and the next freeze is a new episode`() {
        val g = newGuard()
        feed(g, 20)
        g.beginSession(clock, min)
        feed(g, 25)                                   // wieder eingefroren: Hold
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 2200f)))   // das Steuergeraet lebt wieder

        // ... und ein spaeteres Einfrieren darf wieder einmal neu verbinden: der Zyklus mit der Aenderung ist der erste des neuen Laufs,
        // es fehlen also 18 bis zum 19. und ein weiterer bis zum 20.
        assertEquals(FrozenAction.Pass, feed(g, 18, cycle(rpm = 2200f)))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle(rpm = 2200f)) is FrozenAction.Reconnect)
    }

    @Test
    fun `a reconnect that cures the freeze ends the episode - the values move in the new session`() {
        val g = newGuard()
        feed(g, 20)
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1000f)))
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1100f)))   // veraendert: Episode vorbei, dieser Zyklus ist der erste des neuen Laufs
        assertEquals(FrozenAction.Pass, feed(g, 18, cycle(rpm = 1100f)))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle(rpm = 1100f)) is FrozenAction.Reconnect)
    }

    @Test
    fun `stopping the engine ends the episode`() {
        val g = newGuard()
        feed(g, 20)
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 0f)))   // Motor aus
        assertEquals(FrozenAction.Pass, feed(g, 19))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle()) is FrozenAction.Reconnect)
    }

    @Test
    fun `a new session starts counting from zero - the run of the old session is not carried over`() {
        val g = newGuard()
        feed(g, 15)                                   // fast eingefroren
        g.beginSession(clock, min)
        assertEquals(FrozenAction.Pass, feed(g, 19))  // 19 Zyklen der neuen Sitzung: noch nicht
    }

    @Test
    fun `the time limit follows the session - a longer limit needs longer frozen values`() {
        val g = FrozenValuesGuard().also { it.beginSession(clock, 60.seconds) }
        assertEquals(FrozenAction.Pass, feed(g, 40))   // 40 Zyklen, 58,5 s
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle()) is FrozenAction.Reconnect)   // 41. Zyklus bei 60,0 s
    }
}
