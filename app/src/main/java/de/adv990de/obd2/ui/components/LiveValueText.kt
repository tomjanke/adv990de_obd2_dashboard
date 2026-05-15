package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.ValueDisplayStyle
import de.adv990de.obd2.ui.theme.ValueTileStyle

/**
 * Canonical text component for any value that updates at runtime (RPM, speed, temps, voltage, etc.).
 * Uses IBM Plex Mono with tabular numerals enforced so digits keep stable column widths across
 * updates — no horizontal jitter on the dashboard.
 *
 * Pick the style by visual hierarchy:
 *  - [ValueDisplayStyle] — dominant centerpiece (RPM hero, speed full-width)
 *  - [ValueTileStyle]   — standard tile reading (coolant, voltage, fuel)
 *
 * Static or non-numeric live text (status labels, mode indicators) should NOT use this composable —
 * use [Text] with the regular [MaterialTheme.typography] instead.
 */
@Composable
fun LiveValueText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ValueTileStyle,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign? = null,
) {
    Text(
        text = value,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
    )
}

// region — Previews

@Preview(name = "LiveValueText · tile (blue)", showBackground = false)
@Composable
private fun PreviewLiveValueTileBlue() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveValueText(value = "87")
                    Text(
                        text = " °C",
                        style = AppTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveValueText(value = "12.7")
                    Text(
                        text = " V",
                        style = AppTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveValueText(value = "1111", color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        text = " rpm",
                        style = AppTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(name = "LiveValueText · display hero", showBackground = false)
@Composable
private fun PreviewLiveValueDisplay() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "RPM",
                    style = AppTypography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiveValueText(
                    value = "2 840",
                    style = ValueDisplayStyle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(name = "LiveValueText · tabular alignment proof", showBackground = false)
@Composable
private fun PreviewTabularAlignment() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            // Right-aligned column of values demonstrating that each column of digits stays put
            // even when the leading digits change width — the whole point of tnum.
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1111", "2222", "3333", "1 111", "11 111").forEach { v ->
                    LiveValueText(value = v, textAlign = TextAlign.End, modifier = Modifier)
                }
            }
        }
    }
}

// endregion
