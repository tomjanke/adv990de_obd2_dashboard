package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors

internal const val RECORDING_BUTTON_TEST_TAG = "recording_button"

/**
 * Der Knopf fuer die Streckenaufzeichnung im Dashboard (neben dem Bluetooth-Symbol): ein Ring zum Starten, ein **gefuellter roter Punkt**, solange aufgezeichnet wird — ein Tipp darauf
 * fragt vor dem Beenden zurueck ([onStopRequest]), damit ein versehentlicher Tipp mit Handschuhen nichts beendet. Waehrend des Anlegens ist er gesperrt.
 *
 * **Beim Nachfahren ([following])** ist er ein **gruener Kreis mit kleinem Play-Dreieck** (Nutzerwunsch 2026-11-22): er zeigt den Zustand, und ein Tipp oeffnet den Dialog des Nachfahrens
 * ([onFollowMenu]: zur Karte, beenden) — Aufzeichnen und Nachfahren laufen nicht zugleich. Nicht animiert (kein Blinken).
 */
@Composable
internal fun RecordingButton(
    recording: RecordingState,
    onStart: () -> Unit,
    onStopRequest: () -> Unit,
    modifier: Modifier = Modifier,
    following: Boolean = false,
    onFollowMenu: () -> Unit = {},
) {
    val running = recording is RecordingState.Recording
    val showFollowing = following && !running
    val description = stringResource(
        when {
            showFollowing -> R.string.dashboard_track_follow_cd
            running -> R.string.dashboard_track_stop_cd
            else -> R.string.dashboard_track_start_cd
        },
    )
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = when {
            showFollowing -> onFollowMenu
            running -> onStopRequest
            else -> onStart
        },
        enabled = recording !is RecordingState.Starting,
        modifier = modifier.size(56.dp).testTag(RECORDING_BUTTON_TEST_TAG).semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2 - 2.dp.toPx()
            when {
                showFollowing -> {
                    drawCircle(StatusColors.TrafficLightGreen, radius = r)
                    // Ein kleines Play-Dreieck, optisch mittig (der Schwerpunkt liegt etwas links von der Mitte der Huelle).
                    val c = center
                    val h = 5.dp.toPx()
                    val w = 8.dp.toPx()
                    val left = c.x - w * 0.4f
                    drawPath(
                        Path().apply {
                            moveTo(left, c.y - h)
                            lineTo(left + w, c.y)
                            lineTo(left, c.y + h)
                            close()
                        },
                        StatusColors.OnTrafficLightGreen,
                    )
                }
                running -> drawCircle(StatusColors.Critical, radius = r)
                else -> {
                    drawCircle(ring, radius = r, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(ring, radius = 3.dp.toPx(), center = Offset(center.x, center.y))
                }
            }
        }
    }
}

// region — Previews

@Preview(name = "RecordingButton · idle · light", showBackground = true)
@Composable
private fun PreviewRecordingButtonIdleLight() = PreviewTheme(dark = false) { RecordingButton(RecordingState.Idle, {}, {}) }

@Preview(name = "RecordingButton · recording · dark", showBackground = true)
@Composable
private fun PreviewRecordingButtonRecordingDark() = PreviewTheme(dark = true) {
    RecordingButton(RecordingState.Recording(1, 10, 100.0, 0, signal = true, writeFailed = false), {}, {})
}

@Preview(name = "RecordingButton · following · dark", showBackground = true)
@Composable
private fun PreviewRecordingButtonFollowingDark() = PreviewTheme(dark = true) { RecordingButton(RecordingState.Idle, {}, {}, following = true) }

@Preview(name = "RecordingButton · following · light", showBackground = true)
@Composable
private fun PreviewRecordingButtonFollowingLight() = PreviewTheme(dark = false) { RecordingButton(RecordingState.Idle, {}, {}, following = true) }

// endregion
