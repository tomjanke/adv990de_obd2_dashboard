package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * fragt vor dem Beenden zurueck ([onStopRequest]), damit ein versehentlicher Tipp mit Handschuhen nichts beendet. Waehrend des Anlegens ist er gesperrt. Nicht animiert (kein Blinken).
 */
@Composable
internal fun RecordingButton(recording: RecordingState, onStart: () -> Unit, onStopRequest: () -> Unit, modifier: Modifier = Modifier) {
    val running = recording is RecordingState.Recording
    val description = stringResource(if (running) R.string.dashboard_track_stop_cd else R.string.dashboard_track_start_cd)
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = if (running) onStopRequest else onStart,
        enabled = recording !is RecordingState.Starting,
        modifier = modifier.size(56.dp).testTag(RECORDING_BUTTON_TEST_TAG).semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(24.dp)) {
            if (running) {
                drawCircle(StatusColors.Critical, radius = size.minDimension / 2 - 2.dp.toPx())
            } else {
                drawCircle(ring, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                drawCircle(ring, radius = 3.dp.toPx())
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

// endregion
