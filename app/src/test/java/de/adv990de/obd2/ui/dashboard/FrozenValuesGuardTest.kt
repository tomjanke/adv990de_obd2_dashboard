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
 *
 * **Ein offener Fall geht in die naechste Sitzung ueber** (Fahrt vom 2026-10-28): die Werte, die eingefroren waren, werden mitgenommen, und schon der erste Zyklus
 * nach dem Neuverbinden — automatisch oder von Hand — wird damit verglichen. Vorher liefen die ersten 20 Zyklen (rund 30 s) mit denselben Werten still durch.
 */
class FrozenValuesGuardTest {

    private val clock = TestTimeSource()

    /** Kurz, damit die 12 Zyklen entscheiden: 12 Zyklen im Abstand von 1,5 s sind 16,5 s. */
    private val min = 10.seconds

    private fun cycle(rpm: Float = 1612f, voltage: Float = 13.7f) = listOf(
        SensorValue(PidRegistry.RPM, rpm, timestamp = 1_000L),
        SensorValue(PidRegistry.COOLANT_TEMP, 79f, timestamp = 1_000L),
        SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, voltage, timestamp = 1_000L),
    )

    private fun cycleWithoutVoltage() = cycle().filter { it.pid.id != PidRegistry.CONTROL_MODULE_VOLTAGE.id }

    private fun cycleWithoutRpm() = listOf(SensorValue(PidRegistry.COOLANT_TEMP, 79f, timestamp = 1_000L))

    private fun newGuard() = FrozenValuesGuard().also { it.beginSession(clock, min) }

    /** Fuettert [n] gleiche Zyklen im Abstand von 1,5 s und liefert die Entscheidung fuer den letzten. */
    private fun feed(g: FrozenValuesGuard, n: Int, values: List<SensorValue> = cycle()): FrozenAction {
        var action: FrozenAction = FrozenAction.Pass
        repeat(n) { clock += 1_500.milliseconds; action = g.onCycle(values) }
        return action
    }

    /** Ein Wächter, der nach 12 gleichen Zyklen neu verbinden wollte; die Sitzung danach ist noch nicht begonnen. */
    private fun guardAfterReconnect(): FrozenValuesGuard = newGuard().also {
        assertTrue(feed(it, 12) is FrozenAction.Reconnect)
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
        assertEquals(FrozenAction.Pass, feed(g, 11))
        clock += 1_500.milliseconds
        val action = g.onCycle(cycle())
        assertTrue("$action", action is FrozenAction.Reconnect)
        action as FrozenAction.Reconnect
        assertEquals(12, action.cycles)
        assertEquals(16_500.milliseconds, action.unchangedFor)   // 11 Abstaende von 1,5 s
    }

    @Test
    fun `a second frozen cycle in the same episode does not ask again`() {
        val g = guardAfterReconnect()                 // Reconnect
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Hold, g.onCycle(cycle()))
    }

    // --- Die neue Sitzung nach dem Neuverbinden setzt den offenen Fall fort ---

    @Test
    fun `frozen again after the reconnect - the very first cycle of the new session is held back, not 20 cycles later`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)                    // die neue Sitzung nach dem Reconnect
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Hold, g.onCycle(cycle()))
        assertEquals(true, g.holding)
    }

    @Test
    fun `a first cycle after the reconnect that already differs passes at once and ends the episode`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1000f)))   // das Steuergeraet lebt wieder
        assertEquals(false, g.holding)

        // ... und ein spaeteres Einfrieren darf wieder einmal neu verbinden: der Zyklus mit der Aenderung ist der erste des neuen Laufs
        assertEquals(FrozenAction.Pass, feed(g, 10, cycle(rpm = 1000f)))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle(rpm = 1000f)) is FrozenAction.Reconnect)
    }

    @Test
    fun `a reconnect that cures the freeze ends the episode - the values move in the new session`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1000f)))
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 1100f)))   // veraendert: dieser Zyklus ist der erste des neuen Laufs
        assertEquals(FrozenAction.Pass, feed(g, 10, cycle(rpm = 1100f)))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle(rpm = 1100f)) is FrozenAction.Reconnect)
    }

    @Test
    fun `held values come back as soon as one value moves, and the next freeze is a new episode`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        assertEquals(FrozenAction.Hold, feed(g, 5))                      // weiter eingefroren: gehalten
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 2200f)))   // das Steuergeraet lebt wieder
        assertEquals(false, g.holding)

        assertEquals(FrozenAction.Pass, feed(g, 10, cycle(rpm = 2200f)))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle(rpm = 2200f)) is FrozenAction.Reconnect)
    }

    @Test
    fun `stopping the engine ends the episode`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 0f)))      // Motor aus
        assertEquals(false, g.holding)
        assertEquals(FrozenAction.Pass, feed(g, 11))
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle()) is FrozenAction.Reconnect)
    }

    @Test
    fun `the first cycle is compared with the frozen cycle per PID - a PID that is missing does not matter`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals("die Spannung fehlt: Drehzahl und Kuehlmittel sind dieselben", FrozenAction.Hold, g.onCycle(cycleWithoutVoltage()))
    }

    @Test
    fun `a value that differs in any one PID is life`() {
        for (alive in listOf(cycle(rpm = 1613f), cycle(voltage = 13.8f))) {
            val g = guardAfterReconnect()
            g.beginSession(clock, min)
            clock += 1_500.milliseconds
            assertEquals(FrozenAction.Pass, g.onCycle(alive))
        }
    }

    @Test
    fun `the user's reconnect during a hold continues the same episode - held again at once`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)                    // das automatische Neuverbinden: wieder eingefroren, gehalten
        assertEquals(FrozenAction.Hold, feed(g, 3))

        g.beginSession(clock, min)                    // der Nutzer trennt und verbindet von Hand
        assertEquals(true, g.holding)
        clock += 1_500.milliseconds
        assertEquals("keine 20 Zyklen stiller Durchlauf", FrozenAction.Hold, g.onCycle(cycle()))
    }

    @Test
    fun `no episode is open before the first reconnect - a new session counts from zero`() {
        val g = newGuard()
        feed(g, 8)                                    // lange gleich, aber noch kein Neuverbinden
        g.beginSession(clock, min)
        assertEquals(false, g.holding)
        assertEquals(FrozenAction.Pass, feed(g, 11))  // 11 Zyklen der neuen Sitzung: noch nicht
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle()) is FrozenAction.Reconnect)
    }

    @Test
    fun `a new session after a cured episode starts normally - nothing is carried over`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 2200f)))   // geheilt

        g.beginSession(clock, min)                    // spaeter eine neue Sitzung
        assertEquals(false, g.holding)
        assertEquals(FrozenAction.Pass, feed(g, 11, cycle(rpm = 2200f)))
    }

    @Test
    fun `a new session after a stopped engine starts normally`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        clock += 1_500.milliseconds
        g.onCycle(cycle(rpm = 0f))                    // Motor aus beendet den Fall

        g.beginSession(clock, min)
        assertEquals(false, g.holding)
        assertEquals(FrozenAction.Pass, feed(g, 11))
    }

    @Test
    fun `the time limit follows the session - a longer limit needs longer frozen values`() {
        val g = FrozenValuesGuard().also { it.beginSession(clock, 60.seconds) }
        assertEquals(FrozenAction.Pass, feed(g, 40))   // 40 Zyklen, 58,5 s
        clock += 1_500.milliseconds
        assertTrue(g.onCycle(cycle()) is FrozenAction.Reconnect)   // 41. Zyklus bei 60,0 s
    }

    @Test
    fun `the time limit of the continued session does not delay the hold`() {
        val g = FrozenValuesGuard().also { it.beginSession(clock, 60.seconds) }
        feed(g, 41)                                    // Reconnect nach 60 s
        g.beginSession(clock, 60.seconds)
        clock += 1_500.milliseconds
        assertEquals("die 60 s sind im offenen Fall schon abgelaufen", FrozenAction.Hold, g.onCycle(cycle()))
    }

    // --- Sperre ("holding"): die Pipeline fragt, ob ein durchgelassener Zyklus das Ende der Sperre ist ---

    @Test
    fun `the guard says it is holding from the continued session on until a value moves`() {
        val g = newGuard()
        assertEquals(false, g.holding)
        feed(g, 12)                                   // Reconnect: noch keine Sperre
        assertEquals(false, g.holding)
        g.beginSession(clock, min)                    // die Sitzung setzt den offenen Fall fort
        assertEquals(true, g.holding)
        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Hold, g.onCycle(cycle()))
        assertEquals(true, g.holding)

        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 2200f)))
        assertEquals("ein bewegter Wert beendet die Sperre", false, g.holding)
    }

    @Test
    fun `stopping the engine ends the hold too`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min); feed(g, 3)
        assertEquals(true, g.holding)

        clock += 1_500.milliseconds
        assertEquals(FrozenAction.Pass, g.onCycle(cycle(rpm = 0f)))
        assertEquals(false, g.holding)
    }

    @Test
    fun `a cycle without a rpm value cannot prove the stream is alive - while holding it is held back`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min); feed(g, 3)
        assertEquals(true, g.holding)

        assertEquals(FrozenAction.Hold, g.onCycle(cycleWithoutRpm()))
        assertEquals("die Sperre bleibt", true, g.holding)
    }

    @Test
    fun `a continued session holds a first cycle without a rpm value too - it proves nothing`() {
        val g = guardAfterReconnect()
        g.beginSession(clock, min)
        assertEquals(FrozenAction.Hold, g.onCycle(cycleWithoutRpm()))
    }

    @Test
    fun `outside a hold a cycle without a rpm value passes as before`() {
        val g = newGuard()
        assertEquals(FrozenAction.Pass, g.onCycle(cycleWithoutRpm()))
        assertEquals(false, g.holding)
    }

    @Test
    fun `a session that follows no open episode never starts holding`() {
        val g = newGuard()
        feed(g, 5)
        g.beginSession(clock, min)

        assertEquals(false, g.holding)
        assertEquals(FrozenAction.Pass, g.onCycle(cycleWithoutRpm()))
    }
}
