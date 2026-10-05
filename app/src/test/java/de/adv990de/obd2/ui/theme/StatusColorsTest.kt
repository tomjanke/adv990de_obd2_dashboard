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
}
