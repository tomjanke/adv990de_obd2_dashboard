package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Steigung = Hoehenaenderung / Strecke ueber ein Fenster von mindestens 100 m (ohne Barometer 300 m); die Strecke ist das Integral der
 * Fix-Geschwindigkeit. Ein Fix je Sekunde, wie im Betrieb.
 */
class GradeEstimatorTest {

    /** Umkehrung von [pressureAltitudeM]: der Druck, bei dem die Druckhoehe `altitudeM` betraegt. */
    private fun pressureFor(altitudeM: Float): Float =
        (1013.25 * (1.0 - altitudeM / 44330.0).pow(5.255)).toFloat()

    private fun fix(t: Long, speedKmh: Float? = 50f, altitude: Float? = 100f, accuracy: Float? = 5f) =
        GpsFix(t, speedKmh, altitude, accuracy, bearingDeg = null, bearingAccuracyDeg = null)

    /** Faehrt `seconds` Sekunden mit `speedKmh` und der wahren Steigung `slope` (Anteil, 0,05 = 5 %); liefert die letzte Steigung. */
    private fun drive(
        e: GradeEstimator,
        seconds: Int,
        speedKmh: Float = 50f,
        slope: Float = 0f,
        barometer: Boolean = true,
        startMs: Long = 0,
        startDistanceM: Double = 0.0,
        startAltitudeM: Float = 100f,
        noise: (Int) -> Float = { 0f },
    ): Triple<Float?, Long, Double> {
        var t = startMs
        var distance = startDistanceM
        var last: Float? = null
        val metresPerSecond = speedKmh / 3.6
        repeat(seconds) { i ->
            t += 1_000
            distance += metresPerSecond
            val altitude = startAltitudeM + slope * (distance - startDistanceM).toFloat() + noise(i)
            last = e.update(
                t,
                TerrainInput(
                    fix(t, speedKmh, altitude),
                    if (barometer) pressureFor(altitude) else null,
                ),
            )
        }
        return Triple(last, t, distance)
    }

    @Test
    fun `before a full window there is no grade`() {
        val e = GradeEstimator()
        val (grade, _, _) = drive(e, seconds = 5, speedKmh = 50f, slope = 0.05f)   // 69 m
        assertNull(grade)
    }

    @Test
    fun `a constant climb of five percent is read as five percent`() {
        val e = GradeEstimator()
        val (grade, _, _) = drive(e, seconds = 30, slope = 0.05f)
        assertEquals(5f, grade!!, 0.3f)
    }

    @Test
    fun `a constant descent is negative`() {
        val e = GradeEstimator()
        val (grade, _, _) = drive(e, seconds = 30, slope = -0.06f)
        assertEquals(-6f, grade!!, 0.3f)
    }

    @Test
    fun `noise of a third of a metre on the flat stays below one percent`() {
        val e = GradeEstimator()
        var worst = 0f
        var t = 0L
        var d = 0.0
        repeat(120) { i ->
            t += 1_000; d += 50 / 3.6
            val altitude = 100f + if (i % 2 == 0) 0.3f else -0.3f
            val g = e.update(t, TerrainInput(fix(t, 50f, altitude), pressureFor(altitude)))
            if (g != null) worst = maxOf(worst, abs(g))
        }
        assertTrue("groesste Steigung $worst %", worst < 1f)
    }

    @Test
    fun `below ten km per h there is no grade and the history does not grow`() {
        val e = GradeEstimator()
        drive(e, seconds = 40, slope = 0.05f)
        val before = e.history.size
        val (grade, _, _) = drive(e, seconds = 10, speedKmh = 9.9f, slope = 0.05f, startMs = 40_000, startDistanceM = 555.0)
        assertNull(grade)
        assertEquals(before, e.history.size)
    }

    @Test
    fun `ten km per h is enough`() {
        val e = GradeEstimator()
        val (grade, _, _) = drive(e, seconds = 60, speedKmh = 10f, slope = 0.05f)
        assertNotNull(grade)
    }

    @Test
    fun `a stop discards the window so the next grade needs a fresh window`() {
        val e = GradeEstimator()
        drive(e, seconds = 30, slope = 0.05f)
        drive(e, seconds = 3, speedKmh = 0f, startMs = 30_000, startDistanceM = 416.0)
        val (grade, _, _) = drive(e, seconds = 3, speedKmh = 50f, slope = 0.05f, startMs = 33_000, startDistanceM = 416.0)
        assertNull(grade)   // erst 42 m seit dem Anfahren
    }

    @Test
    fun `a gap of more than ten seconds discards the window`() {
        val e = GradeEstimator()
        drive(e, seconds = 30, slope = 0.05f)
        // Naechster Fix 11 s spaeter, direkt danach nur wenige Meter: kein Fenster.
        val grade = e.update(41_000, TerrainInput(fix(41_000, 50f, 150f), pressureFor(150f)))
        assertNull(grade)
    }

    @Test
    fun `an implausible grade is not shown`() {
        val e = GradeEstimator()
        // 60 % Steigung (Messfehler, Tunnel, Druckstoss).
        val (grade, _, _) = drive(e, seconds = 30, slope = 0.6f)
        assertNull(grade)
    }

    @Test
    fun `a speed above 250 km per h is ignored`() {
        val e = GradeEstimator()
        drive(e, seconds = 30, slope = 0.05f)
        assertNull(e.update(31_000, TerrainInput(fix(31_000, 300f, 100f), pressureFor(100f))))
    }

    @Test
    fun `without a fix or a speed there is no grade`() {
        val e = GradeEstimator()
        drive(e, seconds = 30, slope = 0.05f)
        assertNull(e.update(31_000, TerrainInput(null, pressureFor(100f))))
        assertNull(e.update(32_000, TerrainInput(fix(32_000, speedKmh = null), pressureFor(100f))))
    }

    @Test
    fun `without a barometer a three hundred metre window on the smoothed gps altitude is used`() {
        val e = GradeEstimator()
        // Die geglaettete GPS-Hoehe (tau 15 s) hinkt einer Rampe hinterher; nach dem Einschwingen stimmt die Differenz.
        val (grade, _, _) = drive(e, seconds = 120, slope = 0.05f, barometer = false)
        assertEquals(5f, grade!!, 0.6f)
    }

    @Test
    fun `without a barometer there is no grade before three hundred metres`() {
        val e = GradeEstimator()
        val (grade, _, _) = drive(e, seconds = 15, slope = 0.05f, barometer = false)   // 208 m
        assertNull(grade)
    }

    @Test
    fun `losing the barometer in mid drive does not turn the altitude source into a fake slope`() {
        val e = GradeEstimator()
        val (_, t, d) = drive(e, seconds = 40, slope = 0f, barometer = true, startAltitudeM = 100f)
        // GPS-Hoehe liegt 40 m neben der Druckhoehe: ein Wechsel der Quelle darf keine Steigung erzeugen.
        val (grade, _, _) = drive(e, seconds = 5, slope = 0f, barometer = false, startMs = t, startDistanceM = d, startAltitudeM = 140f)
        assertNull(grade)
    }

    // --- Verlauf fuer den Graphen ---

    @Test
    fun `the history gets about one value per twenty five metres`() {
        val e = GradeEstimator()
        // 10 m/s: ab dem ersten Fenster (100 m) 500 m weiter fahren.
        drive(e, seconds = 60, speedKmh = 36f, slope = 0.02f)
        assertTrue("Verlauf ${e.history.size}", e.history.size in 19..23)
        e.history.forEach { assertEquals(2f, it, 0.3f) }
    }

    @Test
    fun `the history keeps at most forty values and drops the oldest`() {
        val e = GradeEstimator()
        // Erst 1,5 km mit 2 %, dann 1,5 km mit 8 %: nur noch die letzten 1000 m (alle 8 %) bleiben.
        val (_, t, d) = drive(e, seconds = 150, speedKmh = 36f, slope = 0.02f)
        drive(e, seconds = 150, speedKmh = 36f, slope = 0.08f, startMs = t, startDistanceM = d, startAltitudeM = 100f + 0.02f * d.toFloat())
        assertEquals(40, e.history.size)
        assertTrue("juengster Wert ${e.history.last()}", abs(e.history.last() - 8f) < 0.5f)
        assertTrue("aeltester Wert ${e.history.first()}", abs(e.history.first() - 8f) < 1.5f)
    }

    @Test
    fun `reset clears window and history`() {
        val e = GradeEstimator()
        drive(e, seconds = 60, speedKmh = 36f, slope = 0.02f)
        e.reset()
        assertTrue(e.history.isEmpty())
        val (grade, _, _) = drive(e, seconds = 3, speedKmh = 36f, slope = 0.02f, startMs = 100_000)
        assertNull(grade)
    }
}
