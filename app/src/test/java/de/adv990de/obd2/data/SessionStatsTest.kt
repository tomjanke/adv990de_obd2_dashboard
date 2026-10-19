package de.adv990de.obd2.data

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Min und Max je Fahrt (F11): Kuehlmittel ungefiltert, Spannung nur bei laufendem Motor. Die Zeilen kommen in Aufzeichnungsreihenfolge
 * (`ORDER BY id`) mit dem Zeitstempel, den der Recorder schrieb.
 */
class SessionStatsTest {

    private val rpm = PidRegistry.RPM.id
    private val coolant = PidRegistry.COOLANT_TEMP.id
    private val volts = PidRegistry.CONTROL_MODULE_VOLTAGE.id

    /** Die Zeilen werden stabil nach Zeit geordnet (bei gleicher Zeit bleibt die angegebene Reihenfolge): so liest sich jeder Test wie eine Aufzeichnung. */
    private fun stats(vararg rows: Triple<Long, String, Float>): SessionStats {
        val acc = SessionStatsAccumulator()
        rows.sortedBy { it.first }.forEach { (t, pid, v) -> acc.add(t, pid, v) }
        return acc.result()
    }

    private fun row(seconds: Int, pid: String, value: Float) = Triple(seconds * 1_000L, pid, value)

    /** Der Motor laeuft ab Sekunde 0 und meldet jede Sekunde 1 200 rpm, bis einschliesslich [untilSeconds]. */
    private fun runningEngine(untilSeconds: Int): List<Triple<Long, String, Float>> = rpmRows(0, untilSeconds)

    /** Eine Drehzahlzeile je Sekunde von [fromSeconds] bis einschliesslich [untilSeconds] — so dicht kommt sie in jedem Zyklus. */
    private fun rpmRows(fromSeconds: Int, untilSeconds: Int, value: Float = 1_200f): List<Triple<Long, String, Float>> =
        (fromSeconds..untilSeconds).map { row(it, rpm, value) }

    @Test
    fun `a trip without any reading has no values`() {
        val result = stats()
        assertNull(result.coolant)
        assertNull(result.voltage)
    }

    @Test
    fun `the coolant range is the lowest and the highest value of the trip`() {
        val result = stats(row(0, coolant, 18f), row(1, coolant, 40f), row(2, coolant, 92f), row(3, coolant, 88f))
        assertEquals(MinMax(18f, 92f), result.coolant)
    }

    @Test
    fun `a single coolant reading is both minimum and maximum`() {
        assertEquals(MinMax(77f, 77f), stats(row(0, coolant, 77f)).coolant)
    }

    @Test
    fun `negative coolant values count as they are`() {
        assertEquals(MinMax(-5f, 60f), stats(row(0, coolant, -5f), row(1, coolant, 60f)).coolant)
    }

    @Test
    fun `the coolant counts without a running engine - the minimum is the start temperature`() {
        // Keine einzige Drehzahlzeile: das Kuehlmittel wird trotzdem gewertet.
        assertEquals(MinMax(12f, 12f), stats(row(0, coolant, 12f)).coolant)
    }

    @Test
    fun `a coolant value that is not a number is ignored`() {
        val result = stats(row(0, coolant, Float.NaN), row(1, coolant, 80f), row(2, coolant, Float.POSITIVE_INFINITY), row(3, coolant, Float.NEGATIVE_INFINITY))
        assertEquals(MinMax(80f, 80f), result.coolant)
    }

    @Test
    fun `only invalid coolant values leave the coolant empty, not 0 to 0`() {
        assertNull(stats(row(0, coolant, Float.NaN)).coolant)
    }

    @Test
    fun `voltage without a single rpm row has no values - nothing proves the engine ran`() {
        assertNull(stats(row(0, volts, 13.8f), row(30, volts, 14.1f)).voltage)
    }

    @Test
    fun `voltage counts while the engine runs, from 15 seconds after the start`() {
        val result = stats(*runningEngine(40).toTypedArray(), row(15, volts, 13.5f), row(30, volts, 14.2f), row(40, volts, 12.4f))
        assertEquals(MinMax(12.4f, 14.2f), result.voltage)
    }

    @Test
    fun `the settle time is 15 seconds exactly - 14 seconds is too early, 15 counts`() {
        val early = stats(*runningEngine(20).toTypedArray(), row(14, volts, 8.0f))
        assertNull("14 s nach dem Start zaehlt noch nicht", early.voltage)
        val onTime = stats(*runningEngine(20).toTypedArray(), row(15, volts, 12.0f))
        assertEquals("15 s zaehlt", MinMax(12.0f, 12.0f), onTime.voltage)
    }

    @Test
    fun `the starter dip and the ignition-on voltage never count`() {
        // Zuendung an (11,9 V) und Anlasser (7,8 V) ohne Drehzahl, danach laeuft der Motor: nur die Werte ab 15 s zaehlen.
        val result = stats(
            row(-10, volts, 11.9f), row(-5, volts, 11.9f), row(-1, volts, 7.8f),
            *runningEngine(60).toTypedArray(),
            row(5, volts, 12.9f), row(20, volts, 12.3f), row(40, volts, 14.1f),
        )
        assertEquals(MinMax(12.3f, 14.1f), result.voltage)
    }

    @Test
    fun `an engine below 500 rpm does not count as running`() {
        val result = stats(*(0..30).map { row(it, rpm, 499f) }.toTypedArray(), row(30, volts, 12.0f))
        assertNull(result.voltage)
    }

    @Test
    fun `500 rpm exactly counts as running`() {
        val result = stats(*(0..30).map { row(it, rpm, 500f) }.toTypedArray(), row(30, volts, 12.0f))
        assertEquals(MinMax(12.0f, 12.0f), result.voltage)
    }

    @Test
    fun `stalling closes the gate at once and a restart needs the settle time again`() {
        val result = stats(
            *runningEngine(30).toTypedArray(),
            row(30, volts, 13.5f), // zaehlt
            row(31, rpm, 0f), // abgestorben
            row(32, volts, 12.0f), // Motor aus: zaehlt nicht
            *rpmRows(40, 60, 1_000f).toTypedArray(), // neu gestartet
            row(45, volts, 7.9f), // 5 s nach dem Neustart: Einschwingzeit
            row(55, volts, 13.9f), // 15 s nach dem Neustart: zaehlt
        )
        assertEquals(MinMax(13.5f, 13.9f), result.voltage)
    }

    @Test
    fun `the last rpm holds for 12 seconds - a missing rpm cycle does not interrupt the run`() {
        // Drehzahl bis Sekunde 20, dann kommt 12 s lang keine; das Kuehlmittel laeuft weiter (jeder Zyklus).
        val result = stats(
            *runningEngine(20).toTypedArray(),
            row(32, coolant, 90f), // genau 12 s nach der letzten Drehzahl: Tor noch offen
            row(32, volts, 12.5f),
        )
        assertEquals(MinMax(12.5f, 12.5f), result.voltage)
    }

    @Test
    fun `more than 12 seconds without rpm closes the gate - and the run starts again afterwards`() {
        val result = stats(
            *runningEngine(20).toTypedArray(),
            row(33, coolant, 90f), // 13 s nach der letzten Drehzahl: Motor gilt als aus
            row(33, volts, 8.0f), // zaehlt nicht
            *rpmRows(40, 60, 1_100f).toTypedArray(), // Drehzahl wieder da: neuer Lauf, Einschwingzeit ab hier
            row(54, volts, 9.0f), // 14 s nach dem Neubeginn: zaehlt nicht
            row(55, volts, 13.0f), // 15 s: zaehlt
        )
        assertEquals(MinMax(13.0f, 13.0f), result.voltage)
    }

    @Test
    fun `a missing rpm is noticed through the coolant rows too - they come in every cycle, the voltage only in every fourth`() {
        // Drehzahl bis Sekunde 20, dann 40 s lang nur Kuehlmittel (kein Spannungswert dazwischen), dann laeuft der Motor wieder.
        val result = stats(
            *runningEngine(20).toTypedArray(),
            *(21..60).map { row(it, coolant, 80f) }.toTypedArray(),
            *rpmRows(61, 80).toTypedArray(),
            row(62, volts, 9.0f), // 1 s nach dem Wiederanlauf: Einschwingzeit, zaehlt nicht
            row(76, volts, 13.1f), // 15 s danach: zaehlt
        )
        assertEquals(MinMax(13.1f, 13.1f), result.voltage)
    }

    @Test
    fun `a gap in the rpm rows without any other row does not count as engine off - nothing was recorded to prove it`() {
        // Zwischen Sekunde 20 und 40 steht gar keine Zeile (Aufzeichnung stockte): die naechste Drehzahlzeile setzt den Lauf fort.
        val result = stats(*runningEngine(20).toTypedArray(), row(40, rpm, 1_200f), row(41, volts, 13.2f))
        assertEquals(MinMax(13.2f, 13.2f), result.voltage)
    }

    @Test
    fun `a voltage of zero or not a number is no reading, even with a running engine`() {
        val result = stats(
            *runningEngine(60).toTypedArray(),
            row(20, volts, 0f), row(21, volts, Float.NaN), row(22, volts, 13.4f), row(23, volts, -1f),
            row(24, volts, Float.POSITIVE_INFINITY), row(25, volts, Float.NEGATIVE_INFINITY),
        )
        assertEquals(MinMax(13.4f, 13.4f), result.voltage)
    }

    @Test
    fun `only invalid voltages leave the voltage empty`() {
        assertNull(stats(*runningEngine(60).toTypedArray(), row(20, volts, 0f)).voltage)
    }

    @Test
    fun `rows of other values are ignored - speed, the shown gear and the oil bar colour`() {
        val result = stats(
            row(0, PidRegistry.SPEED.id, 250f),
            row(0, DerivedPids.GEAR.id, 6f),
            row(0, DerivedPids.OIL_BAR.id, 2f),
            row(0, "01FF", -999f),
        )
        assertNull(result.coolant)
        assertNull(result.voltage)
    }

    @Test
    fun `coolant and voltage are independent - a trip with only one has only that one`() {
        val onlyCoolant = stats(row(0, coolant, 50f))
        assertEquals(MinMax(50f, 50f), onlyCoolant.coolant)
        assertNull(onlyCoolant.voltage)
        val onlyVoltage = stats(*runningEngine(30).toTypedArray(), row(30, volts, 13.0f))
        assertNull(onlyVoltage.coolant)
        assertEquals(MinMax(13.0f, 13.0f), onlyVoltage.voltage)
    }

    @Test
    fun `the result can be read more than once and does not change`() {
        val acc = SessionStatsAccumulator()
        acc.add(0L, coolant, 20f)
        val first = acc.result()
        val second = acc.result()
        assertEquals(first, second)
        acc.add(1_000L, coolant, 30f)
        assertEquals(MinMax(20f, 20f), first.coolant)
        assertEquals(MinMax(20f, 30f), acc.result().coolant)
    }

    @Test
    fun `the query reads exactly the three values it needs, in recording order`() {
        assertEquals(
            "SELECT timestamp, pidId, value FROM sensor_readings WHERE sessionId = ? AND pidId IN ('010C', '0105', '0142') ORDER BY id ASC",
            SESSION_STATS_QUERY_SQL,
        )
    }
}
