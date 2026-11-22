package de.adv990de.obd2.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import de.adv990de.obd2.map.MapTints

/**
 * Die festen Farbtoene der Karte (Wasser blau, Wald und Wiese gruen, Acker gelb, Gebuesch braun). Wie stark sie sind, bestimmt [de.adv990de.obd2.map.MapPalette.derive] aus dem Kontrast zum
 * Grund des Farbschemas; hier steht nur der Ton. Tailwind-500-Reihe wie die Statusfarben.
 */
object MapColors {
    private val Water = Color(0xFF3B82F6)
    private val Wood = Color(0xFF22C55E)
    private val Grass = Color(0xFF84CC16)
    private val Farm = Color(0xFFEAB308)
    private val Scrub = Color(0xFFA16207)

    fun tints() = MapTints(water = Water.toArgb(), wood = Wood.toArgb(), grass = Grass.toArgb(), farm = Farm.toArgb(), scrub = Scrub.toArgb())
}
