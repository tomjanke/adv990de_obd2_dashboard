package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.asLiveValue
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.delay

/**
 * Ersetzt `BottomNav`s `NavigationBar` genau dann, wenn sie waehrend der Fahrt ausgeblendet wird
 * (`DriveStateViewModel.isNavHidden` — Fahrzeug bewegt sich UND `hideNavOnDrive`-Einstellung an).
 * Erscheint auf jedem Screen, nicht nur dem Dashboard, weil `BottomNav` in der einen app-weiten
 * `Scaffold` sitzt (s. `AppNavHost.kt`) — User-bestaetigte Design-Entscheidung, kein Uebersehen.
 *
 * Datum/Uhrzeit links, Aussentemperatur rechts (nur wenn vorhanden — `WeatherRepository` liefert
 * `null` statt zu werfen, s. dessen KDoc). Die Temperatur folgt der Einstellung "Imperiale
 * Einheiten" ([useImperial] -> °F) ueber dieselbe `convertUnit`-Konvention wie `SensorCard`
 * (s. [formatOutsideTemperature]). Aufbau exakt wie die `NavigationBar`, die diese Row ersetzt
 * (s. `BottomNav.kt`): `HorizontalDivider` darueber, 64dp hohe `surface`-Flaeche (die NavigationBar
 * setzt `surface` dort ausdruecklich), `navigationBarsPadding()` VOR der Hoehe und damit ausserhalb
 * der Flaeche — der System-Inset-Streifen darunter zeigt bei beiden denselben Scaffold-Hintergrund.
 * So erzeugt der `AnimatedContent`-Wechsel weder einen Sprung in Hoehe/Inset-Handling noch einen
 * Farbwechsel.
 *
 * Farbe bewusst `onSurfaceVariant` fuer beide Werte statt `LiveValueText`s Default `primary`: diese
 * Zeile ersetzt Chrome (die Navigationsleiste) und ist keine dashboard-kritische Live-Ablesung wie
 * RPM/Kuehlmitteltemperatur in einer Kachel — per CLAUDE.md-Farbtabelle ist das hier "Sekundär"-
 * Information, nicht der "Primärer Live-Wert"-Akzent. (Matches, wie jede andere Produktions-Callsite
 * von `LiveValueText` in diesem Projekt bereits `color` explizit setzt statt den Default zu erben.)
 */
@Composable
fun AmbientStatusRow(outsideTempC: Float?, useImperial: Boolean) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Tick genau auf den Minutenwechsel (die Anzeige hat keine Sekunden) statt in festem
        // Abstand ab dem Einblenden — sonst sprang die Minute bis zu einem Intervall zu spaet um.
        // Jede Runde rechnet neu von der aktuellen Uhrzeit aus, gleicht also verspaetete Ticks
        // und Uhr-Umstellungen von selbst aus.
        while (true) {
            delay(millisUntilNextMinute(System.currentTimeMillis()))
            nowMillis = System.currentTimeMillis()
        }
    }
    // NOT Locale.getDefault(): that reads a non-observable global (Compose lint "NonObservableLocale")
    // and would not recompose if the user changes the system locale without restarting the app.
    val locale = LocalLocale.current.platformLocale

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveValueText(
                    value = formatDashboardHeader(nowMillis, locale),
                    style = MaterialTheme.typography.labelSmall.asLiveValue(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                formatOutsideTemperature(outsideTempC, useImperial)?.let { temperature ->
                    LiveValueText(
                        value = temperature,
                        style = MaterialTheme.typography.labelSmall.asLiveValue(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// region — Previews

@Preview(name = "AmbientStatusRow · with temperature (blue)", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowWithTemp() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = 18.4f, useImperial = false)
        }
    }
}

@Preview(name = "AmbientStatusRow · no temperature yet (blue)", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowNoTemp() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = null, useImperial = false)
        }
    }
}

@Preview(name = "AmbientStatusRow · amber theme, imperial", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowAmber() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = -3.2f, useImperial = true)
        }
    }
}

@Preview(name = "AmbientStatusRow · default light", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowDefaultLight() {
    // Das Default-Farbschema bietet Hell/Dunkel/System (CLAUDE.md, Design-Prinzip 2) — die Zeile
    // erscheint dort also auch auf heller Flaeche.
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = 18.4f, useImperial = false)
        }
    }
}

// endregion
