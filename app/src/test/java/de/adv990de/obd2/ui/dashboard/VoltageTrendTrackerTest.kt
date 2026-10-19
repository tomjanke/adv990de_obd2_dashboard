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
 * Die Glättung der Spannung über die Zyklen: Mittel der letzten 10 s, ab drei Proben. Bei laufendem Motor erst nach der Einschwingzeit, vor dem Start (Motor aus) sofort — dann mit `engineRunning = false`.
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

    /** 40 Zyklen mit laufendem Motor: die Einschwingzeit (15 s) ist vorbei und das Fenster (11 Proben bei 1 s Takt) voll. */
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
    fun `starting the engine empties the bar at once - the starter dip and the pre-start values never show`() {
        repeat(5) { cycle(rpm = 0f, volts = 11.9f) }
        assertNotNull("vor dem Start steht ein Stand", cycle(rpm = 0f, volts = 11.9f))

        // Der Motor laeuft ab k = 1: 15 s Einschwingzeit (Anlasser-Einbruch 9,0 V zaehlt nicht), dann drei Proben. Kein Rest der 11,9 V darf in der Zwischenzeit stehen.
        for (k in 1..17) assertNull("Zyklus $k nach dem Start", cycle(rpm = 1500f, volts = if (k <= 15) 9.0f else 13.8f))
        val running = cycle(rpm = 1500f, volts = 13.8f)!!

        assertEquals("nur laufende Proben im Mittel", 13.8f, running.volts, 1e-4f)
        assertTrue(running.engineRunning)
        assertEquals(ChargeState.Charging, running.state)
    }

    @Test
    fun `stopping the engine drops the running values at once and the bar waits for three fresh samples`() {
        assertNotNull(settledEngine(13.6f))

        assertNull(cycle(rpm = 0f, volts = 12.0f))
        assertNull(cycle(rpm = 0f, volts = 12.0f))
        val off = cycle(rpm = 0f, volts = 12.0f)!!

        assertEquals("kein Rest der 13,6 V", 12.0f, off.volts, 1e-4f)
        assertFalse(off.engineRunning)
        assertEquals(ChargeState.Discharging, off.state)
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
    fun `the first 15 seconds after the start show nothing and then three samples are needed`() {
        // Zyklus k liegt bei k s; der Motor läuft ab k = 1, die Einschwingzeit (15 s) ist bei k = 16 vorbei, die Proben 16, 17, 18 ergeben das erste Ergebnis.
        for (k in 1..17) assertNull("Zyklus $k", cycle())
        assertNotNull("Zyklus 18", cycle())
    }

    @Test
    fun `the trend is the mean of the last 10 seconds, so a single outlier hardly moves it`() {
        settledEngine(13.8f)

        val withOutlier = cycle(volts = 12.0f)!!

        // 11 Proben im Fenster (Alter ≤ 10 s): 10 × 13,8 und 1 × 12,0.
        assertEquals((10 * 13.8f + 12.0f) / 11f, withOutlier.volts, 1e-3f)
        assertEquals("ein Ausreißer ändert den Zustand nicht", ChargeState.Charging, withOutlier.state)
        assertTrue("laeuft der Motor, sagt der Stand es", withOutlier.engineRunning)
    }

    @Test
    fun `old samples fall out of the window`() {
        settledEngine(14.0f)
        repeat(2) { cycle(volts = 12.0f) }

        // Im Fenster: 8 × 14,0 und 3 × 12,0.
        val mixed = cycle(volts = 12.0f)!!
        assertEquals((8 * 14.0f + 3 * 12.0f) / 11f, mixed.volts, 1e-3f)

        // Nach weiteren zwölf Zyklen steht nichts mehr von den 14 V im Fenster.
        var last: VoltageTrend? = null
        repeat(12) { last = cycle(volts = 12.0f) }
        assertEquals(12.0f, last!!.volts, 1e-6f)
        assertEquals(ChargeState.Discharging, last.state)
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
    fun `an engine that stops clears the window at once and the next start waits for the settle time again`() {
        assertNotNull(settledEngine())

        assertNull("0 rpm: der Motor ist aus", cycle(rpm = 0f))
        for (k in 1..17) assertNull("nach dem Wiederanlauf, Zyklus $k", cycle())
        assertNotNull(cycle())
    }

    @Test
    fun `a cycle without an rpm does not end a running engine within 12 seconds`() {
        settledEngine()

        // Die letzte Drehzahl gilt 12 s (EngineGate.RPM_HOLD_MS): ein Zyklus, in dem die Drehzahl ins Zeitlimit lief, setzt nichts zurück.
        repeat(12) { assertNotNull("ohne Drehzahl, Zyklus ${it + 1}", cycle(rpm = null)) }
        assertNull("nach 13 s ohne Drehzahl gilt der Motor als aus", cycle(rpm = null))
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
        // Motor einschwingen lassen, ohne dass eine Spannung zählt, dann denselben Wert (derselbe Zeitstempel) mehrmals zustellen.
        repeat(20) { cycle(volts = null) }
        val repeated = SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, 13.6f, timestamp = 999_999L)

        repeat(5) {
            clock += 1000
            val shown = tracker.update(listOf(SensorValue(PidRegistry.RPM, 1500f, timestamp = stamp++), repeated), isObdCycle = true)
            assertNull("eine Probe, auch fünfmal zugestellt, sind keine drei", shown)
        }
    }

    @Test
    fun `zero volts and values that are not a number are no samples`() {
        repeat(20) { cycle(volts = null) }

        repeat(10) { assertNull(cycle(volts = 0f)) }
        repeat(10) { assertNull(cycle(volts = Float.NaN)) }
        repeat(10) { assertNull(cycle(volts = Float.POSITIVE_INFINITY)) }

        // Danach zählen echte Werte ganz normal.
        assertNull(cycle())
        assertNull(cycle())
        assertNotNull(cycle())
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
        assertNotNull(settledEngine())

        tracker.reset()

        for (k in 1..17) assertNull("nach reset, Zyklus $k", cycle())
        assertNotNull(cycle())
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
