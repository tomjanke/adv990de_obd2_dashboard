package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Glättung der Spannung über die Zyklen: nur bei laufendem Motor, erst nach der Einschwingzeit, Mittel der letzten 10 s, ab drei Proben.
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

    @Test
    fun `without a running engine there is no trend`() {
        repeat(30) { assertNull(cycle(rpm = 0f)) }
        repeat(30) { assertNull(cycle(rpm = 450f)) }
    }

    @Test
    fun `a missing rpm from the very start is no running engine`() {
        repeat(30) { assertNull(cycle(rpm = null)) }
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
