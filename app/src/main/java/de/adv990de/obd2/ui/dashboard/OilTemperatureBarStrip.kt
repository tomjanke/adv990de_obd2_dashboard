package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.FUEL_ROW_START_PADDING
import de.adv990de.obd2.ui.components.formatOilTimer
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Test-only selector: die Oelkanne vor dem Oel-Temperatur-Streifen. */
internal const val OIL_BAR_ICON_TEST_TAG = "oil_bar_icon"

/** Test-only selector: der Oel-Streifen (Farbe, gruener Anteil der Timerphase, Beschreibung). */
internal const val OIL_BAR_STRIP_TEST_TAG = "oil_bar_strip"

/**
 * Oel-Zeile unter den Drehzahl-Segmenten: 20 dp hoch (Entscheidung 1b des Nutzers: die Segmente werden dafuer von 48 auf 40 dp niedriger), darin
 * der Streifen 16 dp hoch (wie der Tankbalken) und die Oelkanne, ebenfalls 16 dp hoch (die Zeichnung fuellt ihre Box: 40,5 dp breit, seit 2026-10-16 als Kontur; vorher 24 dp), beide **unten buendig**
 * (vorher zentriert): zwischen Segmenten und Streifen liegen damit 2 dp Luecke + 4 dp Luft der Zeile = 6 dp (Nutzerwunsch 2026-10-15; vorher 4 dp).
 */
internal val OIL_ROW_HEIGHT = 20.dp
internal val OIL_ICON_HEIGHT = 16.dp
internal val OIL_BAR_HEIGHT = 16.dp
internal val OIL_ICON_GAP = 8.dp

/**
 * Linker Rand der Oel-Zeile: die Oelkanne steht an derselben **sichtbaren** Kante wie das Tankstellen-Symbol der Tankzeile (Nutzerwunsch 2026-10-15; seit der
 * Aenderung am selben Tag 12 dp vom Rand). Das Material-Symbol hat 4 dp Eigenrand, die selbstgezeichnete Kanne nur 1 dp (die Zeichnung beginnt 2,31 Einheiten = 1 dp vom Rand
 * der Box, s. `ic_oil_can`) — der Rand ist also der der Tankzeile ([FUEL_ROW_START_PADDING]) **plus 3 dp**: gleiche Boxen laegen 3 dp daneben. Die Segmente darueber
 * stehen bewusst buendig (0 dp), nur die Zeile mit dem Symbol ist eingerueckt; der Streifen endet weiter an der rechten Kante der Segmente.
 * `DashboardLeftEdgeAlignmentDeviceTest` misst die sichtbaren Kanten gegen die echte Tankzeile.
 */
internal val OIL_ROW_START_PADDING = FUEL_ROW_START_PADDING + 3.dp

/**
 * Simulierte "wahrscheinliche Oeltemperatur", abgeleitet aus der Kuehlmitteltemperatur (kein
 * echter Oelsensor) — s. `oilBarColor()`/`updateOilBarTimer()` in `OilTemperatureBar.kt` fuer die
 * Farblogik, [oilBarStripColor] fuer die Zuordnung zu Farben. Bewusst ohne Textbeschriftung
 * (User-Wunsch); die Oelkanne davor (in `RpmBar`) macht klar, was der Streifen ist. Farbsemantik ist hier invertiert gegenueber dem Rest der App (Rot = zu kalt, nicht
 * gefaehrlich/heiss) — dokumentierte, User-bestaetigte Ausnahme, s. CLAUDE.md.
 */
@Composable
internal fun OilTemperatureBarStrip(
    color: OilBarColor?,
    isLive: Boolean,
    modifier: Modifier = Modifier,
    /** Restzeit bis Gruen (s. [oilBarGreenShare]); die volle Dauer = Timer laeuft nicht. Steht nach `modifier`: der muss der erste optionale Parameter sein (Lint `ModifierParameter`). */
    greenInMillis: Long = OIL_BAR_GREEN_HOLD_MS,
) {
    val barColor = oilBarStripColor(color, isLive, idleColor = MaterialTheme.colorScheme.surfaceVariant)
    // Waehrend der Timerphase faerbt sich der gelbe Streifen anteilig von links nach rechts gruen (Nutzerwunsch 2026-10-16) — nur, solange der Timer
    // wirklich laeuft (live, gelb, Restzeit unter der vollen Dauer), sonst ist der Anteil 0.
    val greenShare = oilBarGreenShare(color, isLive, greenInMillis)
    val locale = LocalLocale.current.platformLocale
    val description = if (greenShare > 0f) {
        stringResource(R.string.oil_bar_warmup_desc, formatOilTimer(greenInMillis, locale))
    } else {
        stringResource(
            if (!isLive) R.string.oil_bar_unknown_desc else when (color) {
                null               -> R.string.oil_bar_unknown_desc
                OilBarColor.Red    -> R.string.oil_bar_red_desc
                OilBarColor.Yellow -> R.string.oil_bar_yellow_desc
                OilBarColor.Green  -> R.string.oil_bar_green_desc
            }
        )
    }
    Box(
        modifier = modifier
            .background(barColor)
            .semantics { contentDescription = description }
    ) {
        // Das Gruen liegt ueber dem deckenden Gelb: der Rand ist eine reine Mischung aus beiden, kein Spalt dazwischen.
        if (greenShare > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(greenShare)
                    .background(StatusColors.TrafficLightGreen)
            )
        }
    }
}

/**
 * Alle Zustaende der [OilTemperatureBarStrip] untereinander, ungefaehr in Anzeige-Hoehe: Rot, Gelb,
 * Gruen, dann grau (noch keine Farbe bzw. ohne Verbindung), darunter die Timerphase als Farbwechsel von Gelb
 * nach Gruen bei 25, 50 und 75 % der Zeit. Die fehlende Vorschau war der Grund,
 * warum das theme-abhaengige "Gruen" (hellblau/amber/mauve, bis zum finalen Review) unbemerkt
 * blieb — deshalb je ein dunkles Cockpit-Schema und das helle Default-Schema.
 */
@Composable
private fun OilTemperatureBarStripStates() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (OilBarColor.entries + null).forEach { color ->
            OilTemperatureBarStrip(color = color, isLive = true, modifier = Modifier.fillMaxWidth().height(OIL_BAR_HEIGHT))
        }
        listOf(0.25f, 0.5f, 0.75f).forEach { elapsed ->
            OilTemperatureBarStrip(
                color = OilBarColor.Yellow,
                isLive = true,
                greenInMillis = (OIL_BAR_GREEN_HOLD_MS * (1f - elapsed)).toLong(),
                modifier = Modifier.fillMaxWidth().height(OIL_BAR_HEIGHT),
            )
        }
    }
}

@Preview(name = "OilTemperatureBarStrip · states (blue)", widthDp = 320, showBackground = true)
@Composable
private fun PreviewOilTemperatureBarStripBlue() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { OilTemperatureBarStripStates() }
    }
}

@Preview(name = "OilTemperatureBarStrip · states (amber)", widthDp = 320, showBackground = true)
@Composable
private fun PreviewOilTemperatureBarStripAmber() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { OilTemperatureBarStripStates() }
    }
}

@Preview(name = "OilTemperatureBarStrip · states (default light)", widthDp = 320, showBackground = true)
@Composable
private fun PreviewOilTemperatureBarStripDefaultLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { OilTemperatureBarStripStates() }
    }
}

@Preview(name = "Oelkanne (ic_oil_can) · gross, hell und dunkel", widthDp = 320, showBackground = true)
@Composable
private fun PreviewOilCanIcon() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
        MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Icon(painterResource(R.drawable.ic_oil_can), contentDescription = null, modifier = Modifier.size(width = 190.dp, height = 77.dp).padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Icon(painterResource(R.drawable.ic_oil_can), contentDescription = null, modifier = Modifier.size(width = 190.dp, height = 77.dp).padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
