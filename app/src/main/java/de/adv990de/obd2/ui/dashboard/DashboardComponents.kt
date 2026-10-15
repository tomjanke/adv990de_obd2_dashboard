package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.FUEL_ROW_START_PADDING
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.GearIndicatorStyle
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Oberes Ende der Drehzahl-Skala; entspricht der maxValue-Angabe des RPM-PID. */
private const val RPM_BAR_MAX = 10_000f

/** Eine Stufe der Segmentanzeige. 200 rpm ergeben 50 Segmente ueber die volle Breite. */
private const val RPM_SEGMENT = 200f

@Composable
internal fun RpmBar(
    rpm: Float,
    threshold: Int,
    gear: Int?,
    modifier: Modifier = Modifier,
    /** Ohne Verbindung zeigt der Balken nur die Demo-Bewegung: kein Alarmrot, kein Blinken
     *  und keine Zahl, die eine Drehzahl vortaeuscht, die es nicht gibt. */
    isLive: Boolean = true,
    /** Simulierte Oel-Temperatur-Farbe (s. [OilTemperatureBarStrip]) — `null` zeigt die Leiste
     *  grau, genau wie `isLive = false`.
     *
     *  Steht bewusst hier, nach `isLive` und vor `trailingContent`, und nicht direkt nach `gear`:
     *  Ein Parameter mit Default zwischen `gear` und `modifier` machte `modifier` zum zweiten statt
     *  zum ersten optionalen Parameter und verletzte die Compose-Lint-Regel "Modifier parameter
     *  should be the first optional parameter" (bei der Einfuehrung gemessen: 31 statt 30
     *  Lint-Warnungen). `trailingContent` bleibt der letzte Parameter (Trailing-Lambda-Konvention).
     *  Alle Call-Sites nutzen benannte Argumente, die Position aendert fuer sie nichts. */
    oilBarColor: OilBarColor? = null,
    /** Fahrzeug steht und Motor laeuft: die Gang-Box zeigt ein gruenes "N" statt eines Gangs
     *  (s. [gearBoxContent] fuer die Praezedenz). Position wie [oilBarColor] aus Lint-Gruenden. */
    isNeutral: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val blinkPeriodMs: Long = rpmBlinkPeriodMs(rpm, threshold, isLive)

    // rememberUpdatedState lets the coroutine read the latest period on each iteration
    // without restarting the effect — crucial during animated RPM sweeps where blinkPeriodMs
    // changes every frame and a key-based restart would prevent the delay from ever firing.
    val blinkPeriodRef = rememberUpdatedState(blinkPeriodMs)
    // Der Effekt haengt nur an "blinkt ueberhaupt?", nicht an der Periode: sie aendert sich bei der Demo-Bewegung in jedem Frame,
    // ein Neustart je Aenderung liesse das delay nie ablaufen. Ohne Blinken laeuft gar keine Schleife — vorher weckte ein
    // `delay(50)` die Coroutine dauerhaft mit 20 Hz, um `value = true` zu wiederholen (UID-22).
    val blinking = blinkPeriodMs > 0L
    val visible by produceState(initialValue = true, blinking) {
        value = true
        if (!blinking) return@produceState
        while (true) {
            val period = blinkPeriodRef.value
            // Die Periode kann zwischen Komposition und Coroutine auf 0 gefallen sein; der Schluessel startet den Effekt gleich neu.
            if (period <= 0L) return@produceState
            delay(period)
            value = !value
        }
    }

    val isAlert = isLive && threshold > 0 && rpm > threshold
    val litColor = if (isAlert) StatusColors.Critical else MaterialTheme.colorScheme.primary
    // Segmente oberhalb des Schwellwerts sind schon vor dem Erreichen als Grenzbereich
    // erkennbar — wie die Schaltblitz-Skala eines Cockpit-Drehzahlmessers.
    val redlineColor = StatusColors.Critical
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val redlineFrom = if (isLive && threshold > 0) threshold.toFloat() else Float.MAX_VALUE

    Row(
        modifier = modifier
            // 78dp (zuvor 72dp): die Gang-Box daneben braucht seit 2026-09-24 (User-Wunsch,
            // Ganganzeige == ValueTileStyle) eine Innenhoehe von mind. 61,36dp
            // (ValueTileStyle.lineHeight bei fixiertem fontScale=1f) statt der zuvor 56dp.
            .height(78.dp)
            // Links kein Rand (Nutzerwunsch 2026-10-15): die Segmente stehen buendig am Bildschirmrand wie die Kachel. Rechts bleiben 8 dp
            // (dort stehen Gang-Box und Bluetooth-Symbol, kein Balken), oben und unten ebenfalls.
            .padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Column statt einzelnem Box: die RPM-Segmente (80% Hoehenanteil) und darunter die
        // Oel-Temperatur-Leiste (20%) teilen sich das `weight(1f)`-Feld links neben der
        // unveraenderten Gang-Box (Plan: docs/superpowers/archive/plans/2026-09-27-oil-temperature-bar.md).
        // Dazwischen 2dp Luecke, in der der Hintergrund durchscheint: ohne sie verschmelzen
        // Segmente und Leiste zu einer Flaeche, sobald beide gleich gefaerbt sind — grau auf grau
        // ohne Verbindung, rot auf rot bei kaltem Motor im Schaltblitz-Bereich.
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Der Segmentbalken war ein nacktes Canvas — fuer TalkBack unsichtbar (UID-13). Die Beschreibung nennt die Drehzahl
            // auf 100 gerundet, damit der Screenreader bei der 20-Hz-Demo-Bewegung nicht bei jedem Schritt spricht; ohne
            // Verbindung gibt es keine Drehzahl, die man ansagen koennte.
            val rpmDescription = if (isLive) {
                stringResource(R.string.dashboard_rpm_bar_desc, rpmForSpeech(rpm))
            } else {
                stringResource(R.string.dashboard_rpm_bar_unavailable_desc)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = rpmDescription }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
                // Segmentanzeige statt durchgehendem Balken: 50 Stufen à 200 rpm. Die Skala bleibt
                // auch bei 0 rpm sichtbar, sodass der Fahrer die Position der Drehzahl im Band
                // ablesen kann, ohne die Zahl zu lesen.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val count = (RPM_BAR_MAX / RPM_SEGMENT).toInt()
                    val gap = 1.5.dp.toPx()
                    val segmentWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                    val lit = if (visible) {
                        ceil(rpm / RPM_SEGMENT).toInt().coerceIn(0, count)
                    } else 0
                    for (i in 0 until count) {
                        val color = rpmSegmentColor(
                            segmentIndex = i,
                            litSegments = lit,
                            segmentStartRpm = i * RPM_SEGMENT,
                            redlineFromRpm = redlineFrom,
                            litColor = litColor,
                            redlineColor = redlineColor,
                            idleColor = idleColor,
                        )
                        drawRect(
                            color = color,
                            topLeft = Offset(i * (segmentWidth + gap), 0f),
                            size = Size(segmentWidth, size.height),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // Oel-Zeile (Nutzerwunsch 2026-10-15): die Oelkanne direkt vor dem Streifen, analog zum Tankstellen-Symbol der Tankzeile; der Streifen ist
            // so hoch wie der Tankbalken (16 dp). Die Segmente nehmen den Rest der Spalte (62 - 2 - 20 = 40 dp, vorher 48), die Zeilenhoehe von
            // 78 dp und die Gang-Box bleiben. Das Symbol ist neutral (onSurfaceVariant), nur der Streifen traegt die Farbe.
            Row(
                modifier = Modifier.fillMaxWidth().height(OIL_ROW_HEIGHT).padding(start = OIL_ROW_START_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oil_can),
                    contentDescription = null,
                    modifier = Modifier.height(OIL_ICON_HEIGHT).testTag(OIL_BAR_ICON_TEST_TAG),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(OIL_ICON_GAP))
                OilTemperatureBarStrip(
                    color = oilBarColor,
                    isLive = isLive,
                    modifier = Modifier.weight(1f).height(OIL_BAR_HEIGHT),
                )
            }
        }
        val gearBox = gearBoxContent(gear, isNeutral, isLive)
        val gearDescription = when (gearBox) {
            GearBoxContent.Neutral -> stringResource(R.string.dashboard_gear_neutral_desc)
            is GearBoxContent.Engaged -> stringResource(R.string.dashboard_gear_desc, gearBox.gear)
            GearBoxContent.Unknown -> stringResource(R.string.dashboard_gear_unknown_desc)
        }
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                // 40dp (zuvor 32dp): ValueTileStyle.fontSize (53,1sp bei fixiertem fontScale=1f)
                // ist breiter als die vorherige, auf 46sp zugeschnittene Box erlaubte.
                .width(40.dp)
                .fillMaxHeight()
                .semantics { contentDescription = gearDescription },
            contentAlignment = Alignment.Center,
        ) {
            // Diese Ziffer lebt in einer fest dimensionierten 32dp-Box (s. GearIndicatorStyle-KDoc)
            // — mit dem System-Schriftskalierungsfaktor mitzuwachsen wuerde sie darin abschneiden.
            // fontScale hier lokal auf 1f zu fixieren betrifft nur diese eine Ziffer, nicht den Rest
            // der App; der contentDescription oben bleibt unveraendert lesbar (TalkBack liest Text,
            // nicht Pixelgroesse).
            val fixedScaleDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(fixedScaleDensity.density, fontScale = 1f)) {
                LiveValueText(
                    value = when (gearBox) {
                        GearBoxContent.Neutral -> stringResource(R.string.dashboard_gear_neutral_symbol)
                        is GearBoxContent.Engaged -> gearBox.gear.toString()
                        GearBoxContent.Unknown -> "–"
                    },
                    style = GearIndicatorStyle,
                    color = gearBoxColor(
                        gearBox,
                        engagedColor = MaterialTheme.colorScheme.primary,
                        unknownColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
        trailingContent?.invoke()
    }
}

/** Test-only selector: die Oelkanne vor dem Oel-Temperatur-Streifen. */
internal const val OIL_BAR_ICON_TEST_TAG = "oil_bar_icon"

/**
 * Oel-Zeile unter den Drehzahl-Segmenten: 20 dp hoch (Entscheidung 1b des Nutzers: die Segmente werden dafuer von 48 auf 40 dp niedriger), darin
 * der Streifen 16 dp hoch (wie der Tankbalken) und die Oelkanne, ebenfalls 16 dp hoch (ihre Breite folgt dem Seitenverhaeltnis 24:15), beide zentriert.
 */
internal val OIL_ROW_HEIGHT = 20.dp
internal val OIL_ICON_HEIGHT = 16.dp
internal val OIL_BAR_HEIGHT = 16.dp
internal val OIL_ICON_GAP = 8.dp

/**
 * Linker Rand der Oel-Zeile: die Oelkanne steht an derselben **sichtbaren** Kante wie das Tankstellen-Symbol der Tankzeile (Nutzerwunsch 2026-10-15; seit der
 * Aenderung am selben Tag 12 dp vom Rand). Das Material-Symbol hat 4 dp Eigenrand, die selbstgezeichnete Kanne nur 1 dp (der Henkel beginnt bei x = 1 im 24er-Raster
 * von `ic_oil_can`) — der Rand ist also der der Tankzeile ([FUEL_ROW_START_PADDING]) **plus 3 dp**: gleiche Boxen laegen 3 dp daneben. Die Segmente darueber
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
) {
    val barColor = oilBarStripColor(color, isLive, idleColor = MaterialTheme.colorScheme.surfaceVariant)
    val description = stringResource(
        if (!isLive) R.string.oil_bar_unknown_desc else when (color) {
            null               -> R.string.oil_bar_unknown_desc
            OilBarColor.Red    -> R.string.oil_bar_red_desc
            OilBarColor.Yellow -> R.string.oil_bar_yellow_desc
            OilBarColor.Green  -> R.string.oil_bar_green_desc
        }
    )
    Box(
        modifier = modifier
            .background(barColor)
            .semantics { contentDescription = description }
    )
}

@Preview(name = "RpmBar · gear locked (blue)", showBackground = true)
@Composable
private fun PreviewRpmBarGearLocked() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Green, modifier = Modifier.fillMaxWidth())
                RpmBar(rpm = 4500f, threshold = 7000, gear = null, oilBarColor = OilBarColor.Yellow, modifier = Modifier.fillMaxWidth())
                RpmBar(rpm = 1100f, threshold = 7000, gear = null, isNeutral = true, oilBarColor = OilBarColor.Green, modifier = Modifier.fillMaxWidth())
                RpmBar(rpm = 0f, threshold = 7000, gear = null, oilBarColor = OilBarColor.Red, isLive = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview(name = "RpmBar · gear locked (amber)", showBackground = true)
@Composable
private fun PreviewRpmBarGearLockedAmber() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RpmBar(rpm = 6200f, threshold = 7000, gear = 5, oilBarColor = OilBarColor.Yellow, modifier = Modifier.fillMaxWidth())
                RpmBar(rpm = 1100f, threshold = 7000, gear = null, isNeutral = true, oilBarColor = OilBarColor.Green, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview(name = "RpmBar · neutral (default light)", showBackground = true)
@Composable
private fun PreviewRpmBarNeutralDefaultLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(16.dp)) {
                RpmBar(rpm = 1100f, threshold = 7000, gear = null, isNeutral = true, oilBarColor = OilBarColor.Green, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Alle Zustaende der [OilTemperatureBarStrip] untereinander, ungefaehr in Anzeige-Hoehe: Rot, Gelb,
 * Gruen, dann grau (noch keine Farbe bzw. ohne Verbindung). Die fehlende Vorschau war der Grund,
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
                Icon(painterResource(R.drawable.ic_oil_can), contentDescription = null, modifier = Modifier.size(width = 120.dp, height = 75.dp).padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Icon(painterResource(R.drawable.ic_oil_can), contentDescription = null, modifier = Modifier.size(width = 120.dp, height = 75.dp).padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
