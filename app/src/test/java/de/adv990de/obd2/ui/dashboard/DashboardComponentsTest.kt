package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.components.StatusDotState
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure decision logic extracted out of this package's Composable bodies: from
 * [RpmBar] the blink timing ([rpmBlinkPeriodMs]) and the per-segment color choice
 * ([rpmSegmentColor]); from `DashboardScreen` the engine-temperature blink-threshold scale
 * ([rpmThresholdTempScale]), the one-shot RPM demo-sweep gate ([shouldStartRpmDemoSweep]) and the
 * threshold the bar is drawn with, in the start animation and for real RPM ([rpmDisplayThreshold]);
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

    @Test
    fun `the bands have no gaps between two representable temperatures (UID-22)`() {
        // Die Baender waren Float-Bereiche (..49.9, 50..69.9, 70..84.9): alles dazwischen (49.95, 69.95, 84.95) fiel
        // in den "else"-Zweig und hatte den Faktor 1.00 — ein Schwellwert, der von der kaeltesten Bande schlagartig
        // auf den vollen Wert sprang. Jede Temperatur gehoert jetzt zu genau einer Bande.
        assertEquals(0.50f, rpmThresholdTempScale(49.95f))
        assertEquals(0.75f, rpmThresholdTempScale(69.95f))
        assertEquals(0.90f, rpmThresholdTempScale(84.95f))
    }

    @Test
    fun `the factor never decreases as the engine warms up`() {
        var previous = 0f
        var t = -40f
        while (t <= 150f) {
            val scale = rpmThresholdTempScale(t)
            assertTrue("scale fell from $previous to $scale at $t C", scale >= previous)
            previous = scale
            t += 0.05f
        }
    }

    @Test
    fun `NaN is not a plausible temperature and gets the conservative scale`() {
        assertEquals(0.50f, rpmThresholdTempScale(Float.NaN))
    }

    @Test
    fun `an infinite temperature stays in the outermost band`() {
        assertEquals(0.50f, rpmThresholdTempScale(Float.NEGATIVE_INFINITY))
        assertEquals(1.00f, rpmThresholdTempScale(Float.POSITIVE_INFINITY))
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

    // --- rpmDisplayThreshold ---
    // Start-Animation (demoRpm != null): nur die eingestellte Blinkgrenze bzw. 7000 bei 0, nie die Temperaturskalierung (Nutzerwunsch 2026-10-30).
    // Echte Drehzahl (demoRpm == null): unveraendert, mit Skalierung wenn eingeschaltet.

    @Test
    fun `the start animation uses the configured blink limit and ignores dynamic scaling`() {
        // Kalter Motor (20 C, Faktor 0.50) und Skalierung an: die Animation blinkt trotzdem ab der eingestellten Grenze, nicht ab 4000.
        assertEquals(8000, rpmDisplayThreshold(configuredThreshold = 8000, dynamicScaling = true, engineTempC = 20f, demoRpm = 5000f))
        assertEquals(8000, rpmDisplayThreshold(configuredThreshold = 8000, dynamicScaling = true, engineTempC = null, demoRpm = 5000f))
        assertEquals(8000, rpmDisplayThreshold(configuredThreshold = 8000, dynamicScaling = false, engineTempC = 20f, demoRpm = 5000f))
    }

    @Test
    fun `the start animation uses the configured limit before the first frame and after the sweep too`() {
        // demoRpm = 0 (vor dem Sweep oder nach ihm): die eingestellte Grenze steht, die Skalierung greift auch hier nicht.
        assertEquals(8000, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = 20f, demoRpm = 0f))
    }

    @Test
    fun `the start animation falls back to 7000 rpm when no blink limit is set`() {
        assertEquals(RPM_DEMO_FALLBACK_THRESHOLD, rpmDisplayThreshold(0, dynamicScaling = false, engineTempC = null, demoRpm = 4000f))
        assertEquals(7000, rpmDisplayThreshold(0, dynamicScaling = false, engineTempC = null, demoRpm = 4000f))
    }

    @Test
    fun `the 7000 rpm fallback of the start animation ignores dynamic scaling`() {
        assertEquals(7000, rpmDisplayThreshold(0, dynamicScaling = true, engineTempC = 20f, demoRpm = 4000f))
        assertEquals(7000, rpmDisplayThreshold(0, dynamicScaling = true, engineTempC = 90f, demoRpm = 10000f))
    }

    @Test
    fun `without a blink limit there is nothing to blink before the sweep starts and after it ended`() {
        // demoRpm = 0: die Animation laeuft nicht (oder ist vorbei) — kein Fallback, sonst blitzte ein Rest.
        assertEquals(0, rpmDisplayThreshold(0, dynamicScaling = true, engineTempC = 20f, demoRpm = 0f))
        assertEquals(0, rpmDisplayThreshold(0, dynamicScaling = false, engineTempC = null, demoRpm = 0f))
    }

    @Test
    fun `real rpm without a blink limit never blinks, whatever the scaling and the temperature`() {
        // Auch nicht mit dem 7000-Fallback der Animation: der gilt nur, solange der animierte Wert gezeigt wird.
        assertEquals(0, rpmDisplayThreshold(0, dynamicScaling = false, engineTempC = 90f, demoRpm = null))
        assertEquals(0, rpmDisplayThreshold(0, dynamicScaling = true, engineTempC = 90f, demoRpm = null))
    }

    @Test
    fun `real rpm uses the configured blink limit as it is when scaling is off`() {
        assertEquals(8000, rpmDisplayThreshold(8000, dynamicScaling = false, engineTempC = 20f, demoRpm = null))
        assertEquals(8000, rpmDisplayThreshold(8000, dynamicScaling = false, engineTempC = null, demoRpm = null))
    }

    @Test
    fun `real rpm scales the blink limit with the engine temperature band when scaling is on`() {
        assertEquals(4000, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = 20f, demoRpm = null)) // < 50 C: 0.50
        assertEquals(6000, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = 60f, demoRpm = null)) // < 70 C: 0.75
        assertEquals(7200, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = 80f, demoRpm = null)) // < 85 C: 0.90
        assertEquals(8000, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = 90f, demoRpm = null)) // >= 85 C: 1.00
    }

    @Test
    fun `real rpm without a temperature uses the most conservative scale when scaling is on`() {
        assertEquals(4000, rpmDisplayThreshold(8000, dynamicScaling = true, engineTempC = null, demoRpm = null))
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

    // --- oilBarGreenShare: der Timer als fortlaufender Farbwechsel des gelben Balkens (Nutzerwunsch 2026-10-16) ---
    // Solange der Timer wirklich laeuft, faerbt sich der gelbe Streifen anteilig von links nach rechts gruen: Anteil = 1 - Restzeit / 10 Minuten.
    // In jedem anderen Zustand (nicht live, nicht gelb, Timer laeuft nicht) gibt es keinen gruenen Anteil.

    private val tenMinutes = OIL_BAR_GREEN_HOLD_MS

    @Test
    fun `a running warm-up turns the yellow bar green in proportion to the elapsed time`() {
        assertEquals(0.5f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = tenMinutes / 2), 1e-6f)
        assertEquals(0.25f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = tenMinutes * 3 / 4), 1e-6f)
        assertEquals(0.9f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = tenMinutes / 10), 1e-6f)
    }

    @Test
    fun `a timer that has not started shows no green - the full ten minutes remain`() {
        assertEquals(0f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = tenMinutes), 0f)
    }

    @Test
    fun `just before the bar turns green almost all of it is green, and it never exceeds the whole bar`() {
        assertEquals(1f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = 0L), 0f)
        assertEquals(1f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = -5_000L), 0f)
        assertEquals(0f, oilBarGreenShare(OilBarColor.Yellow, isLive = true, greenInMillis = tenMinutes + 5_000L), 0f)
    }

    @Test
    fun `only a yellow bar has a green share - red, green and unknown show their plain colour`() {
        for (color in listOf(OilBarColor.Red, OilBarColor.Green, null)) {
            assertEquals("color=$color", 0f, oilBarGreenShare(color, isLive = true, greenInMillis = tenMinutes / 2), 0f)
        }
    }

    @Test
    fun `without a connection there is no green share, whatever the timer says`() {
        assertEquals(0f, oilBarGreenShare(OilBarColor.Yellow, isLive = false, greenInMillis = tenMinutes / 2), 0f)
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

    // ── rpmForSpeech (QS C UID-13) ────────────────────────────────────────

    @Test
    fun `rpmForSpeech rounds to the nearest 100`() {
        assertEquals(2800, rpmForSpeech(2840f))
        assertEquals(2900, rpmForSpeech(2850f))
        assertEquals(0, rpmForSpeech(49f))
        assertEquals(10_000, rpmForSpeech(10_000f))
    }

    @Test
    fun `rpmForSpeech is 0 for values that are no rpm`() {
        assertEquals(0, rpmForSpeech(Float.NaN))
        assertEquals(0, rpmForSpeech(Float.POSITIVE_INFINITY))
        assertEquals(0, rpmForSpeech(-120f))
    }
}

