package de.adv990de.obd2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The three cockpit schemes must define the colour roles that Material 3 components actually use,
 * otherwise they fall back to M3's violet/grey tones: `secondaryContainer` (selected segment, nav
 * indicator), `inverse*` (snackbar), `surfaceContainer*` (dialog, switch track, slider track). The
 * values are derived from each scheme's own palette; these tests pin the legibility (WCAG contrast)
 * so a later palette tweak cannot silently make text unreadable.
 */
class ColorSchemesTest {

    private val schemes: Map<String, ColorScheme> = mapOf(
        "blue" to blueColorScheme,
        "amber" to amberColorScheme,
        "green" to greenColorScheme,
    )

    private fun linear(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(c: Color): Float = 0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** Hue in degrees (0..360) of an sRGB colour. */
    private fun hue(c: Color): Float {
        val r = c.red; val g = c.green; val b = c.blue
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    private fun hueDistance(a: Color, b: Color): Float {
        val d = kotlin.math.abs(hue(a) - hue(b))
        return min(d, 360f - d)
    }

    private fun forEachScheme(check: (name: String, scheme: ColorScheme) -> Unit) =
        schemes.forEach { (name, scheme) -> check(name, scheme) }

    @Test
    fun `the selected segment is legible and visible against the unselected surface`() = forEachScheme { name, s ->
        val text = contrast(s.onSecondaryContainer, s.secondaryContainer)
        assertTrue("$name: onSecondaryContainer on secondaryContainer is $text:1, needs >= 7", text >= 7f)
        val visible = contrast(s.secondaryContainer, s.surface)
        assertTrue("$name: secondaryContainer vs surface is $visible:1, needs >= 1.5", visible >= 1.5f)
    }

    @Test
    fun `secondaryContainer is tinted by the scheme, not Material's default violet`() = forEachScheme { name, s ->
        assertNotEquals("$name falls back to the M3 default", darkColorScheme().secondaryContainer, s.secondaryContainer)
    }

    @Test
    fun `the new roles stay in the hue of their scheme instead of Material's violet-grey`() = forEachScheme { name, s ->
        // M3's fallbacks sit around 260-280 degrees; the cockpit palettes are blue (~200), amber (~27), green (~142).
        val pairs = listOf(
            "secondaryContainer ~ primary" to hueDistance(s.secondaryContainer, s.primary),
            "inversePrimary ~ primary" to hueDistance(s.inversePrimary, s.primary),
            "inverseSurface ~ onSurface" to hueDistance(s.inverseSurface, s.onSurface),
            "surfaceContainerHigh ~ surface" to hueDistance(s.surfaceContainerHigh, s.surface),
            "surfaceContainerHighest ~ surface" to hueDistance(s.surfaceContainerHighest, s.surface),
        )
        for ((what, distance) in pairs) {
            assertTrue("$name: $what differ by $distance degrees of hue, needs <= 30", distance <= 30f)
        }
    }

    @Test
    fun `the snackbar text and its action are legible`() = forEachScheme { name, s ->
        val text = contrast(s.inverseOnSurface, s.inverseSurface)
        assertTrue("$name: inverseOnSurface on inverseSurface is $text:1, needs >= 7", text >= 7f)
        val action = contrast(s.inversePrimary, s.inverseSurface)
        assertTrue("$name: inversePrimary on inverseSurface is $action:1, needs >= 4.5", action >= 4.5f)
    }

    @Test
    fun `dialog and control surfaces keep body text legible`() = forEachScheme { name, s ->
        for ((role, color) in listOf("surfaceContainerHigh" to s.surfaceContainerHigh, "surfaceContainerHighest" to s.surfaceContainerHighest)) {
            val ratio = contrast(s.onSurface, color)
            assertTrue("$name: onSurface on $role is $ratio:1, needs >= 7", ratio >= 7f)
        }
    }

    @Test
    fun `the surface container ladder gets lighter step by step`() = forEachScheme { name, s ->
        val ladder = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
        val lum = ladder.map(::luminance)
        for (i in 1 until lum.size) {
            assertTrue("$name: ladder step $i is darker than step ${i - 1}: $lum", lum[i] >= lum[i - 1])
        }
        assertTrue("$name: highest container must be clearly lighter than the lowest", lum.last() > lum.first() * 2)
    }

    // ── Rollen, die die Cockpit-Schemata bis 1.24.8 nicht definierten (QS C) ─────────────────────────────────

    /** Plain sRGB mix per channel — the same rule the existing container colours were derived with. */
    private fun mix(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t,
    )

    private fun assertSameColor(what: String, expected: Color, actual: Color) {
        val tolerance = 1.5f / 255f   // the schemes hold rounded 8-bit literals
        for ((channel, e, a) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green), Triple("blue", expected.blue, actual.blue))) {
            assertTrue("$what: $channel should be ~$e but is $a", kotlin.math.abs(e - a) <= tolerance)
        }
    }

    @Test
    fun `primary and tertiary containers are the 28 percent mix of the surface towards their main colour`() = forEachScheme { name, s ->
        assertSameColor("$name primaryContainer", mix(s.surface, s.primary, 0.28f), s.primaryContainer)
        assertSameColor("$name tertiaryContainer", mix(s.surface, s.tertiary, 0.28f), s.tertiaryContainer)
    }

    @Test
    fun `the error container is a dark red from the same rule, not M3's own`() = forEachScheme { name, s ->
        assertSameColor("$name errorContainer", mix(s.surface, StatusColors.Critical, 0.28f), s.errorContainer)
        assertNotEquals("$name: M3 default errorContainer must not be used", darkColorScheme().errorContainer, s.errorContainer)
        assertTrue("$name: errorContainer hue is red (<= 30 degrees from Critical)", hueDistance(s.errorContainer, StatusColors.Critical) <= 30f)
    }

    @Test
    fun `text on the new containers is legible`() = forEachScheme { name, s ->
        for ((role, pair) in listOf(
            "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "errorContainer" to (s.onErrorContainer to s.errorContainer),
        )) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name: on$role on $role is $ratio:1, needs >= 7", ratio >= 7f)
        }
    }

    @Test
    fun `the primary and tertiary containers keep the hue of their scheme`() = forEachScheme { name, s ->
        for ((role, color) in listOf("primaryContainer" to s.primaryContainer, "tertiaryContainer" to s.tertiaryContainer)) {
            val distance = hueDistance(color, s.primary)
            assertTrue("$name: $role differs by $distance degrees of hue from primary, needs <= 30", distance <= 30f)
        }
    }

    @Test
    fun `outlineVariant is the quiet line - between the surface and the outline, still visible, in the scheme's hue`() = forEachScheme { name, s ->
        assertSameColor("$name outlineVariant", mix(s.surface, s.outline, 0.5f), s.outlineVariant)
        assertTrue("$name: outlineVariant must be lighter than the surface", luminance(s.outlineVariant) > luminance(s.surface))
        assertTrue("$name: outlineVariant must be quieter than the outline", luminance(s.outlineVariant) < luminance(s.outline))
        val visible = contrast(s.outlineVariant, s.surface)
        assertTrue("$name: the 1 dp line between tiles is only $visible:1 against the tile, needs >= 1.3", visible >= 1.3f)
        assertTrue("$name: outlineVariant hue", hueDistance(s.outlineVariant, s.primary) <= 30f)
        assertNotEquals("$name: M3 default outlineVariant must not be used", darkColorScheme().outlineVariant, s.outlineVariant)
    }
}

