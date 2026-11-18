package de.adv990de.obd2.ui.tracks

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.TRACK_NAME_MAX
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.track.normalizeTrackName
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SwipeToDeleteRow
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.navigation.openSubPage
import de.adv990de.obd2.ui.navigation.rememberClosePage
import de.adv990de.obd2.ui.permission.appSettingsIntent
import de.adv990de.obd2.ui.permission.rememberLocationPermissionState
import de.adv990de.obd2.ui.settings.PermissionHintRow
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue

internal const val TRACKS_START_TEST_TAG = "tracks_start"
internal const val TRACKS_STOP_TEST_TAG = "tracks_stop"
internal const val TRACKS_RECORDING_CARD_TEST_TAG = "tracks_recording_card"
internal const val TRACKS_PERMISSION_HINT_TEST_TAG = "tracks_permission_hint"
internal const val TRACKS_RENAME_FIELD_TEST_TAG = "tracks_rename_field"
internal const val TRACKS_RENAME_SAVE_TEST_TAG = "tracks_rename_save"
internal fun tracksRowTestTag(id: Long) = "tracks_row_$id"
internal fun tracksRenameTestTag(id: Long) = "tracks_rename_$id"
internal fun tracksDeleteTestTag(id: Long) = "tracks_delete_$id"

/** Touch size of the row and button controls (CLAUDE.md design principle 4). */
private val ButtonSize = 56.dp

/** Die Liste der Tracks: oben Start oder die laufende Aufzeichnung, darunter die Tracks (neueste zuerst). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(navController: NavController, vm: TracksViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val undoBatchId by vm.undoBatchId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val permission = rememberLocationPermissionState()
    val start = rememberStartRecording(vm, permission)
    val context = LocalContext.current
    val back = rememberClosePage(navController)
    // Nur die Ids: eine Drehung baut die Seite neu auf, ein offener Dialog gehoert dazu.
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var stopConfirmation by rememberSaveable { mutableStateOf(false) }

    TracksMessageEffect(message, vm::consumeMessage, snackbarHostState)
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = stringResource(R.string.tracks_deleted),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoDelete,
    )
    if (stopConfirmation) {
        StopRecordingDialog(onConfirm = { stopConfirmation = false; vm.stopRecording() }, onDismiss = { stopConfirmation = false })
    }
    renameId?.let { id ->
        state.tracks?.firstOrNull { it.id == id }?.let { track ->
            RenameTrackDialog(initial = track.name, onSave = { vm.rename(id, it); renameId = null }, onDismiss = { renameId = null })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tracks_title)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.heightIn(min = ButtonSize)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        TracksContent(
            state = state,
            permissionMissing = !permission.fine,
            onStart = start,
            onStopRequest = { stopConfirmation = true },
            onOpen = { id -> navController.openSubPage(Screen.TrackDetail.routeFor(id)) },
            onRename = { renameId = it },
            onDelete = vm::delete,
            onOpenSettings = { openAppSettings(context) },
            modifier = Modifier.padding(padding),
        )
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(appSettingsIntent(context.packageName))
    } catch (_: android.content.ActivityNotFoundException) {
        // kein Einstellungsbildschirm erreichbar: es gibt nichts weiter zu tun
    }
}

/** Zustandslos fuer Vorschauen und Tests. */
@Composable
internal fun TracksContent(
    state: TracksUiState,
    permissionMissing: Boolean,
    onStart: () -> Unit,
    onStopRequest: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.recording
    val tracks = state.tracks
    Column(modifier.fillMaxSize()) {
        when (recording) {
            is RecordingState.Recording -> RecordingCard(recording, onStop = onStopRequest, onOpen = { onOpen(recording.trackId) })
            else -> {
                if (permissionMissing) {
                    PermissionHintRow(
                        text = stringResource(R.string.tracks_permission_hint),
                        buttonLabel = stringResource(R.string.settings_permission_open_settings),
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(top = 8.dp).testTag(TRACKS_PERMISSION_HINT_TEST_TAG),
                    )
                }
                Button(
                    onClick = onStart,
                    enabled = recording is RecordingState.Idle,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = ButtonSize).testTag(TRACKS_START_TEST_TAG),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.tracks_start), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tracks != null && tracks.isEmpty()) {
                if (recording !is RecordingState.Recording) EmptyState(Icons.Default.Place, stringResource(R.string.tracks_empty))
            } else if (tracks != null) {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        // Das Wischen ist nur zusaetzlich zum Papierkorb-Knopf (Lösch-Vertrag).
                        SwipeToDeleteRow(onDelete = { onDelete(track.id) }, modifier = Modifier.testTag(tracksRowTestTag(track.id))) {
                            TrackRow(track, onOpen = { onOpen(track.id) }, onRename = { onRename(track.id) }, onDelete = { onDelete(track.id) })
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Die laufende Aufzeichnung: Strecke und Punkte live, ein Hinweis ohne Signal oder bei Schreibfehler, „Beenden“ (mit Rueckfrage). */
@Composable
internal fun RecordingCard(recording: RecordingState.Recording, onStop: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag(TRACKS_RECORDING_CARD_TEST_TAG),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ein Punkt in der Warnfarbe: die Aufzeichnung laeuft (kein Blinken)
                androidx.compose.foundation.Canvas(Modifier.size(12.dp)) { drawCircle(StatusColors.Critical) }
                Text(stringResource(R.string.tracks_recording_title), style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveValueText(
                    formatTrackDistance(recording.distanceM.toLong(), locale),
                    style = MaterialTheme.typography.titleLarge.asLiveValue(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(pluralStringResource(R.plurals.tracks_points, recording.points, recording.points), style = MaterialTheme.typography.bodyMedium)
            }
            if (!recording.signal) Text(stringResource(R.string.tracks_recording_wait), style = MaterialTheme.typography.bodyMedium)
            if (recording.writeFailed) {
                Text(stringResource(R.string.tracks_recording_write_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onStop, modifier = Modifier.heightIn(min = ButtonSize).testTag(TRACKS_STOP_TEST_TAG)) { Text(stringResource(R.string.tracks_stop)) }
        }
    }
}

@Composable
internal fun TrackRow(track: TrackSummary, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val duration = if (track.startMs != null && track.endMs != null) track.endMs - track.startMs else null
    val whenText = formatDateTime(track.startMs ?: track.createdMs, locale)
    val numbers = buildString {
        append(formatTrackDistance(track.distanceM, locale))
        if (duration != null) append(" · ").append(formatTrackDuration(duration))
    }
    val source = stringResource(if (track.source == TrackSource.RECORDED) R.string.tracks_source_recorded else R.string.tracks_source_imported)
    ListItem(
        modifier = modifier.clickable(onClick = onOpen),
        headlineContent = { Text(track.name, maxLines = 1) },
        supportingContent = {
            Column {
                Text("$whenText · $source")
                Text(numbers)
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename, modifier = Modifier.size(ButtonSize).testTag(tracksRenameTestTag(track.id))) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.tracks_cd_rename))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(ButtonSize).testTag(tracksDeleteTestTag(track.id))) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tracks_cd_delete))
                }
            }
        },
    )
}

/** Umbenennen: ein Feld, „Speichern“ nur bei einem Namen mit Inhalt; die Taste „Fertig“ speichert ebenfalls. */
@Composable
internal fun RenameTrackDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = normalizeTrackName(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(TRACK_NAME_MAX) },
                label = { Text(stringResource(R.string.tracks_rename_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(text) }),
                modifier = Modifier.fillMaxWidth().testTag(TRACKS_RENAME_FIELD_TEST_TAG),
            )
        },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = { onSave(text) }, enabled = valid, modifier = Modifier.heightIn(min = ButtonSize).testTag(TRACKS_RENAME_SAVE_TEST_TAG)) {
                    Text(stringResource(R.string.tracks_save))
                }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ButtonSize)) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

// region — Previews

private fun previewTrack(id: Long, name: String, source: TrackSource = TrackSource.RECORDED) = TrackSummary(
    id = id, name = name, source = source, startMs = 1_800_000_000_000 + id * 86_400_000, endMs = 1_800_003_725_000 + id * 86_400_000, sessionId = null,
    distanceM = 12_345L * id, ascentM = null, descentM = null, pointCount = 900, createdMs = 1_800_000_000_000, running = false,
)

@Composable
private fun TracksListPreviewContent(recording: RecordingState) {
    TracksContent(
        state = TracksUiState(listOf(previewTrack(2, "Sonntagsrunde"), previewTrack(1, "2026-11-16 09:30", TrackSource.IMPORTED)), recording),
        permissionMissing = false, onStart = {}, onStopRequest = {}, onOpen = {}, onRename = {}, onDelete = {}, onOpenSettings = {},
    )
}

@Preview(name = "Tracks · idle · light", showBackground = true, heightDp = 520, widthDp = 420)
@Composable
private fun PreviewTracksIdleLight() = PreviewTheme(dark = false) { TracksListPreviewContent(RecordingState.Idle) }

@Preview(name = "Tracks · recording · dark", showBackground = true, heightDp = 520, widthDp = 420)
@Composable
private fun PreviewTracksRecordingDark() = PreviewTheme(dark = true) {
    TracksListPreviewContent(RecordingState.Recording(trackId = 3, points = 214, distanceM = 3_420.0, startedMs = 0, signal = true, writeFailed = false))
}

@Preview(name = "Tracks · empty · light", showBackground = true, heightDp = 360, widthDp = 420)
@Composable
private fun PreviewTracksEmptyLight() = PreviewTheme(dark = false) {
    TracksContent(TracksUiState(emptyList(), RecordingState.Idle), permissionMissing = true, onStart = {}, onStopRequest = {}, onOpen = {}, onRename = {}, onDelete = {}, onOpenSettings = {})
}

// endregion
