package de.adv990de.obd2.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R

/** Global scale applied to every M3 typography size. Increase to improve motorcycle readability. */
private const val BIKE_SCALE = 1.18f

private val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** Monospaced family for live values — IBM Plex Mono, premium-utility feel, glyph-stable digits. */
val PlexMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

/**
 * Tile-size value style for the standard sensor-card reading (coolant temp, voltage, etc).
 * Sized to match M3 `displayMedium` so direct swap of existing displayMedium-styled values
 * preserves vertical rhythm. Same `tnum` guarantee.
 */
val ValueTileStyle = TextStyle(
    fontFamily = PlexMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = (45f * BIKE_SCALE).sp,
    lineHeight = (52f * BIKE_SCALE).sp,
    fontFeatureSettings = "tnum",
)

/**
 * Ziffer der virtuellen Ganganzeige neben dem RPM-Balken.
 *
 * Stand 2026-09-24 (User-Wunsch): identisch mit [ValueTileStyle], damit die Gang-Ziffer optisch
 * exakt so gross ist wie die Sensorwerte in den Dashboard-Kacheln. Zuvor war dieser Style bewusst
 * NICHT mit BIKE_SCALE skaliert (Groesse 40sp, dann 45sp, dann 46sp, jeweils an die feste
 * Innenhoehe von `RpmBar` angepasst) — diese Entkopplung ist jetzt aufgehoben: `RpmBar`s
 * Zeilenhoehe (72dp→78dp) und die Gang-Box (32dp→40dp breit) wurden stattdessen vergroessert,
 * damit die Box [ValueTileStyle] unveraendert aufnehmen kann. Wuerde BIKE_SCALE kuenftig geaendert,
 * wächst diese Ziffer automatisch mit — keine unabhaengige Nachpflege mehr noetig, im Gegensatz
 * zur vorherigen fest-verdrahteten Groesse.
 */
val GearIndicatorStyle = ValueTileStyle

/**
 * Adapter for any existing M3 [TextStyle] that should be promoted to a live-value style without
 * changing its size: swaps in IBM Plex Mono and enables tabular numerals. Use sparingly — prefer
 * [ValueTileStyle] for canonical uses.
 *
 * Example: `MaterialTheme.typography.titleSmall.asLiveValue()` for compact inline readouts where
 * the surrounding layout was built around a specific text size.
 */
fun TextStyle.asLiveValue() = copy(
    fontFamily = PlexMonoFamily,
    fontFeatureSettings = "tnum",
)

/** Swap font family and scale fontSize + lineHeight by BIKE_SCALE. */
internal fun TextStyle.bike() = copy(
    fontFamily = SpaceGroteskFamily,
    fontSize   = (fontSize.value   * BIKE_SCALE).sp,
    lineHeight = (lineHeight.value * BIKE_SCALE).sp,
)

val AppTypography = Typography().run {
    copy(
        displayLarge   = displayLarge.bike(),
        displayMedium  = displayMedium.bike(),
        displaySmall   = displaySmall.bike(),
        headlineLarge  = headlineLarge.bike(),
        headlineMedium = headlineMedium.bike(),
        headlineSmall  = headlineSmall.bike(),
        titleLarge     = titleLarge.bike(),
        titleMedium    = titleMedium.bike(),
        titleSmall     = titleSmall.bike(),
        bodyLarge      = bodyLarge.bike(),
        bodyMedium     = bodyMedium.bike(),
        bodySmall      = bodySmall.bike(),
        labelLarge     = labelLarge.bike(),
        labelMedium    = labelMedium.bike(),
        labelSmall     = labelSmall.bike(),
    )
}
