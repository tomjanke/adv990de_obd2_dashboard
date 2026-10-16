package de.adv990de.obd2.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.theme.StatusColors

/**
 * Fuellfarbe und Beschriftungsfarbe eines Status-Chips. Eine Klasse und zwei Rezepte fuer jede Stelle, an der ein Chip eine
 * Statusbedeutung traegt (Level einer Log-Zeile: `logLevelChipColors`; Fehlercodes einer Fahrt: `sessionDtcChipColors`), damit gleiche
 * Bedeutung gleiche Farben hat und kein Rezept zweimal steht.
 */
internal data class StatusChipColors(val container: Color, val content: Color)

/** Rot: ein Fehler (`errorContainer` des Schemas — in den Cockpit-Schemata aus der Palette abgeleitet, s. CLAUDE.md „Ergänzte Farbrollen“). */
internal fun errorChipColors(scheme: ColorScheme) = StatusChipColors(scheme.errorContainer, scheme.onErrorContainer)

/**
 * Gelb: eine Warnung, aus der Statusfarbe ([StatusColors.container]) und NICHT aus `tertiaryContainer`: im blauen und im gruenen
 * Cockpit-Schema liegt der um 1–2 Grad Farbton neben `primaryContainer` — ein Warn-Chip waere vom Info-Chip nicht zu unterscheiden.
 */
internal fun warningChipColors(scheme: ColorScheme) =
    StatusChipColors(StatusColors.container(StatusColors.Warning, scheme.surface), scheme.onSurface)
