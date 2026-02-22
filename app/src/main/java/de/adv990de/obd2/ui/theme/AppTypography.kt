package de.adv990de.obd2.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import de.adv990de.obd2.R

private val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** M3 default sizes / line-heights kept; only fontFamily is replaced. */
val AppTypography = Typography().run {
    copy(
        displayLarge   = displayLarge.copy(fontFamily   = SpaceGroteskFamily),
        displayMedium  = displayMedium.copy(fontFamily  = SpaceGroteskFamily),
        displaySmall   = displaySmall.copy(fontFamily   = SpaceGroteskFamily),
        headlineLarge  = headlineLarge.copy(fontFamily  = SpaceGroteskFamily),
        headlineMedium = headlineMedium.copy(fontFamily = SpaceGroteskFamily),
        headlineSmall  = headlineSmall.copy(fontFamily  = SpaceGroteskFamily),
        titleLarge     = titleLarge.copy(fontFamily     = SpaceGroteskFamily),
        titleMedium    = titleMedium.copy(fontFamily    = SpaceGroteskFamily),
        titleSmall     = titleSmall.copy(fontFamily     = SpaceGroteskFamily),
        bodyLarge      = bodyLarge.copy(fontFamily      = SpaceGroteskFamily),
        bodyMedium     = bodyMedium.copy(fontFamily     = SpaceGroteskFamily),
        bodySmall      = bodySmall.copy(fontFamily      = SpaceGroteskFamily),
        labelLarge     = labelLarge.copy(fontFamily     = SpaceGroteskFamily),
        labelMedium    = labelMedium.copy(fontFamily    = SpaceGroteskFamily),
        labelSmall     = labelSmall.copy(fontFamily     = SpaceGroteskFamily),
    )
}
