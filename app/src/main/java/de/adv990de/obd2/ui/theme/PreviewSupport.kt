package de.adv990de.obd2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable

/**
 * Der Rahmen der `@Preview`-Funktionen: einmal das helle Standard-Schema, einmal das dunkle Cockpit-Blau (CLAUDE.md, "Bei UI-Aenderungen
 * IMMER": jede neue Composable in Light- UND Dark-Surface, auch wenn die Cockpit-Schemata nicht hell ausgeliefert werden — es hilft beim
 * Kontrast-Check). Nur fuer Vorschauen; R8 entfernt ihn mit ihnen aus dem Release.
 */
@Composable
internal fun PreviewTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) blueColorScheme else androidx.compose.material3.lightColorScheme(),
        typography = AppTypography,
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
