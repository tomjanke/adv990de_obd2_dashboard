package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Die Farbe des Oel-Balkens ueber die Zyklen: nur neue Messwerte zaehlen, fehlende halten die Farbe, der Zustand ueberlebt die Activity. */
class OilBarTrackerTest {

    private var clock = 0L
    private val handle = SavedStateHandle()
    private val tracker = OilBarTracker(handle, now = { clock })

    private fun coolant(tempC: Float, timestamp: Long) = SensorValue(PidRegistry.COOLANT_TEMP, tempC, timestamp = timestamp)

    @Test
    fun `a cold engine shows red, a warm one yellow`() {
        assertEquals(OilBarColor.Red, tracker.update(listOf(coolant(60f, 1)), previous = null))
        assertEquals(OilBarColor.Yellow, tracker.update(listOf(coolant(78f, 2)), previous = OilBarColor.Red))
    }

    @Test
    fun `green only after ten uninterrupted minutes above 80 degrees`() {
        // In Schritten unter dem Luecken-Limit (60 s) zwischen zwei Messwerten: der Timer laeuft ab t = 0.
        var color: OilBarColor? = null
        var stamp = 1L
        clock = 0L
        while (clock < 10 * 60_000L) {
            color = tracker.update(listOf(coolant(85f, stamp++)), previous = color)
            clock += 30_000L
        }
        assertEquals("beim letzten Zyklus bei 9:30 noch Gelb", OilBarColor.Yellow, color)

        clock = 10 * 60_000L - 1
        assertEquals("eine Millisekunde zu frueh", OilBarColor.Yellow, tracker.update(listOf(coolant(85f, stamp++)), previous = color))
        clock = 10 * 60_000L
        assertEquals(OilBarColor.Green, tracker.update(listOf(coolant(85f, stamp)), previous = color))
    }

    @Test
    fun `a reading that is not newer - the GPS refresh repeats the last OBD values - changes nothing`() {
        tracker.update(listOf(coolant(60f, timestamp = 100)), previous = null)

        // Dieselbe Messung noch einmal, jetzt mit "heissem" Wert: wuerde ein neuer Messwert als Gruen-Start zaehlen.
        val repeated = tracker.update(listOf(coolant(95f, timestamp = 100)), previous = OilBarColor.Red)

        assertEquals("die zuletzt angezeigte Farbe bleibt", OilBarColor.Red, repeated)
    }

    @Test
    fun `a cycle without a plausible coolant value keeps the last color`() {
        tracker.update(listOf(coolant(60f, 1)), previous = null)

        assertEquals(OilBarColor.Red, tracker.update(emptyList(), previous = OilBarColor.Red))
        assertEquals("NaN ist kein Messwert", OilBarColor.Red, tracker.update(listOf(coolant(Float.NaN, 2)), previous = OilBarColor.Red))
        assertEquals(OilBarColor.Red, tracker.update(listOf(SensorValue(PidRegistry.RPM, 3000f, timestamp = 3)), previous = OilBarColor.Red))
    }

    @Test
    fun `without any reading yet there is no color`() {
        assertNull(tracker.update(emptyList(), previous = null))
    }

    @Test
    fun `the timer state survives a new tracker on the same SavedStateHandle - an activity recreation in a running process`() {
        // Der Gruen-Timer laeuft ab t = 0; letzter Messwert bei 9:30.
        var stamp = 1L
        clock = 0L
        while (clock < 570_000L + 1) { tracker.update(listOf(coolant(85f, stamp++)), previous = null); clock += 30_000L }

        // "Activity neu erzeugt": neuer Tracker, gleiches Handle, 30 s spaeter — der Timer ist nicht verloren.
        clock = 10 * 60_000L
        val recreated = OilBarTracker(handle, now = { clock })
        val color = recreated.update(listOf(coolant(85f, stamp)), previous = null)

        assertEquals(OilBarColor.Green, color)
    }

    @Test
    fun `a dip in progress survives a new tracker on the same SavedStateHandle`() {
        // Timer laeuft ab t = 0; der Einbruch unter 78 C beginnt bei 330 s, die Activity wird bei 360 s neu erzeugt. Der Reset muss 120 s nach dem
        // BEGINN des Einbruchs kommen (450 s), nicht 120 s nach der Neuerzeugung (480 s).
        var stamp = 1L
        clock = 0L
        while (clock <= 300_000L) { tracker.update(listOf(coolant(85f, stamp++)), previous = null); clock += 30_000L }
        clock = 330_000L
        tracker.update(listOf(coolant(77f, stamp++)), previous = OilBarColor.Yellow)
        assertEquals("laufend: noch nicht zurueckgesetzt", 0L, handle.get<Long>("oilBarAboveGreenSinceMillis"))

        val recreated = OilBarTracker(handle, now = { clock })
        clock = 360_000L
        recreated.update(listOf(coolant(77f, stamp++)), previous = OilBarColor.Yellow)
        assertEquals("30 s nach Beginn des Einbruchs", 0L, handle.get<Long>("oilBarAboveGreenSinceMillis"))
        clock = 450_000L
        recreated.update(listOf(coolant(77f, stamp)), previous = OilBarColor.Yellow)

        assertNull("120 s nach dem Beginn des Einbruchs ist der Timer zurueckgesetzt", handle.get<Long>("oilBarAboveGreenSinceMillis"))
    }

    @Test
    fun `the start of a dip is stored under its own key`() {
        clock = 0L
        tracker.update(listOf(coolant(85f, timestamp = 1)), previous = null)
        clock = 1_000L
        tracker.update(listOf(coolant(77f, timestamp = 2)), previous = OilBarColor.Yellow)

        assertEquals(1_000L, handle.get<Long>("oilBarBelowResetSinceMillis"))
    }

    @Test
    fun `the stored keys are the ones older versions wrote`() {
        tracker.update(listOf(coolant(85f, timestamp = 42)), previous = null)

        assertEquals(42L, handle.get<Long>("oilBarLastSampleTimestamp"))
        assertEquals(0L, handle.get<Long>("oilBarAboveGreenSinceMillis"))
        assertEquals(0L, handle.get<Long>("oilBarLastSampleAtMillis"))
    }
}
