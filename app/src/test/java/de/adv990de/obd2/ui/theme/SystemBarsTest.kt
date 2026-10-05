package de.adv990de.obd2.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Symbole der Statusleiste (Uhr, Akku, Benachrichtigungen) muessen zum HINTERGRUND der App passen, nicht zum
 * Systemmodus: Ab Android 15 zeichnet die App hinter die (durchsichtige) Statusleiste, die Symbolfarbe kam aber vom
 * `DayNight`-Theme, also aus dem Systemmodus — helles System + Cockpit-Schema (fast schwarz) ergab dunkle Symbole auf
 * Schwarz, dunkles System + helles Standard-Schema weisse auf Hell.
 */
class SystemBarsTest {

    @Test
    fun `a light background gets dark status bar icons`() {
        assertTrue(useDarkStatusBarIcons(lightColorScheme()))
    }

    @Test
    fun `a dark background gets light status bar icons`() {
        assertFalse(useDarkStatusBarIcons(darkColorScheme()))
    }

    @Test
    fun `the three cockpit schemes are dark, so their status bar icons are light - in every system mode`() {
        assertFalse(useDarkStatusBarIcons(blueColorScheme))
        assertFalse(useDarkStatusBarIcons(amberColorScheme))
        assertFalse(useDarkStatusBarIcons(greenColorScheme))
    }

    @Test
    fun `the decision follows the background, not the primary colour`() {
        // Heller Hintergrund mit dunkler Primaerfarbe und umgekehrt: nur der Hintergrund zaehlt.
        assertTrue(useDarkStatusBarIcons(lightColorScheme(background = Color.White, primary = Color.Black)))
        assertFalse(useDarkStatusBarIcons(darkColorScheme(background = Color.Black, primary = Color.White)))
    }

    @Test
    fun `the app only controls the icons where it draws behind a transparent status bar - Android 15 and later`() {
        // Davor hat die Statusleiste eine eigene, undurchsichtige Farbe aus dem Theme; dort passende Symbole zu erzwingen
        // (z. B. dunkle auf Schwarz bei dunklem System + hellem App-Schema) waere ein Fehler.
        for (sdk in 26..34) assertFalse("API $sdk", controlsStatusBarIcons(sdk))
        for (sdk in 35..37) assertTrue("API $sdk", controlsStatusBarIcons(sdk))
    }
}
