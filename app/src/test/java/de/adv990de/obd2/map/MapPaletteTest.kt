package de.adv990de.obd2.map

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Kartenfarben: Kontrast zum Grund in jedem der fuenf Farbschemata, und die Linie des Tracks bleibt die klarste Form. */
class MapPaletteTest {

    private val schemes: List<Pair<String, ColorScheme>> = listOf(
        "blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme, "default-light" to lightColorScheme(), "default-dark" to darkColorScheme(),
    )

    // Stellvertreter fuer die Farbtoene der App (die festen Werte legt die Themendatei fest; die Rechnung haengt nicht von ihnen ab).
    private val tints = MapTints(water = 0xFF3B82F6.toInt(), wood = 0xFF22C55E.toInt(), grass = 0xFF84CC16.toInt(), farm = 0xFFEAB308.toInt(), scrub = 0xFFA16207.toInt())

    private fun fills(p: MapPalette) = listOf(p.water, p.wood, p.grass, p.farm, p.scrub)
    private fun roads(p: MapPalette) = listOf(p.roadHighway, p.roadMajor, p.roadMinor, p.roadService, p.roadTrack, p.roadPath, p.rail)

    private fun forEach(profile: MapProfile, check: (name: String, bg: Int, scheme: ColorScheme, p: MapPalette) -> Unit) = schemes.forEach { (name, s) ->
        val bg = s.background.toArgb()
        check(name, bg, s, MapPalette.derive(bg, s.onBackground.toArgb(), tints, profile, s.primary.toArgb()))
    }

    // ── Farbrechnung ──────────────────────────────────────────────────────────

    @Test
    fun `luminance and contrast agree with the WCAG definition`() {
        assertEquals(1.0, luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(21.0, contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-12)
        assertEquals(contrastRatio(0xFF112233.toInt(), 0xFFEEDDCC.toInt()), contrastRatio(0xFFEEDDCC.toInt(), 0xFF112233.toInt()), 1e-12)
        // Die Gewichte der Kanaele: reines Rot, Gruen, Blau.
        assertEquals(0.2126, luminance(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, luminance(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, luminance(0xFF0000FF.toInt()), 1e-9)
        // Grau 50 % (sRGB 128) hat die Leuchtdichte 0,2159 (bekannter Wert).
        assertEquals(0.2159, luminance(0xFF808080.toInt()), 1e-3)
    }

    @Test
    fun `mixing`() {
        assertEquals(0xFF102030.toInt(), mixArgb(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0.0))
        assertEquals(0xFFFFFFFF.toInt(), mixArgb(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1.0))
        assertEquals(0xFF808080.toInt(), mixArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5))
        assertEquals(0xFF, mixArgb(0x00000000, 0x00000000, 0.5) ushr 24) // das Ergebnis ist deckend
    }

    @Test
    fun `mixToContrast reaches the target`() {
        val bg = 0xFF050D1A.toInt()
        for (target in listOf(1.1, 1.3, 1.5, 2.0, 3.0)) {
            val c = mixToContrast(bg, 0xFFE2F3FB.toInt(), target)
            assertEquals("target $target", target, contrastRatio(bg, c), 0.03)
        }
    }

    @Test
    fun `mixToContrast keeps the base for a target of one and caps an unreachable target`() {
        val bg = 0xFF050D1A.toInt()
        assertEquals(bg, mixToContrast(bg, 0xFFFFFFFF.toInt(), 1.0))
        assertEquals(bg, mixToContrast(bg, 0xFFFFFFFF.toInt(), 0.5))
        // Eine Zielfarbe, die dem Grund fast gleicht, erreicht 3:1 nie: Mischung bei MAX_MIX.
        val near = 0xFF060E1B.toInt()
        assertEquals(mixArgb(bg, near, MAX_MIX), mixToContrast(bg, near, 3.0))
        // Ein Ziel, das erst jenseits von MAX_MIX erreicht waere (Grau 0x10 Richtung 0x30 hat auch bei voller Mischung nur 1,44:1): die Mischung bei MAX_MIX, nicht die volle.
        val dark = 0xFF101010.toInt()
        val lighter = 0xFF303030.toInt()
        assertEquals(mixArgb(dark, lighter, MAX_MIX), mixToContrast(dark, lighter, 3.0))
        assertTrue(mixToContrast(dark, lighter, 3.0) != lighter)
    }

    @Test
    fun `an avoided colour is kept at a distance even if the target to the ground would bring the colour closer`() {
        val bg = 0xFFFFFBFE.toInt()      // heller Grund
        val dark = 0xFF1C1B1F.toInt()    // Zielfarbe: fast schwarz
        val line = 0xFF6750A4.toInt()    // mittelhelle Linie
        val plain = mixToContrast(bg, dark, 2.4)
        assertTrue(contrastRatio(line, plain) < 3.2) // ohne Schutz zu nah an der Linie
        val kept = mixToContrast(bg, dark, 2.4, avoid = line, avoidMin = 3.2)
        assertTrue("${contrastRatio(line, kept)}", contrastRatio(line, kept) >= 3.2 - 0.01)
        assertTrue(contrastRatio(bg, kept) < contrastRatio(bg, plain)) // dafuer schwaecher gegen den Grund
        assertTrue(contrastRatio(bg, kept) > 1.0)
    }

    @Test
    fun `an avoided colour that is already far enough changes nothing`() {
        val bg = 0xFF050D1A.toInt()
        val light = 0xFFE2F3FB.toInt()
        assertEquals(mixToContrast(bg, light, 1.5), mixToContrast(bg, light, 1.5, avoid = 0xFF38BDF8.toInt(), avoidMin = 3.2))
    }

    @Test
    fun `a ground that is already too close to the avoided colour gives the ground`() {
        val bg = 0xFF303030.toInt()
        assertEquals(bg, mixToContrast(bg, 0xFFFFFFFF.toInt(), 1.5, avoid = 0xFF303030.toInt(), avoidMin = 3.2))
    }

    // ── Palette ───────────────────────────────────────────────────────────────

    @Test
    fun `in the HUD roads stay at most 1,5 to 1 and areas at most 1,3 to 1 against the ground`() = forEach(MapProfile.HUD) { name, bg, _, p ->
        for (c in roads(p)) assertTrue("$name Strasse ${contrastRatio(bg, c)}", contrastRatio(bg, c) <= 1.5 + 0.02)
        for (c in fills(p)) assertTrue("$name Flaeche ${contrastRatio(bg, c)}", contrastRatio(bg, c) <= 1.3 + 0.02)
    }

    @Test
    fun `every map colour is visible against the ground`() {
        for (profile in MapProfile.entries) forEach(profile) { name, bg, _, p ->
            for (c in fills(p) + roads(p)) assertTrue("$name $profile ${contrastRatio(bg, c)}", contrastRatio(bg, c) >= 1.07)
        }
    }

    @Test
    fun `the track line stays at least 3 to 1 against every map colour`() {
        for (profile in MapProfile.entries) forEach(profile) { name, _, s, p ->
            val line = s.primary.toArgb()
            for (c in fills(p) + roads(p)) assertTrue("$name $profile Linie gegen Karte ${contrastRatio(line, c)}", contrastRatio(line, c) >= 3.0)
        }
    }

    @Test
    fun `the colours hit their contrast targets`() = forEach(MapProfile.HUD) { name, bg, _, p ->
        assertEquals(name, HUD_TARGETS.roadMajor, contrastRatio(bg, p.roadMajor), 0.03)
        assertEquals(name, HUD_TARGETS.wood, contrastRatio(bg, p.wood), 0.03)
        assertEquals(name, HUD_TARGETS.water, contrastRatio(bg, p.water), 0.03)
    }

    @Test
    fun `more important roads are at least as visible and the track view is bolder than the HUD`() {
        forEach(MapProfile.TRACK_VIEW) { name, bg, _, p ->
            val c = listOf(p.roadHighway, p.roadMajor, p.roadMinor, p.roadService).map { contrastRatio(bg, it) }
            assertTrue("$name $c", c.zipWithNext().all { (a, b) -> a >= b - 1e-9 })
        }
        schemes.forEach { (name, s) ->
            val bg = s.background.toArgb()
            val hud = MapPalette.derive(bg, s.onBackground.toArgb(), tints, MapProfile.HUD, s.primary.toArgb())
            val view = MapPalette.derive(bg, s.onBackground.toArgb(), tints, MapProfile.TRACK_VIEW, s.primary.toArgb())
            assertTrue(name, contrastRatio(bg, view.roadMajor) > contrastRatio(bg, hud.roadMajor))
            assertTrue(name, contrastRatio(bg, view.wood) > contrastRatio(bg, hud.wood))
        }
    }

    @Test
    fun `the tint of an area stays visible in the hue of its colour`() = forEach(MapProfile.TRACK_VIEW) { name, bg, _, p ->
        // Wasser neigt am staerksten zu Blau, Wald am staerksten zu Gruen — gemessen am Unterschied zum Grund.
        fun d(c: Int, shift: Int) = ((c shr shift) and 0xFF) - ((bg shr shift) and 0xFF)
        assertTrue("$name Wasser", d(p.water, 0) > d(p.water, 8) && d(p.water, 0) > d(p.water, 16))
        assertTrue("$name Wald", d(p.wood, 8) > d(p.wood, 0) && d(p.wood, 8) > d(p.wood, 16))
    }
}
