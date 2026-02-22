package de.adv990de.obd2.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R

/** Global scale applied to every M3 typography size. Increase to improve motorcycle readability. */
private const val BIKE_SCALE = 1.2f

private val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
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
