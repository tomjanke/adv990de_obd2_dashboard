package de.adv990de.obd2.ui.tracks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.permission.LocationPermissionState
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Der Text einer Rueckmeldung des Aufzeichnens ([TracksMessage]). */
@Composable
internal fun tracksMessageText(message: TracksMessage): String = stringResource(
    when (message) {
        TracksMessage.NoPermission -> R.string.tracks_msg_no_permission
        TracksMessage.NoLocationService -> R.string.tracks_msg_no_location_service
        TracksMessage.StartFailed -> R.string.tracks_msg_start_failed
        TracksMessage.StopFailed -> R.string.tracks_msg_stop_failed
        TracksMessage.RenameFailed -> R.string.tracks_msg_rename_failed
    },
)

/** Zeigt eine Rueckmeldung einmal als Snackbar und gibt sie dann frei ([onConsumed]). Kein Text mit einer Position: die Meldungen nennen nur, was schiefging. */
@Composable
internal fun TracksMessageEffect(message: TracksMessage?, onConsumed: () -> Unit, snackbarHostState: SnackbarHostState) {
    val text = message?.let { tracksMessageText(it) }
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(message) {
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            currentOnConsumed()
        }
    }
}

/**
 * Die Aktion „Aufzeichnung starten“ fuer die Liste und das Dashboard: mit dem genauen Standort startet sie sofort, sonst fragt sie danach (der Dialog des Systems) und startet,
 * wenn er erteilt wurde. Wurde er abgelehnt, bleibt es bei der Hinweiszeile der Seite (sie fuehrt in die App-Einstellungen).
 */
@Composable
internal fun rememberStartRecording(vm: TracksViewModel, permission: LocationPermissionState): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission.refresh()
        if (permission.fine) vm.startRecording(null)
    }
    return remember(vm, permission, launcher) {
        {
            if (permission.fine) {
                vm.startRecording(null)
            } else {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
}

/** Die Rueckfrage vor dem Beenden einer Aufzeichnung (ein versehentlicher Tipp mit Handschuhen soll sie nicht beenden). Ein Dialog, kein Bottom Sheet. */
@Composable
internal fun StopRecordingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_stop_title)) },
        text = { Text(stringResource(R.string.tracks_stop_text)) },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 56.dp).testTag(TRACKS_STOP_CONFIRM_TEST_TAG)) { Text(stringResource(R.string.tracks_stop_confirm)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

internal const val TRACKS_STOP_CONFIRM_TEST_TAG = "tracks_stop_confirm"
