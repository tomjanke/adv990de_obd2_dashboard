package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure decision logic extracted out of [RpmBar]'s Composable body — the blink
 * timing ([rpmBlinkPeriodMs]) and the per-segment color choice ([rpmSegmentColor]). Neither
 * function has a Compose or Android dependency (Projekt-Konvention, vgl. [GearIndicatorTest],
 * `StatusDotTest`), so both are covered here directly instead of through instrumentation tests.
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
}
