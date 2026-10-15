package de.adv990de.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Die virtuelle Tankanzeige steht **nur im Dashboard** (Nutzerwunsch 2026-10-15), nicht in der app-weiten Navigation und auf keiner anderen Seite.
 * Das haelt der Quelltext fest: `FuelGaugeRow(` wird genau von `DashboardScreen` aufgerufen, `BottomNav` kennt sie nicht. Wer sie woanders
 * zeigen will, aendert diese Entscheidung bewusst (und diesen Test).
 */
class FuelGaugePlacementSourceTest {

    private val main = File(projectRoot, "app/src/main/java")

    private fun sources(): List<File> = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `only the dashboard screen shows the fuel gauge row`() {
        val callers = sources()
            .filter { it.name != "FuelGaugeRow.kt" }
            .filter { Regex("""\bFuelGaugeRow\(""").containsMatchIn(it.readText()) }
            .map { it.name }

        assertEquals("FuelGaugeRow( darf nur DashboardScreen aufrufen: $callers", listOf("DashboardScreen.kt"), callers)
    }

    @Test
    fun `the bottom navigation does not know the fuel gauge`() {
        val nav = File(main, "de/adv990de/obd2/ui/components/BottomNav.kt").readText()

        assertFalse("BottomNav darf die Tankanzeige nicht kennen", nav.contains("Fuel"))
    }
}
