package de.adv990de.obd2.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.ValueTileStyle

/**
 * Canonical text component for any value that updates at runtime (RPM, speed, temps, voltage, etc.).
 * Uses IBM Plex Mono with tabular numerals enforced so digits keep stable column widths across
 * updates — no horizontal jitter on the dashboard.
 *
 * Standard style for a tile reading (coolant, voltage, fuel): [ValueTileStyle]. Compact inline readouts promote an existing
 * style with `asLiveValue()`.
 *
 * Static or non-numeric live text (status labels, mode indicators) should NOT use this composable —
 * use [Text] with the regular [MaterialTheme.typography] instead.
 *
 * ## Animation
 *
 * When [animated] is `true`, value changes transition in via [AnimatedContent] with
 * `slideInVertically + fadeIn` over 180 ms `EaseOutCubic` (per CLAUDE.md spec). Use this only for
 * **slow-changing** values (freeze frame snapshots, connection status, VIN load) — high-frequency
 * dashboard readouts (RPM, speed updating at 5–10 Hz) animated this way produce visible motion
 * thrash because each transition gets interrupted by the next value. Default is `false`.
 */
@Composable
fun LiveValueText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ValueTileStyle,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
    animated: Boolean = false,
    /**
     * Kleinste Schriftgroesse, auf die der Wert schrumpfen darf, wenn er in die verfuegbare Breite sonst nicht passt
     * (B27). Unspecified (Standard) = nie schrumpfen, wie bisher. Die Kachel setzt sie, damit "11500" bei grosser
     * Systemschrift oder schmalem Display nicht still zu "1150" abgeschnitten wird: lieber kleiner als falsch.
     */
    minFontSize: TextUnit = TextUnit.Unspecified,
) {
    val autoSize = if (minFontSize.isSpecified && style.fontSize.isSpecified) {
        TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = style.fontSize)
    } else null
    if (animated) {
        AnimatedContent(
            targetState = value,
            modifier = modifier,
            transitionSpec = {
                val spec = tween<Float>(durationMillis = 180, easing = EaseOutCubic)
                val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 180, easing = EaseOutCubic)
                (slideInVertically(animationSpec = slideSpec) { it / 2 } + fadeIn(spec)) togetherWith
                    (slideOutVertically(animationSpec = slideSpec) { -it / 2 } + fadeOut(spec))
            },
            label = "live-value",
        ) { target ->
            Text(
                text = target,
                style = style,
                color = color,
                textAlign = textAlign,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = overflow,
                autoSize = autoSize,
            )
        }
    } else {
        Text(
            text = value,
            modifier = modifier,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow,
            autoSize = autoSize,
        )
    }
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

@Preview(name = "LiveValueText · tile value", showBackground = false)
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
                    style = ValueTileStyle,
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

@Preview(name = "LiveValueText · amber theme", showBackground = false)
@Composable
private fun PreviewLiveValueAmber() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveValueText(value = "98")
                    Text(
                        text = " °C",
                        style = AppTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveValueText(value = "4 200", style = ValueTileStyle)
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

// endregion
