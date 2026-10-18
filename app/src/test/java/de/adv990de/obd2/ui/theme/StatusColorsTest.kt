package de.adv990de.obd2.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Warn-Gelb als TEXT (B28): `StatusColors.Warning` (#EAB308) ist auf den dunklen Cockpit-Flaechen gut lesbar, auf
 * der hellen Fläche des Default-Schemas nur 1,87:1 — der Wert einer Warn-Kachel war dort kaum zu lesen.
 * [StatusColors.warningOn] liefert fuer helle Flaechen ein dunkleres Gelb.
 */
class StatusColorsTest {

    private fun linear(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun luminance(c: Color): Float = 0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)
    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private val lightScheme = lightColorScheme()

    @Test
    fun `the plain warning yellow is unreadable as text on the light default surface - why the second token exists`() {
        assertTrue(contrast(StatusColors.Warning, lightScheme.surface) < 2.5f)
    }

    @Test
    fun `on the light default surface and background the warning text reaches the AA contrast`() {
        assertTrue(contrast(StatusColors.warningOn(lightScheme.surface), lightScheme.surface) >= 4.5f)
        assertTrue(contrast(StatusColors.warningOn(lightScheme.background), lightScheme.background) >= 4.5f)
    }

    @Test
    fun `on the dark cockpit surfaces the bold warning yellow stays as it is and is readable`() {
        for ((name, scheme) in mapOf("blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme)) {
            assertEquals("$name surface", StatusColors.Warning, StatusColors.warningOn(scheme.surface))
            assertEquals("$name background", StatusColors.Warning, StatusColors.warningOn(scheme.background))
            assertTrue("$name: ${contrast(StatusColors.Warning, scheme.surface)}", contrast(StatusColors.Warning, scheme.surface) >= 4.5f)
        }
    }

    @Test
    fun `the default dark scheme keeps the bold yellow too`() {
        val dark = androidx.compose.material3.darkColorScheme()
        assertEquals(StatusColors.Warning, StatusColors.warningOn(dark.surface))
        assertTrue(contrast(StatusColors.Warning, dark.surface) >= 4.5f)
    }

    // ── criticalOn: das rote Warnsymbol des Alarmbands (2,49:1 im hellen Standard-Schema) ──

    private val darkDefault = androidx.compose.material3.darkColorScheme()
    private val allSchemes = mapOf("light" to lightScheme, "default dark" to darkDefault, "blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme)

    @Test
    fun `the plain critical red is below 3 to 1 against its own pale container on the light default surface - why the token exists`() {
        val container = StatusColors.container(StatusColors.Critical, lightScheme.surface)
        assertTrue("${contrast(StatusColors.Critical, container)}", contrast(StatusColors.Critical, container) < 3f)
    }

    @Test
    fun `on every surface the critical accent reaches 3 to 1 against the status container it sits on`() {
        // Nicht-Text-Kontrast (WCAG 1.4.11): das Symbol sitzt auf der gedaempften Statusflaeche (`container`), nicht auf der Flaeche selbst.
        for ((name, scheme) in allSchemes) {
            val container = StatusColors.container(StatusColors.Critical, scheme.surface)
            val ratio = contrast(StatusColors.criticalOn(scheme.surface), container)
            assertTrue("$name: $ratio:1", ratio >= 3f)
        }
    }

    @Test
    fun `on dark surfaces the bold critical red stays as it is, on the light one it is darker`() {
        for (name in listOf("default dark", "blue", "amber", "green")) {
            assertEquals(name, StatusColors.Critical, StatusColors.criticalOn(allSchemes.getValue(name).surface))
        }
        assertEquals(StatusColors.CriticalOnLight, StatusColors.criticalOn(lightScheme.surface))
        assertTrue(luminance(StatusColors.CriticalOnLight) < luminance(StatusColors.Critical))
    }

    // ── container (QS C: WARN-Chip in den App-Logs) ───────────────────────

    @Test
    fun `a status container is the surface mixed 28 percent towards the status colour`() {
        val surface = Color(0xFF0A1628)
        val c = StatusColors.container(StatusColors.Warning, surface)

        // 0x0A + 0.28 * (0xEA - 0x0A) = 10 + 64.4 ~ 74 = 0x4A
        assertTrue(kotlin.math.abs(c.red * 255f - 74f) <= 1.5f)
        assertTrue(kotlin.math.abs(c.green * 255f - (22f + 0.28f * (179f - 22f))) <= 1.5f)
        assertTrue(kotlin.math.abs(c.blue * 255f - (40f + 0.28f * (8f - 40f))) <= 1.5f)
    }

    @Test
    fun `a container on a light surface is a pale tint`() {
        val c = StatusColors.container(StatusColors.Critical, Color.White)
        assertTrue("pale, not dark: ${luminance(c)}", luminance(c) > 0.5f)
    }
}

