package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.dashboard.OIL_BAR_GREEN_HOLD_MS
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
 *
 * **Korrektur (2026-09-28, Feldbericht nach Release v1.17.0):** Schriftgroesse war urspruenglich
 * `labelSmall.asLiveValue()` (~13sp effektiv) — die kleinste Textrolle im ganzen Typografie-System,
 * mit der Begruendung "sekundaer, kein Live-Wert". Das widersprach in der Praxis Design-Prinzip 1
 * (Glanceability, 0,5s-Blick): unlesbar waehrend der Fahrt, unabhaengig von der Sekundaer/Primaer-
 * Einstufung. Jetzt `titleLarge.asLiveValue()` (~26sp effektiv) — deutlich groesser, aber weiterhin
 * klar kleiner als `ValueTileStyle` (~53sp) der echten Sensor-Kacheln, da diese Zeile Ambient-Info
 * bleibt, kein primaerer Messwert.
 */
@Composable
fun AmbientStatusRow(
    outsideTempC: Float?,
    useImperial: Boolean,
    /** Restzeit bis der Oel-Balken gruen wird ([OilWarmupState]); Standard = die volle Dauer ("Starttimerzeit", Timer laeuft noch nicht). */
    oilGreenInMillis: Long = OIL_BAR_GREEN_HOLD_MS,
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Tick genau auf den Minutenwechsel (die Anzeige hat keine Sekunden) statt in festem
        // Abstand ab dem Einblenden — sonst sprang die Minute bis zu einem Intervall zu spaet um.
        // Jede Runde rechnet neu von der aktuellen Uhrzeit aus, gleicht also verspaetete Ticks
        // und Uhr-Umstellungen von selbst aus.
        //
        // Bekannte, geringfuegige Grenze (gleiche Ursache wie WeatherViewModels 30-Minuten-
        // Haltefenster vor dessen elapsedRealtime-Fix, s. dortiges KDoc auf
        // `elapsedRealtimeSource` — kleinere Auswirkung hier): `delay()` plant intern gegen eine
        // schlafpausierende Uhr (unter der Haube z. B. Handler.postDelayed -> SystemClock.
        // uptimeMillis()), nicht gegen elapsedRealtime. Nach einem Tiefschlaf kann die Anzeige
        // daher bis zu einem Taktintervall (~60 s) hinter der echten Uhrzeit zurueckbleiben, bis
        // der naechste Tick nachholt — nie eine FALSCHE Zeit, nur kurzzeitig eine veraltete, die
        // sich beim naechsten Tick von selbst korrigiert. Bewusst NICHT mit demselben
        // elapsedRealtimeNanos()-Ansatz behoben: anders als bei WeatherViewModel (ein injizierbarer
        // TimeSource-Parameter fuer eine reine Dauer-Berechnung) steckt das Problem hier in
        // `delay()`s eigenem Scheduling-Primitiv selbst — eine andere Uhr fuer die Wartezeit-
        // Berechnung aendert nichts daran, wie `delay()` intern wartet. Ein echter Fix (z. B.
        // AlarmManager statt delay()) waere fuer diesen kleinen, selbstheilenden Effekt
        // unverhaeltnismaessig.
        while (true) {
            delay(millisUntilNextMinute(System.currentTimeMillis()))
            nowMillis = System.currentTimeMillis()
        }
    }
    // NOT Locale.getDefault(): that reads a non-observable global (Compose lint "NonObservableLocale")
    // and would not recompose if the user changes the system locale without restarting the app.
    val locale = LocalLocale.current.platformLocale
    val textStyle = MaterialTheme.typography.titleLarge.asLiveValue()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val oilLabel = stringResource(R.string.ambient_oil_label)
    val oilText = ambientOilText(oilLabel, oilGreenInMillis, locale)
    val oilDescription = stringResource(R.string.ambient_oil_desc, formatOilTimer(oilGreenInMillis, locale))
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                // Das volle Datum nur, wenn es neben dem Timer in seine Haelfte passt — sonst die Uhrzeit allein (s. ambientUsesCompactHeader).
                // Gemessen wird mit dem echten Stil und dem breitesten Timer-Text ("10:00"): der Entscheid springt nicht mit dem Countdown.
                val fullHeader = formatDashboardHeader(nowMillis, locale)
                val compact = ambientUsesCompactHeader(
                    availableWidth = with(density) { maxWidth.roundToPx() },
                    fullHeaderWidth = textMeasurer.measure(fullHeader, textStyle).size.width,
                    centreWidth = textMeasurer.measure(ambientOilText(oilLabel, OIL_BAR_GREEN_HOLD_MS, locale), textStyle).size.width,
                    minGap = with(density) { MIN_HEADER_GAP.roundToPx() },
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Links und rechts gleich breit (weight), damit der Timer exakt in der Mitte steht.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        LiveValueText(
                            value = if (compact) formatDashboardTime(nowMillis, locale) else fullHeader,
                            style = textStyle,
                            color = textColor,
                        )
                    }
                    LiveValueText(
                        value = oilText,
                        modifier = Modifier.semantics { contentDescription = oilDescription },
                        style = textStyle,
                        color = textColor,
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        formatOutsideTemperature(outsideTempC, useImperial)?.let { temperature ->
                            LiveValueText(
                                value = temperature,
                                style = textStyle,
                                color = textColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Mindestabstand zwischen dem Datum und dem Timer in der Mitte der Zeile. */
private val MIN_HEADER_GAP = 8.dp

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

@Preview(name = "AmbientStatusRow · oil timer running (blue)", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowOilTimerRunning() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = 18.4f, useImperial = false, oilGreenInMillis = 372_000L)
        }
    }
}

@Preview(name = "AmbientStatusRow · oil timer done (amber)", showBackground = true)
@Composable
private fun PreviewAmbientStatusRowOilTimerDone() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AmbientStatusRow(outsideTempC = null, useImperial = false, oilGreenInMillis = 0L)
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
