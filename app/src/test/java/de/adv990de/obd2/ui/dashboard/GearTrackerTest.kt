package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gang und "N" ueber die Zyklen: Hysterese, Anzeige-Persistenz und die 5-Sekunden-Gegenprobe mit dem GPS. */
class GearTrackerTest {

    private val ratios = GearRatios(
        wheelCircumferenceMm = 2096f,
        primaryRatio = 1.9143f,
        secondaryRatio = 2.625f,
        gearRatios = listOf(2.9167f, 2.1333f, 1.6667f, 1.35f, 1.125f, 1.04f),
    )

    private var clock = 0L
    private val tracker = GearTracker(now = { clock })

    private fun rpmFor(gear: Int, speedKmh: Float): Float {
        val wheelRpm = speedKmh * 1_000_000f / (60f * ratios.wheelCircumferenceMm)
        return ratios.primaryRatio * ratios.secondaryRatio * ratios.gearRatios[gear - 1] * wheelRpm
    }

    @Test
    fun `the gear comes from the GPS speed, not from the ECU speed`() {
        val rpm = rpmFor(3, speedKmh = 80f)

        val gear = tracker.update(rpm, ecuSpeedKmh = 80f, gpsSpeedKmh = 80f, previous = GearReading.Unknown, ratios = ratios)
        assertEquals(GearReading(3, isNeutral = false), gear)

        val noGps = tracker.update(rpm, ecuSpeedKmh = 80f, gpsSpeedKmh = null, previous = GearReading.Unknown, ratios = ratios)
        assertNull("ohne GPS ist der Gang unbekannt, auch wenn die ECU eine Geschwindigkeit meldet", noGps.gear)
    }

    @Test
    fun `a single implausible cycle keeps the shown gear while the vehicle keeps rolling`() {
        val shown = GearReading(4, isNeutral = false)

        // Kupplung gezogen: die Drehzahl faellt aus dem Gang heraus, das GPS zeigt weiter 90 km/h.
        val held = tracker.update(rpm = 1500f, ecuSpeedKmh = 90f, gpsSpeedKmh = 90f, previous = shown, ratios = ratios)

        assertEquals(GearReading(4, isNeutral = false), held)
    }

    @Test
    fun `standing with the engine running shows N and no gear`() {
        val neutral = tracker.update(rpm = 1100f, ecuSpeedKmh = 0f, gpsSpeedKmh = 0f, previous = GearReading(2, false), ratios = ratios)

        assertEquals(GearReading(gear = null, isNeutral = true), neutral)
    }

    @Test
    fun `N wins over a gear that the lagging GPS still reports right after stopping`() {
        // Beim Anhalten meldet die ECU sofort 0, das GPS zeigt noch 20 km/h: die Drehzahl passt zum 2. Gang.
        val rpm = rpmFor(2, speedKmh = 20f)

        val reading = tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 20f, previous = GearReading(2, isNeutral = false), ratios = ratios)

        assertEquals(GearReading(gear = null, isNeutral = true), reading)
    }

    @Test
    fun `an ECU that reports standstill for good does not keep N once the GPS has contradicted it for five seconds`() {
        val rpm = rpmFor(3, speedKmh = 60f)
        var shown = GearReading(gear = null, isNeutral = true)

        clock = 0L
        shown = tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 60f, previous = shown, ratios = ratios)
        assertTrue("beim Anhalten hinkt das GPS nach: kein sofortiges Veto", shown.isNeutral)

        clock = 4_900L
        shown = tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 60f, previous = shown, ratios = ratios)
        assertTrue("knapp unter fuenf Sekunden", shown.isNeutral)

        clock = 5_000L
        shown = tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 60f, previous = shown, ratios = ratios)
        assertFalse(shown.isNeutral)
        assertEquals("der Gang kommt jetzt aus dem GPS", 3, shown.gear)
    }

    @Test
    fun `reset drops the running contradiction, so the five seconds start again`() {
        val rpm = rpmFor(3, speedKmh = 60f)
        var shown = GearReading(gear = null, isNeutral = true)
        clock = 0L
        tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 60f, previous = shown, ratios = ratios)

        tracker.reset()   // z. B. Verbindungsverlust
        clock = 5_000L
        shown = tracker.update(rpm, ecuSpeedKmh = 0f, gpsSpeedKmh = 60f, previous = shown, ratios = ratios)

        assertTrue("der Widerspruch beginnt nach dem Zuruecksetzen neu", shown.isNeutral)
    }
}
