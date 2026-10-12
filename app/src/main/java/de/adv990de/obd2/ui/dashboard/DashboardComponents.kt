package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.LiveValueTile
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.components.TileStatus
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.GearIndicatorStyle
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Oberes Ende der Drehzahl-Skala; entspricht der maxValue-Angabe des RPM-PID. */
private const val RPM_BAR_MAX = 10_000f

/** Eine Stufe der Segmentanzeige. 200 rpm ergeben 50 Segmente ueber die volle Breite. */
private const val RPM_SEGMENT = 200f

/**
 * Blinkperiode des Schaltblitzes: 0 (kein Blinken) ausser wenn live verbunden, ein Schwellwert
 * gesetzt ist ([threshold] > 0) und die aktuelle Drehzahl ihn ueberschritten hat. Dann skaliert
 * die Periode linear von 500ms (an der Schwelle) auf 80ms (bei [RPM_BAR_MAX]) — je naeher am
 * Limit, desto hektischer der Blitz.
 *
 * Reine Funktion ohne Compose-Bezug, direkt JVM-testbar (Projekt-Konvention fuer Entscheidungslogik,
 * vgl. `determineGear`, `statusDotColor`). Aus [RpmBar] extrahiert, das sie pro Frame innerhalb der
 * Blink-Coroutine aufruft.
 */
internal fun rpmBlinkPeriodMs(rpm: Float, threshold: Int, isLive: Boolean): Long {
    if (!isLive || threshold <= 0 || rpm <= threshold) return 0L
    val f = if (threshold >= 10000) 1f
            else ((rpm - threshold) / (10000f - threshold)).coerceIn(0f, 1f)
    return (500f - 420f * f).toLong()  // 500ms at threshold → 80ms at max
}

/**
 * Farbe eines einzelnen Segments der [RpmBar]-Skala: unbeleuchtet ([idleColor]), wenn
 * [segmentIndex] >= [litSegments]; sonst beleuchtet — [redlineColor], wenn die Segmentposition
 * [segmentStartRpm] in der Redline-Zone liegt ([redlineFromRpm] oder darueber), sonst [litColor].
 * Die Redline-Zone-Pruefung greift nur fuer bereits beleuchtete Segmente — ein unbeleuchtetes
 * Segment jenseits der Schwelle bleibt [idleColor], nicht rot.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmBlinkPeriodMs], `statusDotColor`) — die Farben selbst
 * kommen als Parameter herein, weil sie nur innerhalb der Komposition aus `MaterialTheme` bzw.
 * `StatusColors` lesbar sind.
 */
internal fun rpmSegmentColor(
    segmentIndex: Int,
    litSegments: Int,
    segmentStartRpm: Float,
    redlineFromRpm: Float,
    litColor: Color,
    redlineColor: Color,
    idleColor: Color,
): Color = when {
    segmentIndex < litSegments && segmentStartRpm >= redlineFromRpm -> redlineColor
    segmentIndex < litSegments                                     -> litColor
    else                                                            -> idleColor
}

/**
 * Skalierungsfaktor fuer den RPM-Blink-Schwellwert nach Motortemperatur (vier Baender), wenn
 * dynamische Skalierung aktiv ist (`rpmDynamicThreshold`-Einstellung). Fehlt ein plausibler
 * Messwert ([engineTempC] = `null`) oder liegt er unter 50 °C, gilt der konservativste Faktor
 * 0.50 — je waermer der Motor, desto naeher am konfigurierten Schwellwert (1.00 ab 85 °C).
 *
 * Reine Funktion ohne Compose-Bezug, direkt JVM-testbar (Projekt-Konvention fuer Entscheidungslogik,
 * vgl. [rpmBlinkPeriodMs]/[rpmSegmentColor]). Aus `DashboardScreen` extrahiert, das den
 * Rueckgabewert mit dem konfigurierten `rpmBlinkThreshold` multipliziert.
 */
internal fun rpmThresholdTempScale(engineTempC: Float?): Float = when (engineTempC) {
    null                               -> 0.50f   // no plausible value → conservative fallback
    in Float.NEGATIVE_INFINITY..49.9f  -> 0.50f
    in 50f..69.9f                      -> 0.75f
    in 70f..84.9f                      -> 0.90f
    else                               -> 1.00f
}

/**
 * Ob die RPM-Demo-Sweep-Animation in `DashboardScreen` nach der 400ms-Anlaufverzoegerung starten
 * soll: nur, wenn bis dahin noch keine plausible echte Drehzahl eingetroffen ist. Die Frage "schon
 * gezeigt?" beantwortet diese Funktion NICHT — der Sweep laeuft einmal je App-Start, das entscheidet
 * `DashboardViewModel.consumeDemoSweepOffer()` (der Aufrufer verknuepft beide). Dieses Gate ist die
 * Entscheidung "Drehzahl schon da?" und damit der testbare Teil.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmThresholdTempScale]).
 */
internal fun shouldStartRpmDemoSweep(rpmValue: Float): Boolean = rpmValue < 1f

/**
 * Farbe der [OilTemperatureBarStrip]: [idleColor] (grau), solange keine Verbindung besteht oder
 * noch keine Farbe bestimmt ist; sonst eine feste Ampelfarbe je [OilBarColor] — Rot
 * [StatusColors.Critical], Gelb [StatusColors.Warning], Gruen [StatusColors.TrafficLightGreen].
 *
 * Gruen ist bewusst NICHT `colorScheme.tertiary` (so bis zum finalen Review des Features): das ist
 * nur im gruenen Cockpit-Schema tatsaechlich gruen — hellblau im blauen, amber im amber-farbenen
 * (dort kaum von Gelb zu unterscheiden) und mauve bzw. rosa im hellen/dunklen Default-Schema. Eine
 * Ampel muss in jedem Schema als Ampel lesbar sein.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmSegmentColor]) — [idleColor] kommt als Parameter
 * herein, weil `surfaceVariant` nur innerhalb der Komposition aus `MaterialTheme` lesbar ist.
 */
internal fun oilBarStripColor(color: OilBarColor?, isLive: Boolean, idleColor: Color): Color =
    if (!isLive) idleColor else when (color) {
        null               -> idleColor
        OilBarColor.Red    -> StatusColors.Critical
        OilBarColor.Yellow -> StatusColors.Warning
        OilBarColor.Green  -> StatusColors.TrafficLightGreen
    }

/** Was die Gang-Box neben dem RPM-Balken zeigt: ein Gang, Leerlauf ("N") oder "–". */
internal sealed interface GearBoxContent {
    data object Neutral : GearBoxContent
    data class Engaged(val gear: Int) : GearBoxContent
    data object Unknown : GearBoxContent
}

/**
 * Praezedenz der Gang-Box: ohne Verbindung immer [GearBoxContent.Unknown]; sonst gewinnt "N" vor
 * einem noch gesetzten Gang (die ECU meldet den Stillstand sofort, die GPS-Uebersetzung hinkt nach).
 */
internal fun gearBoxContent(gear: Int?, isNeutral: Boolean, isLive: Boolean): GearBoxContent = when {
    !isLive -> GearBoxContent.Unknown
    isNeutral -> GearBoxContent.Neutral
    gear != null -> GearBoxContent.Engaged(gear)
    else -> GearBoxContent.Unknown
}

/**
 * "N" ist wie die Leerlauf-Kontrollleuchte am Motorrad in jedem Farbschema das feste Ampel-Gruen
 * (dritte dokumentierte Gruen-Ausnahme, s. CLAUDE.md); Gang und Platzhalter nehmen Theme-Farben.
 */
internal fun gearBoxColor(content: GearBoxContent, engagedColor: Color, unknownColor: Color): Color =
    when (content) {
        GearBoxContent.Neutral -> StatusColors.TrafficLightGreen
        is GearBoxContent.Engaged -> engagedColor
        GearBoxContent.Unknown -> unknownColor
    }

/** Die Drehzahl, wie der Screenreader sie ansagt: auf 100 gerundet und nie negativ. */
internal fun rpmForSpeech(rpm: Float): Int =
    if (rpm.isNaN() || rpm.isInfinite() || rpm <= 0f) 0 else (Math.round(rpm / 100f) * 100)

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
    val visible by produceState(initialValue = true) {
        while (true) {
            val period = blinkPeriodRef.value
            if (period > 0L) {
                delay(period)
                value = !value
            } else {
                value = true
                delay(50) // idle poll — keeps loop alive without busy-spinning
            }
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
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Column statt einzelnem Box: die RPM-Segmente (80% Hoehenanteil) und darunter die
        // Oel-Temperatur-Leiste (20%) teilen sich das `weight(1f)`-Feld links neben der
        // unveraenderten Gang-Box (Plan: docs/superpowers/plans/2026-09-27-oil-temperature-bar.md).
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
            Box(modifier = Modifier.weight(0.8f).fillMaxWidth().semantics { contentDescription = rpmDescription }) {
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
            OilTemperatureBarStrip(
                color = oilBarColor,
                isLive = isLive,
                modifier = Modifier.weight(0.2f).fillMaxWidth(),
            )
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

/**
 * Simulierte "wahrscheinliche Oeltemperatur", abgeleitet aus der Kuehlmitteltemperatur (kein
 * echter Oelsensor) — s. `oilBarColor()`/`updateOilBarTimer()` in `OilTemperatureBar.kt` fuer die
 * Farblogik, [oilBarStripColor] fuer die Zuordnung zu Farben. Bewusst ohne Beschriftung
 * (User-Wunsch). Farbsemantik ist hier invertiert gegenueber dem Rest der App (Rot = zu kalt, nicht
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SensorCardSlot(
    reading: SensorValue?,
    sparklines: Map<String, List<Float>>,
    useImperial: Boolean,
    modifier: Modifier = Modifier,
    /** Text fuer den Fall ohne Messwert — siehe [slotPlaceholderRes]. */
    @StringRes placeholderRes: Int = R.string.dashboard_slot_empty,
    /** false zeichnet die Kachel als Vorschau: "--" statt einer Zahl, keine Statusfarbe. */
    isLive: Boolean = true,
    onLongPress: (() -> Unit)? = null
) {
    if (reading != null) {
        SensorCard(
            value = reading,
            modifier = modifier.fillMaxSize(),
            sparkline = sparklines[reading.pid.id] ?: emptyList(),
            useImperial = useImperial,
            onLongPress = onLongPress,
            isLive = isLive,
        )
    } else {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress?.invoke() },
                    onLongClickLabel = stringResource(R.string.dashboard_slot_change_action),
                ),
            shape = RectangleShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            // Ein unbelegter Slot war bisher eine leere Flaeche und sah nach Renderfehler aus.
            // Der Hinweis nennt zugleich die einzige Geste, die ihn belegt.
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(placeholderRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Schmales Band ueber dem Kachelraster, solange keine Verbindung besteht.
 *
 * Bis 1.11.0 zeigte das Dashboard ohne Verbindung Kacheln mit dem Wert 0 — optisch nicht von
 * echten Messwerten zu unterscheiden. Genau diese Verwechslung stand am Anfang der Fehlersuche
 * nach der GPS-Geschwindigkeit: Der Eindruck, die App zeige eine Geschwindigkeit, kam von der
 * Vorschau, nicht vom Fahrzeug.
 *
 * Die Vorschau bleibt — sie zeigt die Slot-Belegung und macht das Layout begreifbar — aber sie
 * sagt jetzt, was sie ist.
 *
 * [labelRes] ist normalerweise "Vorschau · nicht verbunden"; bei veralteten Werten ([DashboardUiState.isStale])
 * nutzt dasselbe Band "Keine aktuellen Werte" — die Verbindung steht, das Steuergeraet schweigt. Waehrend einer
 * Wiederverbindung nennt es den Versuch ([previewBannerLabel], [labelArgs] = Nummer und Gesamtzahl).
 *
 * [reason] (siehe [previewBannerReason]) schreibt aus, WARUM nicht verbunden ist; ohne Grund bleibt es
 * das schlichte Band. Der Grund steht in der Fehlerfarbe des Themes (theme-sicherer Kontrast, wie die
 * uebrigen Leerzustands-Fehlertexte) in lesbarer Schriftgroesse, nicht in der ALL-CAPS-Beschriftung.
 */
@Composable
internal fun PreviewBanner(
    modifier: Modifier = Modifier,
    reason: UiText? = null,
    @StringRes labelRes: Int = R.string.dashboard_preview_banner,
    labelArgs: List<Any> = emptyList(),
) {
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(
                text = stringResource(labelRes, *labelArgs.toTypedArray()),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (reason != null) {
                Text(
                    text = reason.asString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Adapter from the domain type [SensorValue] to the generic [LiveValueTile]:
 * handles unit conversion (imperial flag), NaN/Inf → "--", voltage gets one
 * decimal place, everything else integer. The accessibility contentDescription
 * stays here because it depends on the PID semantics.
 */
@Composable
internal fun SensorCard(
    value: SensorValue,
    modifier: Modifier = Modifier,
    sparkline: List<Float> = emptyList(),
    useImperial: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    /**
     * false bedeutet: Es gibt keine Verbindung, der Wert stammt aus der Vorschau-Belegung und ist
     * kein Messwert. Er wird dann als "--" gezeichnet — eine "0" liesse sich von einem echten
     * Messwert nicht unterscheiden, und genau diese Verwechslung hat schon eine Fehlersuche
     * ausgeloest.
     */
    isLive: Boolean = true,
) {
    val (convertedValue, displayUnit) = if (value.value.isNaN() || value.value.isInfinite()) {
        value.value to value.pid.unit
    } else {
        convertUnit(value.value, value.pid.unit, useImperial)
    }
    val displayValue = when {
        !isLive -> "--"
        value.value.isNaN() || value.value.isInfinite() -> "--"
        else -> formatSensorNumber(convertedValue, value.pid.unit)
    }
    val pidLabel = stringResource(value.pid.labelRes)
    LiveValueTile(
        label = pidLabel,
        value = displayValue,
        unit = displayUnit,
        // Ohne Verbindung gibt es auch keinen Verlauf zu zeigen.
        sparkline = if (isLive) sparkline else emptyList(),
        status = if (isLive) pidTileStatus(value.pid.id, value.value) else TileStatus.Ok,
        isPreview = !isLive,
        onLongPress = onLongPress,
        // Das Neubelegen per Long-Press ist die einzige Geste dafuer — TalkBack soll sie beim Namen nennen (UID-13).
        onLongPressLabel = stringResource(R.string.dashboard_slot_change_action),
        modifier = modifier.semantics { contentDescription = "$pidLabel: $displayValue $displayUnit" },
    )
}

/** Test-only selector for [SlotPickerSheet]'s [LazyColumn], so instrumentation tests can
 *  `performScrollToNode` to PID rows below the initially-composed viewport (confirmed empirically
 *  2026-09-29: only a handful of rows compose before the first frame settles on a Pixel 8 Pro,
 *  same root cause as `LOGGING_CONFIG_LIST_TEST_TAG`). */
internal const val SLOT_PICKER_LIST_TEST_TAG = "slot_picker_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlotPickerSheet(
    currentPidId: String,
    availablePids: List<PidDefinition>,
    readings: List<SensorValue>,
    useImperial: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Volle Hoehe statt halb: im Querformat zeigte das halbhohe Sheet anfangs nur ~3 Zeilen.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Nach dem angezeigten (uebersetzten) Namen geordnet, nicht nach dem englischen (UID-15).
    val labels = availablePids.associate { it.id to stringResource(it.labelRes) }
    val ordered = remember(availablePids, labels) { sortByDisplayLabel(availablePids, { labels.getValue(it.id) }) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // Das Sheet ist ein eigenes Fenster mit eigener ViewConfiguration: die 56-dp-Regel aus AppTheme gilt nur,
        // wenn sie hier erneut gesetzt wird (s. ProvideMinTouchTarget).
        ProvideMinTouchTarget {
            LazyColumn(Modifier.testTag(SLOT_PICKER_LIST_TEST_TAG)) {
                items(ordered, key = { it.id }) { pid ->
                    val reading = readings.firstOrNull { it.pid.id == pid.id }
                    val label = if (reading == null || reading.value.isNaN() || reading.value.isInfinite()) {
                        "${labels.getValue(pid.id)}: -- (${pid.unit})"
                    } else {
                        val (convertedValue, displayUnit) = convertUnit(reading.value, pid.unit, useImperial)
                        val formatted = formatSensorNumber(convertedValue, pid.unit)
                        "${labels.getValue(pid.id)}: $formatted ($displayUnit)"
                    }
                    ListItem(
                        modifier = Modifier.clickable { onSelect(pid.id) },
                        headlineContent = { Text(label) },
                        trailingContent = if (pid.id == currentPidId) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null
                    )
                    HorizontalDivider()
                }
            }
        }
    }
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
            OilTemperatureBarStrip(color = color, isLive = true, modifier = Modifier.fillMaxWidth().height(12.dp))
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
