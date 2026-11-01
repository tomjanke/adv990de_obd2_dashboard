package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Die Drehzahl als Ziffern links im [RpmBar] (Nutzerwunsch 2026-11-01, Variante „Fenster“): was der Text sagt ([rpmDigitsText]), wann es ihn gibt
 * ([rpmDigitsEnabled]) und wo das Fenster und die Ziffern stehen ([rpmDigitsLayout], reine Rechnung in Bildpunkten).
 */
class RpmDigitsTest {

    // --- rpmDigitsText: ganze rpm wie die Drehzahl-Kachel (formatSensorNumber), nur bei Anzeige ---

    @Test
    fun `the text is the rpm rounded to whole numbers, like the tile`() {
        assertEquals("2844", withDefaultLocale(Locale.US) { rpmDigitsText(2843.6f, enabled = true) })
        assertEquals("2844", withDefaultLocale(Locale.GERMANY) { rpmDigitsText(2843.6f, enabled = true) })
        assertEquals("2843", withDefaultLocale(Locale.GERMANY) { rpmDigitsText(2843.4f, enabled = true) })
    }

    @Test
    fun `half a revolution rounds up, as the tile does`() {
        assertEquals("950", withDefaultLocale(Locale.US) { rpmDigitsText(949.5f, enabled = true) })
    }

    @Test
    fun `an engine at rest shows 0`() {
        assertEquals("0", withDefaultLocale(Locale.US) { rpmDigitsText(0f, enabled = true) })
        assertEquals("0", withDefaultLocale(Locale.US) { rpmDigitsText(0.4f, enabled = true) })
    }

    @Test
    fun `the largest value the PID can carry has five digits`() {
        assertEquals("16384", withDefaultLocale(Locale.US) { rpmDigitsText(16383.75f, enabled = true) })
    }

    @Test
    fun `an absurd value is held at five digits so the window cannot outgrow the bar`() {
        assertEquals("99999", withDefaultLocale(Locale.US) { rpmDigitsText(123_456f, enabled = true) })
        assertEquals("99999", withDefaultLocale(Locale.US) { rpmDigitsText(Float.MAX_VALUE, enabled = true) })
    }

    @Test
    fun `no text when the digits are not enabled, whatever the value`() {
        assertNull(rpmDigitsText(3000f, enabled = false))
        assertNull(rpmDigitsText(0f, enabled = false))
    }

    @Test
    fun `no text for a value that is not a plausible rpm`() {
        assertNull(rpmDigitsText(Float.NaN, enabled = true))
        assertNull(rpmDigitsText(Float.POSITIVE_INFINITY, enabled = true))
        assertNull(rpmDigitsText(Float.NEGATIVE_INFINITY, enabled = true))
        assertNull(rpmDigitsText(-1f, enabled = true))
    }

    // --- rpmDigitsEnabled: nur die echte Drehzahl, nie die Start-Animation ---

    @Test
    fun `the digits show with a live connection and the real rpm`() {
        assertTrue(rpmDigitsEnabled(isLive = true, demoRpm = null))
    }

    @Test
    fun `the digits show a live engine at rest, where the demo value is 0 and nothing is animated`() {
        assertTrue(rpmDigitsEnabled(isLive = true, demoRpm = 0f))
    }

    @Test
    fun `the digits stay away while the start animation runs, even with a live connection`() {
        assertFalse(rpmDigitsEnabled(isLive = true, demoRpm = 1f))
        assertFalse(rpmDigitsEnabled(isLive = true, demoRpm = 5000f))
        assertFalse(rpmDigitsEnabled(isLive = true, demoRpm = 10_000f))
    }

    @Test
    fun `the digits never show without a live connection`() {
        assertFalse(rpmDigitsEnabled(isLive = false, demoRpm = null))
        assertFalse(rpmDigitsEnabled(isLive = false, demoRpm = 0f))
        assertFalse(rpmDigitsEnabled(isLive = false, demoRpm = 5000f))
    }

    // --- rpmDigitsLayout: Fenster und Ziffern in Bildpunkten (Balken 90 px hoch, Rand 18 px, Fensterrand 11 px, Ziffernhoehe 40 px) ---

    private fun layout(textWidth: Float = 140f, minWidth: Float = 140f, bar: Float = 90f) = rpmDigitsLayout(
        barHeightPx = bar, textWidthPx = textWidth, minTextWidthPx = minWidth,
        digitHeightPx = 40f, startPaddingPx = 18f, plateVerticalPaddingPx = 11f,
    )

    @Test
    fun `the window starts flush at the left edge of the bar`() {
        assertEquals(0f, layout().plateLeft, 0f)
    }

    @Test
    fun `the digits start after the left padding`() {
        assertEquals(18f, layout().textLeft, 0f)
    }

    @Test
    fun `the window ends one padding after the digits`() {
        assertEquals(18f + 140f + 18f, layout().plateRight, 0f)
    }

    @Test
    fun `a shorter number keeps the window as wide as four digits`() {
        // 999 -> 1000 darf den rechten Rand des Fensters nicht verschieben.
        assertEquals(layout(textWidth = 140f).plateRight, layout(textWidth = 105f).plateRight, 0f)
    }

    @Test
    fun `a longer number widens the window`() {
        assertEquals(18f + 175f + 18f, layout(textWidth = 175f).plateRight, 0f)
    }

    @Test
    fun `the window is as high as the digits plus a padding above and below, centred in the bar`() {
        val l = layout()
        assertEquals(40f + 2 * 11f, l.plateBottom - l.plateTop, 0f)
        assertEquals((90f - 62f) / 2f, l.plateTop, 0f)
        assertEquals(90f / 2f, (l.plateTop + l.plateBottom) / 2f, 0f)
    }

    @Test
    fun `the baseline puts the digits' middle on the middle of the window`() {
        // Ziffern 40 px hoch: Unterkante = Mitte + 20.
        assertEquals(90f / 2f + 40f / 2f, layout().baselineY, 0f)
    }

    @Test
    fun `the window never grows beyond the bar`() {
        val l = layout(bar = 50f) // 62 px Fenster in 50 px Balken
        assertEquals(0f, l.plateTop, 0f)
        assertEquals(50f, l.plateBottom, 0f)
    }
}
