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
 * with the active theme while red/amber are alarming. The only exceptions are the
 * displays documented in CLAUDE.md (red/yellow/green traffic lights, the neutral "N"), which
 * use [TrafficLightGreen].
 *
 * Palette: Tailwind 500 row, matches the project's color-scheme aesthetic.
 */
object StatusColors {
    /** Non-blocking caution (high coolant temp, low fuel, weak BLE signal). Tailwind Yellow 500 —
     *  bold/saturated on purpose (User-Wunsch 2026-09-28), same hue already used for the
     *  Bluetooth dot's "Connecting" state ([de.adv990de.obd2.ui.dashboard.connectionDotColor]),
     *  though that stays its own independent hardcoded value, not a reference to this one. */
    val Warning = Color(0xFFEAB308)

    /** Actionable redline / fault / hard limit hit. */
    val Critical = Color(0xFFEF4444)

    /**
     * Fixed "green light" (Tailwind Green 500) for the displays documented as green exceptions in
     * CLAUDE.md — and only for those: the dashboard's Bluetooth connection dot ("connected",
     * `connectionDotColor()`), the simulated oil-temperature bar ("at operating temperature",
     * `oilBarStripColor()`), and the neutral "N" in the gear box (like a motorcycle's green
     * neutral lamp, `gearBoxColor()`).
     *
     * Not a general "OK" color — every other OK/connected/nominal signal keeps using the
     * theme-tinted `tertiary` (see above). A traffic light cannot use `tertiary`, because it is
     * only actually green in the green cockpit scheme: sky blue in the blue one, amber in the
     * amber one (next to indistinguishable from [Warning]) and mauve/pink in the M3 default
     * light/dark schemes.
     */
    val TrafficLightGreen = Color(0xFF22C55E)
}
