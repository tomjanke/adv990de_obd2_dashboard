package de.adv990de.obd2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der System-Splash (Android 12+: Icon auf einer Flaeche) entsteht, bevor irgendein App-Code laeuft; er liest nur
 * Ressourcen. Vorher kam seine Hintergrundfarbe aus dem `DayNight`-Theme (im dunklen System ein neutrales Grau
 * `#303030`), nicht aus der App — beim Start wechselte grau auf Cockpit-Schwarz.
 *
 * Zwei Ebenen: statisch der Hell/Dunkel-Standard (das Theme, auch ohne Android 12 und beim ersten Start), und je gewaehltem
 * Farbschema ein eigenes Splash-Theme, das die App fuer den NAECHSTEN Start setzt (`SplashScreen.setSplashScreenTheme`).
 * Beide muessen die Hintergrundfarbe der echten Schemata treffen — dieser Test haelt Ressourcen und Kotlin-Schemata zusammen.
 */
class SplashThemeTest {

    private val projectRoot: File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")

    private fun res(relative: String): String = File(projectRoot, "app/src/main/res/$relative").readText()

    private fun colors(relative: String): Map<String, String> =
        Regex("""<color name="([^"]+)">([^<]+)</color>""").findAll(res(relative))
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun hex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun backgroundOf(scheme: ColorScheme): String = hex(scheme.background)

    private val day get() = colors("values/splash_colors.xml")
    private val night get() = colors("values-night/splash_colors.xml")

    @Test
    fun `every splash colour is the background of the matching colour scheme of the app`() {
        assertEquals(backgroundOf(lightColorScheme()), day["splash_background_light"])
        assertEquals(backgroundOf(darkColorScheme()), day["splash_background_dark"])
        assertEquals(backgroundOf(blueColorScheme), day["splash_background_blue"])
        assertEquals(backgroundOf(amberColorScheme), day["splash_background_amber"])
        assertEquals(backgroundOf(greenColorScheme), day["splash_background_green"])
    }

    @Test
    fun `the system splash colour is light by day and dark by night`() {
        assertEquals("@color/splash_background_light", day["splash_background_system"])
        assertEquals("@color/splash_background_dark", night["splash_background_system"])
    }

    @Test
    fun `the night folder overrides nothing else`() {
        assertEquals(setOf("splash_background_system"), night.keys)
    }

    private fun styleBlock(name: String): String {
        val themes = res("values/themes.xml")
        val match = Regex("""<style name="${Regex.escape(name)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(themes)
        return requireNotNull(match) { "Style $name fehlt in themes.xml" }.value
    }

    @Test
    fun `the base theme paints its window in the system splash colour - the system splash uses it by default`() {
        assertTrue(styleBlock("Theme.OBD2Monitor").contains("""<item name="android:windowBackground">@color/splash_background_system</item>"""))
    }

    @Test
    fun `each splash theme exists under its stable name, extends the base theme and paints its own colour`() {
        // Die Namen werden vom System PERSISTIERT (SplashScreen.setSplashScreenTheme: "must be stable across versions,
        // otherwise it won't be found after your application is updated") — nie umbenennen.
        val expected = mapOf(
            "Theme.OBD2Monitor.Splash.Light" to "splash_background_light",
            "Theme.OBD2Monitor.Splash.Dark" to "splash_background_dark",
            "Theme.OBD2Monitor.Splash.Blue" to "splash_background_blue",
            "Theme.OBD2Monitor.Splash.Amber" to "splash_background_amber",
            "Theme.OBD2Monitor.Splash.Green" to "splash_background_green",
        )
        for ((style, color) in expected) {
            val block = styleBlock(style)
            assertTrue("$style erbt vom Basis-Theme", block.contains("""parent="Theme.OBD2Monitor""""))
            assertTrue(
                "$style malt sein Fenster in @color/$color",
                block.contains("""<item name="android:windowBackground">@color/$color</item>"""),
            )
        }
    }

    @Test
    fun `the manifest launches the base theme`() {
        assertTrue(File(projectRoot, "app/src/main/AndroidManifest.xml").readText().contains("""android:theme="@style/Theme.OBD2Monitor""""))
    }

    // ── Zuordnung Farbschema -> Splash-Theme ───────────────────────────────

    @Test
    fun `each colour scheme maps to its own splash theme`() {
        assertEquals(R.style.Theme_OBD2Monitor_Splash_Light, splashThemeRes(AppPreferences.THEME_LIGHT))
        assertEquals(R.style.Theme_OBD2Monitor_Splash_Dark, splashThemeRes(AppPreferences.THEME_DARK))
        assertEquals(R.style.Theme_OBD2Monitor_Splash_Blue, splashThemeRes(AppPreferences.THEME_BLUE))
        assertEquals(R.style.Theme_OBD2Monitor_Splash_Amber, splashThemeRes(AppPreferences.THEME_AMBER))
        assertEquals(R.style.Theme_OBD2Monitor_Splash_Green, splashThemeRes(AppPreferences.THEME_GREEN))
    }

    @Test
    fun `following the system - or an unknown value - resets to the manifest theme, which follows the system mode itself`() {
        assertEquals(SPLASH_THEME_NONE, splashThemeRes(AppPreferences.THEME_SYSTEM))
        assertEquals(SPLASH_THEME_NONE, splashThemeRes("something-from-a-future-version"))
        assertEquals(0, SPLASH_THEME_NONE) // Resources.ID_NULL
    }

    @Test
    fun `the splash themes are distinct and real`() {
        val ids = listOf(
            AppPreferences.THEME_LIGHT, AppPreferences.THEME_DARK, AppPreferences.THEME_BLUE,
            AppPreferences.THEME_AMBER, AppPreferences.THEME_GREEN,
        ).map(::splashThemeRes)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotEquals(SPLASH_THEME_NONE, it) }
    }

    @Test
    fun `the splash theme can only be set from Android 12`() {
        assertEquals(false, canSetSplashTheme(30))
        assertEquals(true, canSetSplashTheme(31))
        assertEquals(true, canSetSplashTheme(37))
    }
}
