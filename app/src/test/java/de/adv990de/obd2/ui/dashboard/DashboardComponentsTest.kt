package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.components.StatusDotState
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure decision logic extracted out of this package's Composable bodies: from
 * [RpmBar] the blink timing ([rpmBlinkPeriodMs]) and the per-segment color choice
 * ([rpmSegmentColor]); from `DashboardScreen` the engine-temperature blink-threshold scale
 * ([rpmThresholdTempScale]) and the one-shot RPM demo-sweep gate ([shouldStartRpmDemoSweep]);
 * from [OilTemperatureBarStrip] its color choice ([oilBarStripColor]).
 * None of these functions has a Compose or Android dependency (Projekt-Konvention, vgl.
 * [GearIndicatorTest], `StatusDotTest`), so all are covered here directly instead of through
 * instrumentation tests.
 */
class DashboardComponentsTest {

    // --- rpmBlinkPeriodMs ---

    @Test
    fun `no blink when rpm is at or below the threshold`() {
        assertEquals(0L, rpmBlinkPeriodMs(rpm = 7000f, threshold = 7000, isLive = true))
        assertEquals(0L, rpmBlinkPeriodMs(rpm = 6000f, threshold = 7000, isLive = true))
    }

    @Test
    fun `no blink when not live, even above threshold`() {
        // Ohne Verbindung zeigt die Demo-Animation nur Bewegung, nie Alarmverhalten.
        assertEquals(0L, rpmBlinkPeriodMs(rpm = 8000f, threshold = 7000, isLive = false))
    }

    @Test
    fun `no blink when the threshold is zero or negative`() {
        // threshold <= 0 bedeutet "kein Schaltpunkt konfiguriert" — nie blinken, egal wie hoch rpm ist.
        assertEquals(0L, rpmBlinkPeriodMs(rpm = 8000f, threshold = 0, isLive = true))
        assertEquals(0L, rpmBlinkPeriodMs(rpm = 8000f, threshold = -1, isLive = true))
    }

    @Test
    fun `just above the threshold blinks at the slow end, near 500ms`() {
        // f ist hier fast 0, der Wert also fast (aber wegen der float-Rundung/toLong-Truncation
        // nie exakt) 500 — 499 ist der tatsaechlich erreichbare Grenzwert direkt oberhalb der
        // Schwelle (verifiziert per float32-Handrechnung, s. Testverlauf).
        assertEquals(499L, rpmBlinkPeriodMs(rpm = 5001f, threshold = 5000, isLive = true))
    }

    @Test
    fun `at the scale maximum it blinks at the fast end, 80ms`() {
        assertEquals(80L, rpmBlinkPeriodMs(rpm = 10000f, threshold = 7000, isLive = true))
    }

    @Test
    fun `midway between threshold and max blinks at a proportional rate`() {
        // threshold=6000, max=10000 -> Spanne 4000; rpm=8000 liegt genau in der Mitte (f=0.5)
        // -> 500 - 420*0.5 = 290.
        assertEquals(290L, rpmBlinkPeriodMs(rpm = 8000f, threshold = 6000, isLive = true))
    }

    @Test
    fun `a threshold at or above the scale maximum still clamps to the fastest rate`() {
        // threshold >= 10000 erzwingt f=1 unabhaengig von rpm (die "else"-Grenze in der Formel).
        assertEquals(80L, rpmBlinkPeriodMs(rpm = 10001f, threshold = 10000, isLive = true))
    }

    // --- rpmSegmentColor ---

    private val litColor = Color(0xFF111111)
    private val redlineColor = Color(0xFF222222)
    private val idleColor = Color(0xFF333333)

    @Test
    fun `an unlit segment uses the idle color`() {
        assertEquals(
            idleColor,
            rpmSegmentColor(
                segmentIndex = 5,
                litSegments = 3,
                segmentStartRpm = 1000f,
                redlineFromRpm = 7000f,
                litColor = litColor,
                redlineColor = redlineColor,
                idleColor = idleColor,
            )
        )
    }

    @Test
    fun `an unlit segment stays idle even if its position is past the redline`() {
        // Ein Segment jenseits der Schwelle, das aber (noch) nicht beleuchtet ist, darf nicht rot
        // vorgezeichnet werden — Redline-Farbe gilt nur fuer bereits beleuchtete Segmente.
        assertEquals(
            idleColor,
            rpmSegmentColor(
                segmentIndex = 40,
                litSegments = 3,
                segmentStartRpm = 8000f,
                redlineFromRpm = 7000f,
                litColor = litColor,
                redlineColor = redlineColor,
                idleColor = idleColor,
            )
        )
    }

    @Test
    fun `a lit segment below the redline zone uses the lit color`() {
        assertEquals(
            litColor,
            rpmSegmentColor(
                segmentIndex = 2,
                litSegments = 5,
                segmentStartRpm = 400f,
                redlineFromRpm = 7000f,
                litColor = litColor,
                redlineColor = redlineColor,
                idleColor = idleColor,
            )
        )
    }

    @Test
    fun `a lit segment in the redline zone uses the redline color`() {
        assertEquals(
            redlineColor,
            rpmSegmentColor(
                segmentIndex = 36,
                litSegments = 40,
                segmentStartRpm = 7200f,
                redlineFromRpm = 7000f,
                litColor = litColor,
                redlineColor = redlineColor,
                idleColor = idleColor,
            )
        )
    }

    @Test
    fun `a lit segment exactly at the redline boundary counts as redline`() {
        // Die Pruefung ist ">=", nicht ">" — das erste Segment auf/oberhalb der Schwelle muss
        // schon rot sein, nicht erst das naechste.
        assertEquals(
            redlineColor,
            rpmSegmentColor(
                segmentIndex = 10,
                litSegments = 20,
                segmentStartRpm = 7000f,
                redlineFromRpm = 7000f,
                litColor = litColor,
                redlineColor = redlineColor,
                idleColor = idleColor,
            )
        )
    }

    // --- rpmThresholdTempScale ---

    @Test
    fun `no plausible temperature falls back to the conservative scale`() {
        // null bedeutet "kein plausibler Messwert" — wie die kaelteste Bande behandelt (0.50).
        assertEquals(0.50f, rpmThresholdTempScale(null))
    }

    @Test
    fun `below the coldest band uses the conservative scale`() {
        assertEquals(0.50f, rpmThresholdTempScale(-40f))
    }

    @Test
    fun `a representative value in the coldest band uses 0_50`() {
        assertEquals(0.50f, rpmThresholdTempScale(20f))
    }

    @Test
    fun `just below the second band boundary still uses 0_50`() {
        assertEquals(0.50f, rpmThresholdTempScale(49.9f))
    }

    @Test
    fun `at the second band boundary the scale steps up to 0_75`() {
        assertEquals(0.75f, rpmThresholdTempScale(50f))
    }

    @Test
    fun `a representative value in the second band uses 0_75`() {
        assertEquals(0.75f, rpmThresholdTempScale(60f))
    }

    @Test
    fun `just below the third band boundary still uses 0_75`() {
        assertEquals(0.75f, rpmThresholdTempScale(69.9f))
    }

    @Test
    fun `at the third band boundary the scale steps up to 0_90`() {
        assertEquals(0.90f, rpmThresholdTempScale(70f))
    }

    @Test
    fun `a representative value in the third band uses 0_90`() {
        assertEquals(0.90f, rpmThresholdTempScale(80f))
    }

    @Test
    fun `just below the fourth band boundary still uses 0_90`() {
        assertEquals(0.90f, rpmThresholdTempScale(84.9f))
    }

    @Test
    fun `at the fourth band boundary the scale reaches the full 1_00`() {
        assertEquals(1.00f, rpmThresholdTempScale(85f))
    }

    @Test
    fun `a representative value well above the fourth boundary stays at 1_00`() {
        assertEquals(1.00f, rpmThresholdTempScale(120f))
    }

    // --- shouldStartRpmDemoSweep ---

    @Test
    fun `zero rpm allows the demo sweep to start`() {
        assertEquals(true, shouldStartRpmDemoSweep(0f))
    }

    @Test
    fun `an rpm just below 1 still allows the demo sweep to start`() {
        assertEquals(true, shouldStartRpmDemoSweep(0.99f))
    }

    @Test
    fun `an rpm of exactly 1 no longer allows the demo sweep to start`() {
        // Grenze ist "< 1f", nicht "<= 1f" — 1.0 zaehlt bereits als plausible echte Drehzahl.
        assertEquals(false, shouldStartRpmDemoSweep(1f))
    }

    @Test
    fun `a plausible real rpm value blocks the demo sweep from starting`() {
        assertEquals(false, shouldStartRpmDemoSweep(2500f))
    }

    // --- oilBarStripColor ---
    // Regressionsschutz fuer den Befund aus dem finalen Review: Gruen war bis dahin
    // `colorScheme.tertiary` — hellblau im blauen, amber im amber-farbenen (kaum von Gelb zu
    // unterscheiden) und mauve/rosa im Default-Farbschema. Die Funktion bekommt bewusst nur die
    // Grau-Farbe aus dem Theme herein, alle drei Ampelfarben sind fest.

    @Test
    fun `oil bar Green is the fixed traffic-light green, not a theme color`() {
        val green = oilBarStripColor(OilBarColor.Green, isLive = true, idleColor = idleColor)
        assertEquals(StatusColors.TrafficLightGreen, green)
        assertEquals(Color(0xFF22C55E), green) // Tailwind Green 500
    }

    @Test
    fun `oil bar Green is exactly the Bluetooth dot's connected green`() {
        // Nutzerentscheidung: dieselbe Gruen-Farbe wie der Verbindungs-Dot, keine zweite.
        assertEquals(
            connectionDotColor(StatusDotState.Ok),
            oilBarStripColor(OilBarColor.Green, isLive = true, idleColor = idleColor),
        )
    }

    @Test
    fun `oil bar Red and Yellow reuse the StatusColors red and amber`() {
        assertEquals(StatusColors.Critical, oilBarStripColor(OilBarColor.Red, isLive = true, idleColor = idleColor))
        assertEquals(StatusColors.Warning, oilBarStripColor(OilBarColor.Yellow, isLive = true, idleColor = idleColor))
    }

    @Test
    fun `oil bar without a color yet is grey`() {
        assertEquals(idleColor, oilBarStripColor(null, isLive = true, idleColor = idleColor))
    }

    @Test
    fun `oil bar is grey without a connection, whatever color is passed in`() {
        (OilBarColor.entries + null).forEach { color ->
            assertEquals("color=$color", idleColor, oilBarStripColor(color, isLive = false, idleColor = idleColor))
        }
    }

    // --- gearBoxContent / gearBoxColor ---
    // Die Gang-Box neben dem RPM-Balken kennt drei Zustaende: Gang, Leerlauf ("N") und unbekannt
    // ("–"). Praezedenz: ohne Verbindung immer unbekannt, sonst gewinnt "N" vor einem noch
    // gesetzten Gang (die ECU meldet den Stillstand sofort, die GPS-Uebersetzung hinkt nach).

    @Test
    fun `a live determined gear shows that gear`() {
        assertEquals(GearBoxContent.Engaged(3), gearBoxContent(gear = 3, isNeutral = false, isLive = true))
    }

    @Test
    fun `a live undetermined gear shows the unknown placeholder`() {
        assertEquals(GearBoxContent.Unknown, gearBoxContent(gear = null, isNeutral = false, isLive = true))
    }

    @Test
    fun `neutral shows N and wins over a gear that is still set`() {
        assertEquals(GearBoxContent.Neutral, gearBoxContent(gear = null, isNeutral = true, isLive = true))
        assertEquals(GearBoxContent.Neutral, gearBoxContent(gear = 2, isNeutral = true, isLive = true))
    }

    @Test
    fun `without a connection neither a gear nor neutral is shown`() {
        assertEquals(GearBoxContent.Unknown, gearBoxContent(gear = 4, isNeutral = false, isLive = false))
        assertEquals(GearBoxContent.Unknown, gearBoxContent(gear = null, isNeutral = true, isLive = false))
    }

    @Test
    fun `neutral is the fixed traffic-light green, not a theme color`() {
        // Wie die reale Leerlauf-Kontrollleuchte: dasselbe feste Gruen in jedem Farbschema.
        assertEquals(
            StatusColors.TrafficLightGreen,
            gearBoxColor(GearBoxContent.Neutral, engagedColor = Color.Red, unknownColor = Color.Blue),
        )
        assertEquals(
            StatusColors.TrafficLightGreen,
            gearBoxColor(GearBoxContent.Neutral, engagedColor = Color.Blue, unknownColor = Color.Red),
        )
    }

    @Test
    fun `a gear uses the engaged color and the placeholder the unknown color`() {
        assertEquals(Color.Red, gearBoxColor(GearBoxContent.Engaged(3), engagedColor = Color.Red, unknownColor = Color.Blue))
        assertEquals(Color.Blue, gearBoxColor(GearBoxContent.Unknown, engagedColor = Color.Red, unknownColor = Color.Blue))
    }
}
