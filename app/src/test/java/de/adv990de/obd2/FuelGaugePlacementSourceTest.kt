package de.adv990de.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * Die Tank-Segmente haben **denselben** Abstand wie die Drehzahl-Segmente (Nutzerwunsch 2026-10-15). Das haelt eine gemeinsame Konstante
     * fest, kein zweites Literal: wer den Abstand des einen aendert, aendert den des anderen mit.
     */
    @Test
    fun `the fuel gauge and the rpm bar draw their segments with the same shared gap`() {
        val fuel = File(main, "de/adv990de/obd2/ui/components/FuelGaugeRow.kt").readText()
        val rpm = File(main, "de/adv990de/obd2/ui/dashboard/DashboardComponents.kt").readText()
        val literal = Regex("""\b1\.5\.dp\b""")

        assertTrue("FuelGaugeRow muss SEGMENT_GAP nutzen", fuel.contains("SEGMENT_GAP"))
        assertTrue("RpmBar muss SEGMENT_GAP nutzen", rpm.contains("SEGMENT_GAP"))
        assertFalse("FuelGaugeRow darf den Abstand nicht als eigenes Literal fuehren", literal.containsMatchIn(fuel))
        assertFalse("RpmBar darf den Abstand nicht als eigenes Literal fuehren", literal.containsMatchIn(rpm))
    }
}
