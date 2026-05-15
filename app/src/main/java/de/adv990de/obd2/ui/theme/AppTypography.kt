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
 * Display-size value style for the dominant live readout (e.g. RPM centerpiece, speed hero tile).
 * Tabular numerals enforced via `tnum` so digits do not shift width on update.
 */
val ValueDisplayStyle = TextStyle(
    fontFamily = PlexMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = (57f * BIKE_SCALE).sp,
    lineHeight = (64f * BIKE_SCALE).sp,
    fontFeatureSettings = "tnum",
)

/**
 * Tile-size value style for the standard `LiveValueTile` reading (e.g. coolant temp, voltage).
 * Same `tnum` guarantee.
 */
val ValueTileStyle = TextStyle(
    fontFamily = PlexMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = (28f * BIKE_SCALE).sp,
    lineHeight = (36f * BIKE_SCALE).sp,
    fontFeatureSettings = "tnum",
)

/** Swap font family and scale fontSize + lineHeight by BIKE_SCALE. */
private fun TextStyle.bike() = copy(
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
