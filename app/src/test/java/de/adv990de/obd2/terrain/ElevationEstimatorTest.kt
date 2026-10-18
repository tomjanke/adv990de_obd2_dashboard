package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Hoehe = Druckhoehe + Versatz zur GPS-Hoehe (s. Spezifikation `2026-10-18-terrain-tile-design.md`).
 * Zeiten sind `elapsedRealtime`-Millisekunden, wie im Betrieb.
 */
class ElevationEstimatorTest {

    private val seaLevel = 1013.25f

    private fun fix(
        t: Long,
        altitude: Float? = 100f,
        accuracy: Float? = 5f,
    ) = GpsFix(
        elapsedMs = t, speedKmh = 50f, altitudeM = altitude, verticalAccuracyM = accuracy,
        bearingDeg = null, bearingAccuracyDeg = null,
    )

    private fun input(t: Long, hpa: Float? = seaLevel, altitude: Float? = 100f, accuracy: Float? = 5f) =
        TerrainInput(fix(t, altitude, accuracy), hpa)

    // --- pressureAltitudeM ---

    @Test
    fun `standard sea level pressure is zero metres`() {
        assertEquals(0f, pressureAltitudeM(1013.25f), 0.01f)
    }

    @Test
    fun `899 hPa is about one thousand metres`() {
        assertEquals(1000f, pressureAltitudeM(899f), 15f)
    }

    @Test
    fun `lower pressure means higher altitude`() {
        assertTrue(pressureAltitudeM(950f) > pressureAltitudeM(1000f))
        assertTrue(pressureAltitudeM(1000f) > pressureAltitudeM(1013.25f))
        assertTrue(pressureAltitudeM(1030f) < 0f)
    }

    // --- Kalibrierung ---

    @Test
    fun `without a calibration sample the altitude is unknown`() {
        val e = ElevationEstimator()
        assertNull(e.update(0, TerrainInput(null, seaLevel)))
        assertNull(e.update(1_000, TerrainInput(fix(1_000, altitude = null), seaLevel)))
    }

    @Test
    fun `one good sample calibrates immediately`() {
        val e = ElevationEstimator()
        assertEquals(100f, e.update(0, input(0))!!, 0.01f)
    }

    @Test
    fun `the pressure alone moves the altitude after calibration`() {
        val e = ElevationEstimator()
        e.update(0, input(0, hpa = seaLevel, altitude = 100f))
        // 1 hPa weniger = rund 8 bis 9 m hoeher; der GPS-Fix liefert keine Hoehe mehr.
        val up = e.update(1_000, input(1_000, hpa = seaLevel - 1f, altitude = null))!!
        assertEquals(108.5f, up, 1.0f)
    }

    @Test
    fun `a sample with poor or missing vertical accuracy does not count`() {
        val e = ElevationEstimator()
        assertNull(e.update(0, input(0, accuracy = 20.1f)))
        assertNull(e.update(1_000, input(1_000, accuracy = null)))
        assertNotNull(e.update(2_000, input(2_000, accuracy = 20f)))
    }

    @Test
    fun `a fix older than three seconds does not calibrate`() {
        val e = ElevationEstimator()
        // Der Fix traegt die Zeit 0, die Eingabe wird erst bei 3,5 s verarbeitet.
        assertNull(e.update(3_500, TerrainInput(fix(0), seaLevel)))
        assertNotNull(e.update(3_500, TerrainInput(fix(3_000), seaLevel)))
    }

    @Test
    fun `the first samples are a running mean, then a slow average`() {
        val e = ElevationEstimator()
        e.update(0, input(0, altitude = 100f))
        // Zweite Probe: laufender Mittelwert, Gewicht 1/2.
        val second = e.update(1_000, input(1_000, altitude = 110f))!!
        assertEquals(105f, second, 0.01f)
        // Nach vielen Proben bewegt eine Probe den Versatz hoechstens um 1/120 der Differenz je Sekunde.
        var t = 1_000L
        repeat(200) { t += 1_000; e.update(t, input(t, altitude = 105f)) }
        val before = e.update(t + 1_000, input(t + 1_000, altitude = 105f))!!
        val after = e.update(t + 2_000, input(t + 2_000, altitude = 125f))!!
        assertEquals(20f / 120f, after - before, 0.02f)
    }

    // --- Wetterdrift ---

    @Test
    fun `slow weather drift without any climb stays within a few metres`() {
        val e = ElevationEstimator()
        var t = 0L
        var worst = 0f
        // Eine Stunde, der Druck faellt um 3 hPa (Wetterwechsel), die wahre Hoehe bleibt 100 m.
        repeat(3_600) { i ->
            val hpa = seaLevel - 3f * i / 3_600f
            val trueAltitude = 100f
            val shown = e.update(t, input(t, hpa = hpa, altitude = trueAltitude))
            if (i > 60) worst = maxOf(worst, abs(shown!! - trueAltitude))
            t += 1_000
        }
        assertTrue("groesste Abweichung $worst m", worst < 5f)
    }

    // --- Veralten ---

    @Test
    fun `the altitude goes back to unknown after ten minutes without a usable sample`() {
        val e = ElevationEstimator()
        e.update(0, input(0))
        // Ab hier nur noch Druck (kein Fix): bis 10 Minuten weiter bekannt, danach nicht mehr.
        assertNotNull(e.update(599_000, TerrainInput(null, seaLevel)))
        assertNull(e.update(601_000, TerrainInput(null, seaLevel)))
    }

    @Test
    fun `a usable sample after the gap calibrates again`() {
        val e = ElevationEstimator()
        e.update(0, input(0, altitude = 100f))
        assertNull(e.update(700_000, TerrainInput(null, seaLevel)))
        assertEquals(300f, e.update(701_000, input(701_000, hpa = seaLevel, altitude = 300f))!!, 0.01f)
    }

    // --- ohne Barometer ---

    @Test
    fun `without a barometer the smoothed gps altitude is shown`() {
        val e = ElevationEstimator()
        assertEquals(100f, e.update(0, input(0, hpa = null, altitude = 100f))!!, 0.01f)
        // EMA mit tau 15 s: eine Probe 30 m hoeher zieht die Anzeige nur um 1 s / 15 s dorthin.
        val next = e.update(1_000, input(1_000, hpa = null, altitude = 130f))!!
        assertEquals(100f + 30f / 15f, next, 0.5f)
    }

    @Test
    fun `without a barometer a poor sample leaves the altitude unchanged`() {
        val e = ElevationEstimator()
        e.update(0, input(0, hpa = null, altitude = 100f))
        val held = e.update(1_000, input(1_000, hpa = null, altitude = 500f, accuracy = 40f))!!
        assertEquals(100f, held, 0.01f)
    }

    // --- reset ---

    @Test
    fun `reset forgets the calibration`() {
        val e = ElevationEstimator()
        e.update(0, input(0))
        e.reset()
        assertNull(e.update(1_000, TerrainInput(null, seaLevel)))
    }
}
