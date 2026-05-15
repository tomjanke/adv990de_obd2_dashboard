package de.adv990de.obd2.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * Semantic state of a status dot. Drives the rendered color and (for [Connecting]) the
 * pulsing animation. There is no explicit "disconnected" state — callers should simply
 * not render the dot when the underlying signal is absent rather than introduce a fifth
 * meaning.
 */
enum class StatusDotState {
    /** Healthy / connected / nominal. Solid [MaterialTheme.colorScheme.tertiary]. */
    Ok,
    /** Non-blocking caution. Solid [StatusColors.Warning]. */
    Warning,
    /** Actionable redline / fault. Solid [StatusColors.Critical]. */
    Critical,
    /** Pending state — handshake, reconnect loop. Pulses between 30% and 100% alpha
     *  on [MaterialTheme.colorScheme.primary] over 600 ms reversing. */
    Connecting,
}

/**
 * Small circular status indicator (default 8 dp), color and animation driven by [state].
 *
 * Intentionally compact and side-effect-free — drop into any Row/Box where a glanceable
 * health signal is needed (TopBar, list item trailing icon, dashboard chrome). For tile
 * accent coloring use [TileStatus] on a [LiveValueTile] instead, not this dot.
 */
@Composable
fun StatusDot(
    state: StatusDotState,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val baseColor = when (state) {
        StatusDotState.Ok         -> MaterialTheme.colorScheme.tertiary
        StatusDotState.Warning    -> StatusColors.Warning
        StatusDotState.Critical   -> StatusColors.Critical
        StatusDotState.Connecting -> MaterialTheme.colorScheme.primary
    }
    val alpha = if (state == StatusDotState.Connecting) {
        val infinite = rememberInfiniteTransition(label = "status-dot-pulse")
        val a by infinite.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        a
    } else 1f

    Box(
        modifier = modifier
            .size(size)
            .background(baseColor.copy(alpha = alpha), CircleShape),
    )
}

// region — Previews

@Preview(name = "StatusDot · all states", showBackground = false)
@Composable
private fun PreviewStatusDotStates() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDotState.entries.forEach { state ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .padding(8.dp)
                    ) {
                        StatusDot(state = state)
                    }
                }
            }
        }
    }
}

@Preview(name = "StatusDot · larger (16dp)", showBackground = false)
@Composable
private fun PreviewStatusDotLarger() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state = StatusDotState.Ok, size = 16.dp)
                StatusDot(state = StatusDotState.Warning, size = 16.dp)
                StatusDot(state = StatusDotState.Critical, size = 16.dp)
                StatusDot(state = StatusDotState.Connecting, size = 16.dp)
            }
        }
    }
}

// endregion
