package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

internal const val FOLLOW_MENU_OPEN_MAP_TEST_TAG = "follow_menu_open_map"
internal const val FOLLOW_MENU_STOP_TEST_TAG = "follow_menu_stop"
internal const val FOLLOW_REPLACE_CONFIRM_TEST_TAG = "follow_replace_confirm"
internal const val FOLLOW_REPLACE_CANCEL_TEST_TAG = "follow_replace_cancel"
internal const val FOLLOW_RECORDING_OK_TEST_TAG = "follow_recording_ok"

/**
 * Der Dialog hinter dem gruenen Knopf des Dashboards: **zur Karte** oder **Nachfahren beenden**. Ein Tipp daneben schliesst ihn, ohne etwas zu tun. Beide Knoepfe sind mindestens 56 dp hoch
 * (der Dialog ist ein eigenes Fenster und braucht die Regel selbst).
 */
@Composable
internal fun FollowMenuDialog(info: FollowInfo, onOpenMap: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (info.name != null) stringResource(R.string.follow_menu_title, info.name) else stringResource(R.string.follow_menu_title_unnamed))
        },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onOpenMap, modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_MENU_OPEN_MAP_TEST_TAG)) { Text(stringResource(R.string.follow_menu_open_map)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_MENU_STOP_TEST_TAG)) { Text(stringResource(R.string.track_follow_stop)) }
            }
        },
    )
}

/** Es wird schon einem anderen Track gefolgt: erst nach der Bestaetigung wechselt die Sitzung. [current] ist der Name des laufenden Tracks. */
@Composable
internal fun FollowReplaceDialog(current: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_follow_replace_title)) },
        text = { Text(stringResource(R.string.track_follow_replace_text, current ?: stringResource(R.string.follow_menu_title_unnamed))) },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_REPLACE_CONFIRM_TEST_TAG)) { Text(stringResource(R.string.track_follow_replace_confirm)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_REPLACE_CANCEL_TEST_TAG)) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Eine Aufzeichnung laeuft: dann laesst sich kein Track nachfahren (Aufzeichnen und Nachfahren laufen nicht zugleich). */
@Composable
internal fun FollowRecordingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_follow_recording_title)) },
        text = { Text(stringResource(R.string.track_follow_recording_text)) },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_RECORDING_OK_TEST_TAG)) { Text(stringResource(R.string.track_follow_dialog_ok)) }
            }
        },
    )
}

// region — Previews

@Preview(name = "FollowMenuDialog · light", showBackground = true)
@Composable
private fun PreviewFollowMenuDialogLight() = PreviewTheme(dark = false) { FollowMenuDialog(FollowInfo(1, "Sonntagsrunde"), {}, {}, {}) }

@Preview(name = "FollowMenuDialog · dark", showBackground = true)
@Composable
private fun PreviewFollowMenuDialogDark() = PreviewTheme(dark = true) { FollowMenuDialog(FollowInfo(1, "Sonntagsrunde"), {}, {}, {}) }

@Preview(name = "FollowReplaceDialog · dark", showBackground = true)
@Composable
private fun PreviewFollowReplaceDialogDark() = PreviewTheme(dark = true) { FollowReplaceDialog("Sonntagsrunde", {}, {}) }

@Preview(name = "FollowRecordingDialog · light", showBackground = true)
@Composable
private fun PreviewFollowRecordingDialogLight() = PreviewTheme(dark = false) { FollowRecordingDialog({}) }

// endregion
