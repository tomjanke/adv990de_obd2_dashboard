package de.adv990de.obd2.ui.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.ui.theme.StatusColors

/** Fuellfarbe und Beschriftungsfarbe des Level-Chips einer Log-Zeile. */
internal data class LogChipColors(val container: Color, val content: Color)

/**
 * Die Farben der Level-Chips in den App-Logs: INFO traegt das Thema (`primaryContainer`), ERROR ist rot
 * (`errorContainer`), WARN ist gelb aus der Statusfarbe ([StatusColors.container]).
 *
 * WARN ist bewusst NICHT `tertiaryContainer`: Im blauen und im gruenen Cockpit-Schema liegt der um 1–2 Grad Farbton neben
 * `primaryContainer` — INFO und WARN waeren nicht zu unterscheiden (die Rollen sind seit 1.24.9 aus der Palette
 * abgeleitet; vorher fiel M3 auf Violett/Mauve zurueck, was die Chips nur zufaellig unterscheidbar machte).
 */
internal fun logLevelChipColors(level: LogLevel, scheme: ColorScheme): LogChipColors = when (level) {
    LogLevel.INFO -> LogChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    LogLevel.WARN -> LogChipColors(StatusColors.container(StatusColors.Warning, scheme.surface), scheme.onSurface)
    LogLevel.ERROR -> LogChipColors(scheme.errorContainer, scheme.onErrorContainer)
}
