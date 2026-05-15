package de.adv990de.obd2.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cross-theme semantic status tokens for live dashboard signals
 * (redline, overtemp, low voltage, fault indicators).
 *
 * These intentionally bypass [androidx.compose.material3.MaterialTheme.colorScheme] —
 * a critical-state red must read as red regardless of whether the user picked the Blue,
 * Amber, or Green theme. Glanceability while driving requires that the *meaning* of a
 * color is stable across themes.
 *
 * For semantically *neutral* M3-style error coloring (form validation, empty-state
 * error text, error banners), keep using `MaterialTheme.colorScheme.error` — that
 * scales per theme and isn't competing with the live-value signal language.
 *
 * For "OK / connected / nominal" signals use `MaterialTheme.colorScheme.tertiary`
 * (per-theme tinted) rather than a fixed green — keeps the OK signal in harmony
 * with the active theme while red/amber are alarming.
 *
 * Palette: Tailwind 500 row, matches the project's color-scheme aesthetic.
 */
object StatusColors {
    /** Non-blocking caution (high coolant temp, low fuel, weak BLE signal). */
    val Warning = Color(0xFFF59E0B)

    /** Actionable redline / fault / hard limit hit. */
    val Critical = Color(0xFFEF4444)
}
