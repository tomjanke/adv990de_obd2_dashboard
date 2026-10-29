package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Glättung der Spannung über die Zyklen: **Median** der letzten 10 s (seit 1.44.9; vorher Mittel), ab drei Proben — nur nach dem ersten Verbinden. Nach jedem Wechsel des Motorzustands (Start, Abstellen) beginnt das Fenster neu und zeigt
 * die erste Probe sofort (Nutzerwunsch 2026-10-19: keine Lücke von rund 18 s); vor dem Start (Motor aus) mit `engineRunning = false`.
 * Der Takt der Tests ist 1 s je Zyklus (echt 1,1–1,6 s), damit die Fenstergrenzen glatte Zahlen sind.
 */
class VoltageTrendTrackerTest {

    private var clock = 0L
    private var stamp = 1L
    private val tracker = VoltageTrendTracker(now = { clock })

    /** Ein OBD-Zyklus nach [dtMs]; `null` lässt den jeweiligen Wert in diesem Zyklus weg. Jeder Wert bekommt einen neuen Zeitstempel. */
    private fun cycle(rpm: Float? = 1500f, volts: Float? = 13.6f, dtMs: Long = 1000L): VoltageTrend? {
        clock += dtMs
        val values = buildList {
            if (rpm != null) add(SensorValue(PidRegistry.RPM, rpm, timestamp = stamp++))
            if (volts != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volts, timestamp = stamp++))
        }
        return tracker.update(values, isObdCycle = true)
    }

    /** 40 Zyklen mit laufendem Motor: das Fenster (11 Proben bei 1 s Takt) ist voll und hat die drei Proben längst überschritten. */
    private fun settledEngine(volts: Float = 13.6f): VoltageTrend? {
        var last: VoltageTrend? = null
        repeat(40) { last = cycle(volts = volts) }
        return last
    }

    // --- Vor dem Start: verbunden, Motor aus --------------------------------------------------------------------
    // Der Balken zeigt dann schon die Spannung (Nutzerwunsch 2026-10-19), ohne Einschwingzeit und ohne Anspruch, die Lichtmaschine zu beschreiben: `engineRunning = false`.

    @Test
    fun `with the engine off the bar shows the voltage after three samples, without settle time, and says the engine is off`() {
        assertNull(cycle(rpm = 0f, volts = 11.9f))
        assertNull(cycle(rpm = 0f, volts = 11.9f))

        val trend = cycle(rpm = 0f, volts = 11.9f)!!

        assertEquals(11.9f, trend.volts, 1e-4f)
        assertEquals(ChargeState.Discharging, trend.state)
        assertFalse("Motor aus", trend.engineRunning)
    }

    @Test
    fun `below 500 rpm is an engine that is off - the bar still shows the voltage`() {
        repeat(2) { assertNull(cycle(rpm = 450f, volts = 12.4f)) }
        val trend = cycle(rpm = 499f, volts = 12.4f)!!
        assertFalse(trend.engineRunning)
        assertEquals(12.4f, trend.volts, 1e-4f)
    }

    @Test
    fun `a missing rpm from the very start shows the same pre-start bar`() {
        repeat(2) { assertNull(cycle(rpm = null, volts = 12.6f)) }
        val trend = cycle(rpm = null, volts = 12.6f)!!
        assertFalse(trend.engineRunning)
        assertEquals(12.6f, trend.volts, 1e-4f)
    }

    @Test
    fun `the pre-start state follows the smoothed value with the same hysteresis`() {
        fun hold(volts: Float): VoltageTrend {
            var last: VoltageTrend? = null
            repeat(12) { last = cycle(rpm = 0f, volts = volts) }
            return last!!
        }

        assertEquals(ChargeState.Discharging, hold(12.3f).state)
        assertEquals("12,75 V: noch entlaedt (Rueckkehr erst ab 12,8 V)", ChargeState.Discharging, hold(12.75f).state)
        assertEquals(ChargeState.Marginal, hold(12.8f).state)
        assertEquals(ChargeState.Charging, hold(13.3f).state)
        assertTrue(hold(13.3f).let { !it.engineRunning })
    }

    @Test
    fun `starting the engine restarts the window and shows the first running sample at once - no pre-start value mixes in`() {
        repeat(5) { cycle(rpm = 0f, volts = 11.9f) }
        assertNotNull("vor dem Start steht ein Stand", cycle(rpm = 0f, volts = 11.9f))

        // Der Motor läuft ab diesem Zyklus: der Balken zeigt sofort dessen Probe, ohne Lücke und ohne Rest der 11,9 V.
        val first = cycle(rpm = 1500f, volts = 13.8f)!!

        assertEquals("nur die Probe seit dem Start", 13.8f, first.volts, 1e-4f)
        assertTrue(first.engineRunning)
        assertEquals(ChargeState.Charging, first.state)
    }

    @Test
    fun `after the start the median grows from the samples since the start and the pre-start values stay out`() {
        repeat(6) { cycle(rpm = 0f, volts = 11.9f) }

        assertEquals(13.0f, cycle(rpm = 1500f, volts = 13.0f)!!.volts, 1e-4f)
        assertEquals("Median aus zwei Proben = Mittel der beiden", 13.5f, cycle(rpm = 1500f, volts = 14.0f)!!.volts, 1e-4f)
        assertEquals("Median aus drei Proben: der mittlere Wert", 13.5f, cycle(rpm = 1500f, volts = 13.5f)!!.volts, 1e-4f)
    }

    @Test
    fun `stopping the engine restarts the window and shows the first sample at once - no running value mixes in`() {
        assertNotNull(settledEngine(13.6f))

        val off = cycle(rpm = 0f, volts = 12.0f)!!

        assertEquals("kein Rest der 13,6 V", 12.0f, off.volts, 1e-4f)
        assertFalse(off.engineRunning)
        assertEquals(ChargeState.Discharging, off.state)
    }

    @Test
    fun `after stopping the median grows from the samples since the stop`() {
        settledEngine(13.6f)

        assertEquals(12.4f, cycle(rpm = 0f, volts = 12.4f)!!.volts, 1e-4f)
        assertEquals("Median aus zwei Proben = Mittel der beiden", 12.3f, cycle(rpm = 0f, volts = 12.2f)!!.volts, 1e-4f)
    }

    @Test
    fun `start and stop leave no gap - every cycle with a voltage shows the bar from the switch on`() {
        repeat(4) { cycle(rpm = 0f, volts = 11.9f) }
        assertNotNull("vor dem Start (nach drei Proben)", cycle(rpm = 0f, volts = 11.9f))

        // Start, ein langer Lauf, Abstellen, wieder an: kein einziger Zyklus ohne Balken.
        repeat(30) { assertNotNull("laufend, Zyklus ${it + 1}", cycle(rpm = 1500f, volts = 13.6f)) }
        repeat(30) { assertNotNull("nach dem Abstellen, Zyklus ${it + 1}", cycle(rpm = 0f, volts = 12.0f)) }
        repeat(30) { assertNotNull("zweiter Lauf, Zyklus ${it + 1}", cycle(rpm = 1500f, volts = 13.6f)) }
    }

    @Test
    fun `a switch cycle without a voltage shows nothing until the next voltage arrives`() {
        settledEngine()

        assertNull("Abstellen ohne Spannung im Zyklus: nichts zu zeigen", cycle(rpm = 0f, volts = null))
        val next = cycle(rpm = 0f, volts = 12.1f)!!
        assertEquals(12.1f, next.volts, 1e-4f)
        assertFalse(next.engineRunning)
    }

    @Test
    fun `a connection that starts while the engine is already running shows the first sample at once`() {
        // Verbinden unterwegs: der Motor läuft schon, der Balken wartet nicht auf eine Einschwingzeit.
        val first = cycle(rpm = 2500f, volts = 13.7f)!!

        assertEquals(13.7f, first.volts, 1e-4f)
        assertTrue(first.engineRunning)
    }

    @Test
    fun `a window that has been established needs three samples again after it has drained`() {
        // Nach einem Wechsel zeigt schon die erste Probe; sind drei erreicht, gilt wieder die Regel „ab drei Proben“ — ein Rest von ein oder zwei Proben im Fenster zeigt nichts.
        repeat(3) { assertNotNull(cycle(rpm = 1500f, volts = 13.6f)) }
        repeat(8) { assertNotNull("ohne Spannung, Zyklus ${it + 1}", cycle(volts = null)) }
        assertNull("nur noch zwei Proben", cycle(volts = null))
        assertNull("nur noch eine Probe", cycle(volts = null))
    }

    @Test
    fun `before the start zero volts and values that are not a number are no samples either`() {
        repeat(10) { assertNull(cycle(rpm = 0f, volts = 0f)) }
        repeat(10) { assertNull(cycle(rpm = 0f, volts = Float.NaN)) }
        repeat(10) { assertNull(cycle(rpm = 0f, volts = Float.POSITIVE_INFINITY)) }

        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNotNull(cycle(rpm = 0f, volts = 12.2f))
    }

    @Test
    fun `before the start a repeated sample counts once`() {
        val repeated = SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 12.2f, timestamp = 999_999L)

        repeat(5) {
            clock += 1000
            val shown = tracker.update(listOf(SensorValue(PidRegistry.RPM, 0f, timestamp = stamp++), repeated), isObdCycle = true)
            assertNull("eine Probe, auch fuenfmal zugestellt, sind keine drei", shown)
        }
    }

    @Test
    fun `before the start a GPS refresh changes nothing`() {
        repeat(3) { cycle(rpm = 0f, volts = 12.2f) }
        val before = cycle(rpm = 0f, volts = 12.2f)!!

        clock += 500
        val repeated = tracker.update(
            listOf(SensorValue(PidRegistry.RPM, 3000f, timestamp = stamp++), SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 9.0f, timestamp = stamp++)),
            isObdCycle = false,
        )

        assertEquals(before, repeated)
        // Auch das Motor-Tor hat sich nicht bewegt: der naechste echte Zyklus (0 rpm) rechnet weiter im Vor-Start-Stand.
        assertFalse(cycle(rpm = 0f, volts = 12.2f)!!.engineRunning)
    }

    @Test
    fun `reset forgets the pre-start values too`() {
        repeat(4) { cycle(rpm = 0f, volts = 12.2f) }
        tracker.reset()

        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNotNull(cycle(rpm = 0f, volts = 12.2f))
    }

    @Test
    fun `there is no settle time for the display - the very first cycle of a running engine shows its sample`() {
        val first = cycle()

        assertNotNull(first)
        assertEquals(13.6f, first!!.volts, 1e-4f)
    }

    @Test
    fun `the trend is the median of the last 10 seconds, so a single outlier does not move it at all`() {
        settledEngine(13.8f)

        val withOutlier = cycle(volts = 12.0f)!!

        // 11 Proben im Fenster (Alter ≤ 10 s): 10 × 13,8 und 1 × 12,0 — der Median ist 13,8 (das Mittel waere 13,64).
        assertEquals(13.8f, withOutlier.volts, 1e-4f)
        assertEquals("ein Ausreißer ändert den Zustand nicht", ChargeState.Charging, withOutlier.state)
        assertTrue("laeuft der Motor, sagt der Stand es", withOutlier.engineRunning)
    }

    @Test
    fun `old samples fall out of the window - the median flips when the new samples are the majority`() {
        settledEngine(14.0f)

        // Fenster: 11 Proben (Alter ≤ 10 s). Nach fuenf neuen Proben (5 × 12,0 gegen 6 × 14,0) ist der Median noch 14,0 (das Mittel waere schon 13,09).
        var last: VoltageTrend? = null
        repeat(5) { last = cycle(volts = 12.0f) }
        assertEquals(14.0f, last!!.volts, 1e-4f)

        // Mit der sechsten (6 × 12,0 gegen 5 × 14,0) kippt der Median.
        assertEquals(12.0f, cycle(volts = 12.0f)!!.volts, 1e-4f)

        // Nach weiteren zwölf Zyklen steht nichts mehr von den 14 V im Fenster.
        repeat(12) { last = cycle(volts = 12.0f) }
        assertEquals(12.0f, last!!.volts, 1e-6f)
        assertEquals(ChargeState.Discharging, last!!.state)
    }

    @Test
    fun `an even number of samples gives the mean of the two middle ones`() {
        assertEquals(12.0f, cycle(volts = 12.0f)!!.volts, 1e-4f)
        assertEquals("zwei Proben: (12 + 13) / 2", 12.5f, cycle(volts = 13.0f)!!.volts, 1e-4f)
        assertEquals("drei Proben: der mittlere", 13.0f, cycle(volts = 14.0f)!!.volts, 1e-4f)
        assertEquals("vier Proben: (13 + 14) / 2", 13.5f, cycle(volts = 15.0f)!!.volts, 1e-4f)
    }

    @Test
    fun `the starter value that the control module keeps for several cycles does not decide the bar once most samples are good`() {
        // Nachgestellt nach einer aufgezeichneten Fahrt (erfundene Zahlen): Zündung an, dann haelt die Rohspannung den Anlasser-Wert (10,0 V) drei Zyklen lang, obwohl der Motor laeuft.
        repeat(6) { cycle(rpm = 0f, volts = 12.0f) }
        repeat(3) {
            val dip = cycle(rpm = 2000f, volts = 10.0f)!!
            assertEquals(10.0f, dip.volts, 1e-4f)
            assertEquals(ChargeState.Discharging, dip.state)
        }

        // Danach 13,9 / 13,3 / 13,3 / 13,3 V. Median der Proben seit dem Start: 10,0 · 10,0 · 11,65 (zwei Mittlere 10,0 und 13,3) · 13,3.
        assertEquals("4 Proben (10, 10, 10, 13,9)", 10.0f, cycle(rpm = 2000f, volts = 13.9f)!!.volts, 1e-4f)
        assertEquals("5 Proben", 10.0f, cycle(rpm = 2000f, volts = 13.3f)!!.volts, 1e-4f)
        val sixth = cycle(rpm = 2000f, volts = 13.3f)!!
        assertEquals("6 Proben: Mittel der zwei mittleren (10,0 und 13,3)", 11.65f, sixth.volts, 1e-4f)
        assertEquals(ChargeState.Discharging, sixth.state)
        val seventh = cycle(rpm = 2000f, volts = 13.3f)!!
        assertEquals("7 Proben: der Median ist schon ein guter Wert (das Mittel laege noch bei 11,97 V und rot)", 13.3f, seventh.volts, 1e-4f)
        assertEquals(ChargeState.Charging, seventh.state)
    }

    @Test
    fun `the state follows the smoothed value with the hysteresis`() {
        assertEquals(ChargeState.Charging, settledEngine(13.5f)!!.state)

        fun hold(volts: Float): ChargeState {
            var last: VoltageTrend? = null
            repeat(12) { last = cycle(volts = volts) }
            return last!!.state
        }

        assertEquals("13,25 V: noch lädt (Rückkehr erst unter 13,2 V)", ChargeState.Charging, hold(13.25f))
        assertEquals(ChargeState.Marginal, hold(13.1f))
        assertEquals("13,25 V: noch lädt kaum (Eintritt erst ab 13,3 V)", ChargeState.Marginal, hold(13.25f))
        assertEquals(ChargeState.Charging, hold(13.3f))
        assertEquals(ChargeState.Discharging, hold(12.3f))
        assertEquals("12,75 V: noch entlädt (Rückkehr erst ab 12,8 V)", ChargeState.Discharging, hold(12.75f))
        assertEquals(ChargeState.Marginal, hold(12.8f))
    }

    @Test
    fun `an engine that stops and starts again restarts the window each time and shows the first sample at once`() {
        assertNotNull(settledEngine(13.6f))

        assertEquals("0 rpm: der Motor ist aus", 12.0f, cycle(rpm = 0f, volts = 12.0f)!!.volts, 1e-4f)
        val again = cycle(rpm = 1500f, volts = 13.9f)!!
        assertEquals("Wiederanlauf: nur die neue Probe", 13.9f, again.volts, 1e-4f)
        assertTrue(again.engineRunning)
    }

    @Test
    fun `a cycle without an rpm does not end a running engine within 12 seconds`() {
        settledEngine()

        // Die letzte Drehzahl gilt 12 s (EngineGate.RPM_HOLD_MS): ein Zyklus, in dem die Drehzahl ins Zeitlimit lief, setzt nichts zurück.
        repeat(12) {
            val shown = cycle(rpm = null)
            assertNotNull("ohne Drehzahl, Zyklus ${it + 1}", shown)
            assertTrue("der Motor gilt weiter als laufend", shown!!.engineRunning)
        }
        // Nach 13 s ohne Drehzahl gilt der Motor als aus: das Fenster beginnt neu, der Balken zeigt die Probe dieses Zyklus.
        val off = cycle(rpm = null, volts = 12.0f)!!
        assertFalse(off.engineRunning)
        assertEquals(12.0f, off.volts, 1e-4f)
    }

    @Test
    fun `a GPS refresh changes nothing, neither the trend nor the engine`() {
        val before = settledEngine()!!

        // Ein Zwischenupdate wiederholt alte Werte (hier sogar mit 0 rpm): weder Fenster noch Motor-Tor dürfen sich bewegen.
        clock += 500
        val repeated = tracker.update(
            listOf(SensorValue(PidRegistry.RPM, 0f, timestamp = stamp++), SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 9.0f, timestamp = stamp++)),
            isObdCycle = false,
        )
        assertEquals(before, repeated)

        // Der nächste echte Zyklus rechnet weiter, als wäre nichts gewesen.
        assertNotNull(cycle())
    }

    @Test
    fun `a GPS refresh on a fresh tracker has no trend`() {
        assertNull(tracker.update(listOf(SensorValue(PidRegistry.RPM, 1500f, timestamp = 1)), isObdCycle = false))
    }

    @Test
    fun `a repeated sample counts once`() {
        // Motor laufen lassen, ohne dass eine Spannung zählt, dann denselben Wert (derselbe Zeitstempel) mehrmals zustellen.
        repeat(20) { cycle(volts = null) }
        val repeated = SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 13.6f, timestamp = 999_999L)

        repeat(5) {
            clock += 1000
            val shown = tracker.update(listOf(SensorValue(PidRegistry.RPM, 1500f, timestamp = stamp++), repeated), isObdCycle = true)
            assertEquals("die erste Zustellung zeigt, die weiteren ändern nichts", 13.6f, shown!!.volts, 1e-4f)
        }

        // Zählte die Wiederholung mit, läge das Mittel bei (5 × 13,6 + 11,0) / 6; so ist es der Mittelwert zweier Proben.
        assertEquals(12.3f, cycle(volts = 11.0f)!!.volts, 1e-4f)
    }

    @Test
    fun `zero volts and values that are not a number are no samples`() {
        repeat(20) { cycle(volts = null) }

        repeat(10) { assertNull(cycle(volts = 0f)) }
        repeat(10) { assertNull(cycle(volts = Float.NaN)) }
        repeat(10) { assertNull(cycle(volts = Float.POSITIVE_INFINITY)) }

        // Danach zählen echte Werte ganz normal: die erste zeigt sofort, ohne ein Mittel mit den ungültigen.
        assertEquals(12.9f, cycle(volts = 12.9f)!!.volts, 1e-4f)
    }

    @Test
    fun `a voltage that stops answering drains the window within 10 seconds`() {
        settledEngine()

        // Ohne Spannung bleibt der Motor an (die Drehzahl kommt), die Proben altern: nach acht Zyklen stehen noch drei im Fenster, mit dem neunten nur noch zwei.
        repeat(8) { assertNotNull("ohne Spannung, Zyklus ${it + 1}", cycle(volts = null)) }
        assertNull("weniger als drei Proben im Fenster: kein Balken", cycle(volts = null))
    }

    @Test
    fun `reset forgets everything, the engine gate included`() {
        assertNotNull(settledEngine(13.6f))

        tracker.reset()

        // Ohne Drehzahl gilt der Motor nach dem Zurücksetzen als aus (das Tor hat die 1500 rpm vergessen): wie beim ersten Verbinden braucht der Balken drei Proben, und keine 13,6 V sind übrig.
        assertNull(cycle(rpm = null, volts = 12.2f))
        assertNull(cycle(rpm = null, volts = 12.2f))
        val trend = cycle(rpm = null, volts = 12.2f)!!
        assertEquals(12.2f, trend.volts, 1e-4f)
        assertFalse(trend.engineRunning)
    }

    @Test
    fun `a reset right after a switch does not carry the one-sample rule into the next connection`() {
        // Der Motor startet, die erste Probe zeigt schon (Wechsel: ab einer Probe) — dann reißt die Verbindung ab.
        assertNotNull(cycle(rpm = 1500f, volts = 13.6f))
        tracker.reset()

        // Die neue Verbindung beginnt mit stehendem Motor wie ein erstes Verbinden: drei Proben, nicht eine.
        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNull(cycle(rpm = 0f, volts = 12.2f))
        assertNotNull(cycle(rpm = 0f, volts = 12.2f))
    }

    @Test
    fun `the state after a gap starts without memory of the old state`() {
        // lädt …
        assertEquals(ChargeState.Charging, settledEngine(13.6f)!!.state)
        // … Motor aus, wieder an, und der erste Wert liegt in der Totzone 13,2–13,3 V: ohne Vorgeschichte ist das „lädt kaum“, nicht „lädt“.
        cycle(rpm = 0f)
        var last: VoltageTrend? = null
        repeat(25) { last = cycle(volts = 13.25f) }

        assertEquals(ChargeState.Marginal, last!!.state)
    }
}
