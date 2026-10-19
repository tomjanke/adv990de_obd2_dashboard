package de.adv990de.obd2.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.ValueTileStyle
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * Kleinste Schriftgroesse des Kachelwerts (`ValueTileStyle` ~53 sp): Passt "11500" bei grosser Systemschrift nicht mehr
 * in die Kachel, schrumpft der Wert bis hierher (B27). 20 sp bleiben im Fahren lesbar; darunter waere "abgeschnitten"
 * ehrlicher als "winzig" — soweit kommt es bei einem 5-stelligen Wert auf einer 170-dp-Kachel nicht.
 */
internal val MIN_VALUE_FONT_SIZE = 20.sp

/** Innenrand jeder Kachel (rundum, bei [TileFrame] auch oben und unten neben einem [TileFrame] `gauge`). */
internal val TILE_INNER_PADDING = 12.dp

/** Breite des senkrechten Anzeigebalkens links in der Kachel — wie der Tankbalken (16 dp). */
internal val TILE_GAUGE_WIDTH = 16.dp

/** Abstand zwischen der 4-dp-Statusleiste und dem Anzeigebalken. */
internal val TILE_GAUGE_START_GAP = 8.dp

/**
 * Semantic state of a live tile. Drives the accent color used for the left side
 * bar and the value text. Sparkline color is always the theme primary (trend
 * vs. state distinction).
 *
 *  - [Ok]       → MaterialTheme.colorScheme.primary (theme-tinted; default)
 *  - [Warning]  → StatusColors.warningOn(surface) (bold yellow on dark surfaces, darker yellow on light ones)
 *  - [Critical] → StatusColors.Critical (cross-theme red)
 */
enum class TileStatus { Ok, Warning, Critical }

/**
 * Reine Farbauswahl fuer die Status-Side-Bar (und den Wert-Text) von [LiveValueTile]: bei
 * [isPreview] eine gedaempfte, statusfreie Farbe (s. KDoc zu `isPreview` dort), sonst die
 * Statusfarbe je [status]. Extrahiert, damit die Zuordnung ohne Compose-Rendering testbar ist
 * (Projekt-Konvention fuer Entscheidungslogik, vgl. `statusDotColor`, `connectionDotColor`) —
 * [onSurfaceVariant]/[primary] werden von der Composable hereingereicht, da sie nur innerhalb der
 * Komposition aus `MaterialTheme` lesbar sind, diese Funktion selbst aber keinen Compose-Bezug
 * haben soll.
 */
internal fun liveValueTileAccentColor(
    status: TileStatus,
    isPreview: Boolean,
    onSurfaceVariant: Color,
    primary: Color,
    // Fuer das Warn-Gelb: auf hellen Flaechen ein dunkleres, lesbares Gelb (B28, s. StatusColors.warningOn).
    surface: Color,
): Color = if (isPreview) {
    onSurfaceVariant.copy(alpha = 0.35f)
} else when (status) {
    TileStatus.Ok       -> primary
    TileStatus.Warning  -> StatusColors.warningOn(surface)
    TileStatus.Critical -> StatusColors.Critical
}

/**
 * Canonical dashboard tile for a single live readout. Anatomy:
 *
 * ```
 * ┌─┬─────────────────────────────┐
 * │ │ LABEL                       │
 * │ │                             │
 * │ │                             │
 * │ │  ╲╱╲╱──╲╱     2 840  rpm    │
 * └─┴─────────────────────────────┘
 *   ↑
 *   4dp accent side-bar (status-colored)
 * ```
 *
 * The tile fills its parent via `Modifier.fillMaxSize()` internally; the caller
 * controls grid cell size via the [modifier] parameter (e.g. inside a
 * `LazyVerticalGrid` cell).
 *
 * The tile is non-interactive by default. Passing [onClick] or [onLongPress]
 * wraps the surface in `combinedClickable`. Long-press is the conventional
 * "configure this slot" gesture on the dashboard.
 *
 * @param label Static label (typically the PID name; sans-serif, onSurfaceVariant).
 * @param value Live numeric or short string (rendered in IBM Plex Mono with tnum).
 * @param unit  Optional unit suffix shown next to the value in muted style.
 * @param sparkline Optional trend points; empty list hides the line.
 * @param status Drives accent color. Default [TileStatus.Ok].
 * @param isPreview Kennzeichnet eine Kachel ohne Verbindung: gedaempfte Akzentfarbe, keine
 *   Statusfarbe. Der Wert selbst kommt als "--" vom Aufrufer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveValueTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sparkline: List<Float> = emptyList(),
    status: TileStatus = TileStatus.Ok,
    isPreview: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /** Was der Long-Press tut, fuer Screenreader ("Messwert aendern") — ohne sie sagt TalkBack nur "Doppeltippen und
     *  halten". Gehoert zu [onLongPress]; die Kachel selbst kennt die Domaene nicht. */
    onLongPressLabel: String? = null,
    /** Optionaler senkrechter Anzeigebalken links vom Inhalt (Spannungs-Kachel, s. [TileFrame]); ohne ihn ist die Kachel unveraendert. */
    gauge: (@Composable () -> Unit)? = null,
) {
    // Im Vorschau-Zustand traegt die Kachel keine Statusfarbe: Eine gruene oder rote Akzentleiste
    // ueber einem Wert, den kein Fahrzeug geliefert hat, waere eine Aussage ohne Grundlage.
    val accentColor = liveValueTileAccentColor(
        status = status,
        isPreview = isPreview,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
        surface = MaterialTheme.colorScheme.surface,
    )
    TileFrame(
        label = label,
        accentColor = accentColor,
        modifier = modifier,
        onClick = onClick,
        onLongPress = onLongPress,
        onLongPressLabel = onLongPressLabel,
        gauge = gauge,
    ) {
        // Sparkline ueber dem Wert statt daneben oder dahinter: neben dem Wert blieben
        // bei vierstelligen Zahlen nur wenige dp uebrig, dahinter lief die Linie durch
        // die Ziffern. Als eigenes Band nutzt sie die volle Kachelbreite.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            if (sparkline.isNotEmpty()) {
                Sparkline(
                    points = sparkline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        // rechts Platz fuer den Punkt auf dem juengsten Wert
                        .padding(bottom = 8.dp, end = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // weight(fill = false): Die Einheit wird zuerst gemessen und behaelt ihren Platz, der Wert bekommt den
                // Rest und schrumpft (bis MIN_VALUE_FONT_SIZE), statt abgeschnitten zu werden (B27).
                LiveValueText(
                    value = value,
                    modifier = Modifier.weight(1f, fill = false),
                    style = ValueTileStyle,
                    color = accentColor,
                    minFontSize = MIN_VALUE_FONT_SIZE,
                )
                if (unit != null) {
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Der Rahmen jeder Dashboard-Kachel: Flaeche ohne Rundung und Schatten, links die 4 dp breite Status-Leiste in [accentColor], innen 12 dp Rand und oben links das
 * [label]. Was die Kachel zeigt, steht in [content] (ein `BoxScope` ueber die ganze Innenflaeche, die Beschriftung liegt darueber oben links). Tippen, Long-Press und
 * der Ripple in der Akzentfarbe gehoeren zum Rahmen — [LiveValueTile] (ein Wert) und `TerrainTile` (drei Werte) teilen sie, statt sie zu kopieren.
 *
 * Mit [gauge] steht zwischen der Statusleiste und dem Inhalt ein senkrechter Balken ([TILE_GAUGE_WIDTH] breit, [TILE_GAUGE_START_GAP] Abstand zur Statusleiste, oben und unten die 12 dp Innenrand
 * der Kachel); Beschriftung und Inhalt ruecken um Breite plus Abstand nach rechts. Die Statusleiste bleibt, wie sie ist (sie traegt den Kachelstatus). Ohne [gauge] ist der Rahmen unveraendert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TileFrame(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onLongPressLabel: String? = null,
    gauge: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactive = onClick != null || onLongPress != null
    // Ripple in der Akzentfarbe der Kachel statt des M3-Default-Grau: ein generischer grauer
    // Ripple wirkt neben der sonst durchgehend themenfarbigen Cockpit-Optik fremd (s. CLAUDE.md,
    // "Layout-Patterns", Tap). `MutableInteractionSource` explizit, weil `ripple()` als
    // `indication`-Parameter eine braucht, um Press-Events ueberhaupt zu empfangen.
    val finalModifier = if (interactive) {
        modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = accentColor),
            onClick = { onClick?.invoke() },
            onLongClick = { onLongPress?.invoke() },
            onLongClickLabel = onLongPressLabel,
        )
    } else modifier
    Surface(
        modifier = finalModifier,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )
            if (gauge != null) {
                Box(
                    Modifier
                        .padding(start = TILE_GAUGE_START_GAP, top = TILE_INNER_PADDING, bottom = TILE_INNER_PADDING)
                        .width(TILE_GAUGE_WIDTH)
                        .fillMaxHeight()
                ) { gauge() }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(TILE_INNER_PADDING)
            ) {
                Text(
                    // ALL-CAPS mit Letterspacing gemaess Design-Profil; die Grossschreibung
                    // passiert hier, damit die Quelle des Labels (PID-Name, Ressource) davon
                    // nichts wissen muss.
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.em),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                content()
            }
        }
    }
}

// region — Previews

@Preview(name = "LiveValueTile · Ok (blue)", widthDp = 200, heightDp = 140, showBackground = false)
@Composable
private fun PreviewTileOkBlue() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            LiveValueTile(
                label = "COOLANT",
                value = "87",
                unit = "°C",
                sparkline = listOf(78f, 80f, 82f, 85f, 87f, 86f, 87f, 88f, 87f),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "LiveValueTile · Warning (blue)", widthDp = 200, heightDp = 140, showBackground = false)
@Composable
private fun PreviewTileWarning() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            LiveValueTile(
                label = "OIL TEMP",
                value = "118",
                unit = "°C",
                status = TileStatus.Warning,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "LiveValueTile · Critical (blue)", widthDp = 200, heightDp = 140, showBackground = false)
@Composable
private fun PreviewTileCritical() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            LiveValueTile(
                label = "RPM",
                value = "9 100",
                unit = "rpm",
                status = TileStatus.Critical,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "LiveValueTile · amber theme", widthDp = 200, heightDp = 140, showBackground = false)
@Composable
private fun PreviewTileAmberTheme() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            LiveValueTile(
                label = "BATTERY",
                value = "12.7",
                unit = "V",
                sparkline = listOf(12.2f, 12.4f, 12.5f, 12.6f, 12.7f),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "LiveValueTile · grid 2×2", widthDp = 420, heightDp = 300, showBackground = false)
@Composable
private fun PreviewTileGrid() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveValueTile(label = "SPEED", value = "127", unit = "km/h", modifier = Modifier.weight(1f).height(140.dp))
                LiveValueTile(
                    label = "RPM", value = "2 840", unit = "rpm", modifier = Modifier.weight(1f).height(140.dp),
                    sparkline = listOf(2500f, 2700f, 2900f, 2840f, 2820f, 2840f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveValueTile(label = "COOLANT", value = "87", unit = "°C", modifier = Modifier.weight(1f).height(140.dp))
                LiveValueTile(
                    label = "LOAD", value = "42", unit = "%", modifier = Modifier.weight(1f).height(140.dp),
                    status = TileStatus.Warning,
                )
            }
        }
    }
}

// endregion
