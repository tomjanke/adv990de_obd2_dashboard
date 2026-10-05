package de.adv990de.obd2.ui.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * QS C (Cockpit-Farbrollen): Die Level-Chips der App-Logs. INFO traegt das Thema (`primaryContainer`), ERROR ist rot
 * (`errorContainer`), WARN ist GELB aus der Statusfarbe — NICHT `tertiaryContainer`: Im blauen und im gruenen
 * Cockpit-Schema liegt der um 1–2 Grad Farbton neben `primaryContainer`, INFO und WARN waeren nicht zu unterscheiden.
 */
class LogLevelColorsTest {

    private val schemes: Map<String, ColorScheme> = mapOf(
        "blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme,
        "default-light" to lightColorScheme(), "default-dark" to darkColorScheme(),
    )

    private fun linear(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun luminance(c: Color) = 0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)
    private fun contrast(a: Color, b: Color): Float = (max(luminance(a), luminance(b)) + 0.05f) / (min(luminance(a), luminance(b)) + 0.05f)

    private fun hue(c: Color): Float {
        val r = c.red; val g = c.green; val b = c.blue
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
        return (h * 60f + 360f) % 360f
    }

    private fun hueDistance(a: Color, b: Color): Float { val d = kotlin.math.abs(hue(a) - hue(b)); return min(d, 360f - d) }

    @Test
    fun `INFO is the themed container, ERROR the error container`() {
        for ((name, s) in schemes) {
            assertEquals(name, s.primaryContainer, logLevelChipColors(LogLevel.INFO, s).container)
            assertEquals(name, s.onPrimaryContainer, logLevelChipColors(LogLevel.INFO, s).content)
            assertEquals(name, s.errorContainer, logLevelChipColors(LogLevel.ERROR, s).container)
            assertEquals(name, s.onErrorContainer, logLevelChipColors(LogLevel.ERROR, s).content)
        }
    }

    @Test
    fun `the three levels have three different colours in every scheme`() {
        for ((name, s) in schemes) {
            val containers = LogLevel.values().map { logLevelChipColors(it, s).container }
            assertEquals("$name: ${containers}", 3, containers.toSet().size)
        }
    }

    @Test
    fun `the label on every chip is legible in every scheme`() {
        for ((name, s) in schemes) for (level in LogLevel.values()) {
            val c = logLevelChipColors(level, s)
            val ratio = contrast(c.content, c.container)
            assertTrue("$name/$level: label on chip is $ratio:1, needs >= 4.5", ratio >= 4.5f)
        }
    }

    @Test
    fun `WARN is yellow-ish and clearly apart from INFO and ERROR in the cockpit schemes`() {
        for ((name, s) in mapOf("blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme)) {
            val info = logLevelChipColors(LogLevel.INFO, s).container
            val warn = logLevelChipColors(LogLevel.WARN, s).container
            val error = logLevelChipColors(LogLevel.ERROR, s).container
            assertTrue("$name: WARN is not yellow (hue ${hue(warn)})", hue(warn) in 35f..70f)
            assertTrue("$name: WARN vs INFO only ${hueDistance(warn, info)} degrees apart", hueDistance(warn, info) >= 10f)
            assertTrue("$name: WARN vs ERROR only ${hueDistance(warn, error)} degrees apart", hueDistance(warn, error) >= 20f)
        }
    }

    @Test
    fun `WARN is not the tertiary container - that one sits next to INFO in the blue and green scheme`() {
        for ((name, s) in mapOf("blue" to blueColorScheme, "green" to greenColorScheme)) {
            assertNotEquals(name, s.tertiaryContainer, logLevelChipColors(LogLevel.WARN, s).container)
            assertTrue("$name: premise - tertiaryContainer is within 5 degrees of primaryContainer", hueDistance(s.tertiaryContainer, s.primaryContainer) <= 5f)
        }
    }
}
