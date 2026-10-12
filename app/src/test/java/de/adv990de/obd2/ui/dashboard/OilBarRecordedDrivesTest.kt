package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zwei aufgezeichnete Aufwaermfahrten (2026-10-12, CSV-Export der App, PID 0105) als Regressionstest der Gruen-Hysterese des Oel-Balkens.
 *
 * Anlass: Das Kuehlmittel haengt in den ersten Minuten bei 78-80 C (Thermostat) und bricht bei hoher Drehzahl immer wieder auf 76-78 C ein.
 * Mit einem sofortigen Reset bei <= 78 C erschien in beiden Fahrten nie Gruen (4 bzw. 2 Resets); das Entprellen (RESET_DEBOUNCE_MS = 120 s) ignoriert
 * Einbrueche, die kuerzer dauern. Der laengste Einbruch war 84 s (Vormittag) bzw. 74 s (Abend).
 *
 * Die Reihen beginnen beim ersten Wert ueber 80 C und stehen als Zonen-Laufkodierung (Zone, Dauer in Sekunden) hier: Die Logik haengt nur von der
 * Zone und den Zeitpunkten ab, nicht vom genauen Gradwert. Die Abtastung ist hier ein Wert je Sekunde (die echte lag bei ~1,09 s).
 */
class OilBarRecordedDrivesTest {

    /** Zonen der Reset-Logik: ueber 80 (Start/Lauf), 79-80 (Haltezone), 76-78 (Einbruch, Reset nach Entprellen). */
    private enum class Zone(val tempC: Float) { Above(85f), Hold(79f), Dip(77f) }

    private val morning = listOf(
        Zone.Above to 124, Zone.Hold to 33, Zone.Dip to 14, Zone.Hold to 9, Zone.Above to 8, Zone.Hold to 6, Zone.Dip to 84, Zone.Hold to 44,
        Zone.Above to 25, Zone.Hold to 1, Zone.Dip to 11, Zone.Hold to 7, Zone.Above to 200, Zone.Hold to 12, Zone.Dip to 33, Zone.Hold to 6,
        Zone.Dip to 17, Zone.Hold to 11, Zone.Above to 16,
    )

    private val evening = listOf(
        Zone.Above to 67, Zone.Hold to 19, Zone.Above to 122, Zone.Hold to 34, Zone.Dip to 74, Zone.Hold to 13, Zone.Dip to 26, Zone.Hold to 7,
        Zone.Above to 50, Zone.Hold to 18, Zone.Above to 14, Zone.Hold to 11, Zone.Dip to 21, Zone.Hold to 7, Zone.Above to 178,
    )

    private val tenMinutesS = 10 * 60

    /** Farbe je Sekunde 0..Ende: Index = Sekunde seit dem ersten Wert ueber 80 C. */
    private fun colorsPerSecond(drive: List<Pair<Zone, Int>>): List<OilBarColor> {
        var state = OilBarTimerState()
        val colors = mutableListOf<OilBarColor>()
        var second = 0
        for ((zone, seconds) in drive) {
            repeat(seconds) {
                val nowMs = second * 1_000L
                state = updateOilBarTimer(zone.tempC, nowMs, state)
                colors += oilBarColor(zone.tempC, nowMs, state)
                second++
            }
        }
        return colors
    }

    private fun assertGreenExactlyFromTenMinutes(name: String, drive: List<Pair<Zone, Int>>) {
        val colors = colorsPerSecond(drive)
        assertEquals("$name: Laenge der Reihe", 661, colors.size)
        val firstGreen = colors.indexOf(OilBarColor.Green)
        assertEquals("$name: erstes Gruen genau 10 Minuten nach dem ersten Wert ueber 80 C", tenMinutesS, firstGreen)
        assertEquals("$name: danach bleibt es gruen", List(colors.size - tenMinutesS) { OilBarColor.Green }, colors.drop(tenMinutesS))
        assertEquals("$name: davor nie gruen", List(tenMinutesS) { OilBarColor.Yellow }, colors.take(tenMinutesS))
    }

    @Test
    fun `the morning warm-up reaches green ten minutes after the first value above 80C despite its dips`() {
        assertGreenExactlyFromTenMinutes("Vormittag", morning)
    }

    @Test
    fun `the evening warm-up reaches green ten minutes after the first value above 80C despite its dips`() {
        assertGreenExactlyFromTenMinutes("Abend", evening)
    }
}
