package de.adv990de.obd2.ui.settings

import androidx.compose.material3.ColorScheme
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.ui.components.StatusChipColors
import de.adv990de.obd2.ui.components.errorChipColors
import de.adv990de.obd2.ui.components.warningChipColors

/**
 * Die Farben der Level-Chips in den App-Logs: INFO traegt das Thema (`primaryContainer`), ERROR ist rot ([errorChipColors]),
 * WARN ist gelb ([warningChipColors] — bewusst NICHT `tertiaryContainer`, Begruendung dort). Die Rezepte fuer Rot und Gelb teilen
 * sich die Chips der Fahrten (`sessionDtcChipColors`).
 */
internal fun logLevelChipColors(level: LogLevel, scheme: ColorScheme): StatusChipColors = when (level) {
    LogLevel.INFO -> StatusChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    LogLevel.WARN -> warningChipColors(scheme)
    LogLevel.ERROR -> errorChipColors(scheme)
}
