// app/src/main/java/de/adv990de/obd2/ui/theme/ColorSchemes.kt
package de.adv990de.obd2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Farbrollen, die Material-3-Komponenten tatsaechlich benutzen und die frueher fehlten (Stand
 * 2026-10-04): ohne sie fiel M3 auf seine violett-grauen Standardtoene zurueck — gewaehlte Segmente
 * und Nav-Indikator (`secondaryContainer`), Snackbar (`inverse*`), Dialog, Schalter-Spur und
 * Slider-Spur (`surfaceContainer*`). Alle Werte sind aus der Palette des jeweiligen Schemas
 * abgeleitet (Container = Surface zu 28 % Richtung Primary gemischt, Inverse = die helle
 * `onSurface`-Tonlage, `inversePrimary` = dunkle Primary-Variante fuer Text auf der hellen
 * Snackbar, Container-Leiter = `surfaceVariant` zu 6 %/12 % Richtung `onSurface`);
 * `ColorSchemesTest` haelt WCAG-Kontraste (Text >= 7:1, Aktion >= 4,5:1) und den Farbton fest.
 *
 * Extrahiert (Backlog-Cleanup, Sweep 2026-09-28/29): die drei Cockpit-Schemata unten waren drei
 * `darkColorScheme(...)`-Aufrufe mit identischen 12 benannten Parametern, nur die Farbliterale
 * unterschieden sich. Reine mechanische Extraktion, keine Werte geaendert -- jeder Aufruf unten
 * uebergibt exakt dieselben Literale wie vorher direkt an `darkColorScheme`.
 */
private fun cockpitColorScheme(
    background: Color,
    onBackground: Color,
    surface: Color,
    onSurface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    primary: Color,
    onPrimary: Color,
    secondary: Color,
    onSecondary: Color,
    tertiary: Color,
    onTertiary: Color,
    outline: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    inverseSurface: Color,
    inverseOnSurface: Color,
    inversePrimary: Color,
    surfaceContainerLowest: Color,
    surfaceContainerLow: Color,
    surfaceContainer: Color,
    surfaceContainerHigh: Color,
    surfaceContainerHighest: Color,
): ColorScheme = darkColorScheme(
    background       = background,
    onBackground     = onBackground,
    surface          = surface,
    onSurface        = onSurface,
    surfaceVariant   = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    primary          = primary,
    onPrimary        = onPrimary,
    secondary        = secondary,
    onSecondary      = onSecondary,
    tertiary         = tertiary,
    onTertiary       = onTertiary,
    outline          = outline,
    secondaryContainer      = secondaryContainer,
    onSecondaryContainer    = onSecondaryContainer,
    inverseSurface          = inverseSurface,
    inverseOnSurface        = inverseOnSurface,
    inversePrimary          = inversePrimary,
    surfaceContainerLowest  = surfaceContainerLowest,
    surfaceContainerLow     = surfaceContainerLow,
    surfaceContainer        = surfaceContainer,
    surfaceContainerHigh    = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
)

val blueColorScheme = cockpitColorScheme(
    background       = Color(0xFF050D1A),
    onBackground     = Color(0xFFE2F3FB),
    surface          = Color(0xFF0A1628),
    onSurface        = Color(0xFFE2F3FB),
    surfaceVariant   = Color(0xFF0D2035),
    onSurfaceVariant = Color(0xFF9DC5D6),
    primary          = Color(0xFF38BDF8),
    onPrimary        = Color(0xFF000D1A),
    secondary        = Color(0xFF0EA5E9),
    onSecondary      = Color(0xFF000D1A),
    tertiary         = Color(0xFF7DD3FC),
    onTertiary       = Color(0xFF000D1A),
    outline          = Color(0xFF3D6070),
    secondaryContainer      = Color(0xFF174562),
    onSecondaryContainer    = Color(0xFFE2F3FB),
    inverseSurface          = Color(0xFFE2F3FB),
    inverseOnSurface        = Color(0xFF050D1A),
    inversePrimary          = Color(0xFF0369A1),
    surfaceContainerLowest  = Color(0xFF050D1A),
    surfaceContainerLow     = Color(0xFF0A1628),
    surfaceContainer        = Color(0xFF0A1628),
    surfaceContainerHigh    = Color(0xFF1A2D41),
    surfaceContainerHighest = Color(0xFF27394D),
)

val amberColorScheme = cockpitColorScheme(
    background       = Color(0xFF110A02),
    onBackground     = Color(0xFFFBEBD5),
    surface          = Color(0xFF1E1005),
    onSurface        = Color(0xFFFBEBD5),
    surfaceVariant   = Color(0xFF2A1A08),
    onSurfaceVariant = Color(0xFFD4A574),
    primary          = Color(0xFFFB923C),
    onPrimary        = Color(0xFF1A0500),
    secondary        = Color(0xFFF59E0B),
    onSecondary      = Color(0xFF1A0500),
    tertiary         = Color(0xFFFCD34D),
    onTertiary       = Color(0xFF1A0500),
    outline          = Color(0xFF6B4020),
    secondaryContainer      = Color(0xFF5C3414),
    onSecondaryContainer    = Color(0xFFFBEBD5),
    inverseSurface          = Color(0xFFFBEBD5),
    inverseOnSurface        = Color(0xFF110A02),
    inversePrimary          = Color(0xFFB4390B),
    surfaceContainerLowest  = Color(0xFF110A02),
    surfaceContainerLow     = Color(0xFF1E1005),
    surfaceContainer        = Color(0xFF1E1005),
    surfaceContainerHigh    = Color(0xFF372714),
    surfaceContainerHighest = Color(0xFF433321),
)

val greenColorScheme = cockpitColorScheme(
    background       = Color(0xFF030F08),
    onBackground     = Color(0xFFDCF5E6),
    surface          = Color(0xFF081A10),
    onSurface        = Color(0xFFDCF5E6),
    surfaceVariant   = Color(0xFF0D2A18),
    onSurfaceVariant = Color(0xFF7AB890),
    primary          = Color(0xFF4ADE80),
    onPrimary        = Color(0xFF001A08),
    secondary        = Color(0xFF10B981),
    onSecondary      = Color(0xFF001A08),
    tertiary         = Color(0xFF86EFAC),
    onTertiary       = Color(0xFF001A08),
    outline          = Color(0xFF1A5C30),
    secondaryContainer      = Color(0xFF1A512F),
    onSecondaryContainer    = Color(0xFFDCF5E6),
    inverseSurface          = Color(0xFFDCF5E6),
    inverseOnSurface        = Color(0xFF030F08),
    inversePrimary          = Color(0xFF166534),
    surfaceContainerLowest  = Color(0xFF030F08),
    surfaceContainerLow     = Color(0xFF081A10),
    surfaceContainer        = Color(0xFF081A10),
    surfaceContainerHigh    = Color(0xFF193624),
    surfaceContainerHighest = Color(0xFF264231),
)
