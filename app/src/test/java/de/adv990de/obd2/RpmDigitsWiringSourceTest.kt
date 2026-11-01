package de.adv990de.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Ziffern im Drehzahlbalken (Nutzerwunsch 2026-11-01) zeigen die echte Drehzahl, nie die Start-Animation: das entscheidet `rpmDigitsEnabled(isLive, demoRpm)`.
 * Der Segmentbalken ist ein `Canvas`, den kein Compose-Test auslesen kann (s. `DashboardScreenTest`); dass `DashboardScreen` die Entscheidung auch an `RpmBar` weitergibt
 * und `RpmBar` das Fenster zeichnet, haelt deshalb der Quelltext fest. Wer die Ziffern anders freigibt, aendert diese Entscheidung bewusst (und diesen Test).
 */
class RpmDigitsWiringSourceTest {

    private val dashboard = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui/dashboard")

    @Test
    fun `the dashboard screen gives the bar the decision of rpmDigitsEnabled with the animated value`() {
        val screen = File(dashboard, "DashboardScreen.kt").readText()

        assertTrue(
            "DashboardScreen muss RpmBar(digitsEnabled = rpmDigitsEnabled(isLive, demoRpm)) rufen: die Start-Animation zeigt keine Ziffern",
            Regex("""digitsEnabled\s*=\s*rpmDigitsEnabled\(\s*isLive\s*,\s*demoRpm\s*\)""").containsMatchIn(screen),
        )
    }

    @Test
    fun `no other caller of the bar sets digitsEnabled`() {
        val callers = dashboard.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DashboardComponents.kt" }
            .filter { Regex("""\bdigitsEnabled\s*=""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        assertEquals("digitsEnabled setzt nur DashboardScreen: $callers", listOf("DashboardScreen.kt"), callers)
    }

    @Test
    fun `the bar draws the window after the segments and takes its text from rpmDigitsText`() {
        val bar = File(dashboard, "DashboardComponents.kt").readText()

        assertTrue("RpmBar muss den Text aus rpmDigitsText(rpm, digitsEnabled) nehmen", Regex("""rpmDigitsText\(\s*rpm\s*,\s*digitsEnabled\s*\)""").containsMatchIn(bar))
        val segments = bar.indexOf("drawRect(")
        val digits = bar.indexOf("drawRpmDigits(")
        assertTrue("RpmBar muss drawRpmDigits rufen", digits >= 0)
        assertTrue("das Fenster liegt ueber den Segmenten: drawRpmDigits steht nach dem Zeichnen der Segmente", digits > segments && segments >= 0)
    }

    @Test
    fun `the digits are drawn with the title large size that the ambient status row already uses`() {
        val digits = File(dashboard, "RpmDigits.kt").readText()

        val bar = File(dashboard, "DashboardComponents.kt").readText()

        assertTrue("die Ziffern nutzen titleLarge.asLiveValue() — eine schon benutzte Groesse", digits.contains("typography.titleLarge.asLiveValue()"))
        assertTrue("RpmBar reicht die Typografie des Themes an die Ziffern", bar.contains("rpmDigitsTextStyle(MaterialTheme.typography)"))
    }
}
