package de.adv990de.obd2.ui.tracks

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.map.MapTiles
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.ExportFeedbackEffects
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.navigation.openSubPage
import de.adv990de.obd2.ui.navigation.rememberClosePage
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue
import kotlinx.coroutines.launch

/** Test-only selectors of the export buttons and the note under the numbers. */
internal const val TRACK_EXPORT_SAVE_TEST_TAG = "track_export_save"
internal const val TRACK_EXPORT_SHARE_TEST_TAG = "track_export_share"
internal const val TRACK_EXPORT_NOTE_TEST_TAG = "track_export_note"
internal const val TRACK_FOLLOW_OPEN_TEST_TAG = "track_follow_open"

/** The MIME type of the file dialog for "save as GPX". */
private const val GPX_MIME = "application/gpx+xml"

/** Die Ansicht eines Tracks (Route `track_detail/{id}`): Zeichnung ohne Karte, darunter oder daneben die Kennzahlen; in der Kopfleiste „als GPX speichern“ und „als GPX teilen“. */
@Composable
fun TrackDetailScreen(
    navController: NavController,
    vm: TrackDetailViewModel = hiltViewModel(),
    followVm: FollowStatusViewModel = hiltViewModel(),
    mapVm: MapLayerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tiles by mapVm.tiles.collectAsStateWithLifecycle()
    val followInfo by followVm.info.collectAsStateWithLifecycle()
    val recordingActive by followVm.recordingActive.collectAsStateWithLifecycle()
    // „Nachfahren“: laeuft eine Aufzeichnung, geht es nicht (Hinweis); laeuft schon ein anderer Track, fragt ein Dialog vorher; sonst oeffnet es die Karte, die die Sitzung beginnt.
    var followDialog: FollowDialog? by remember { mutableStateOf(null) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saveUnavailableMessage = stringResource(R.string.history_save_unavailable)

    // „Speichern“: das Dateidialog-System fragt nach Ort und Namen (der Vorschlag kommt vom ViewModel); null bei Abbruch. Gibt es keinen Dateidialog im System, sagt es eine Snackbar.
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { uri -> if (uri != null) vm.saveTo(uri) }
    val onSave: () -> Unit = {
        try {
            saveLauncher.launch(vm.suggestedFileName())
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(saveUnavailableMessage) }
        }
    }

    ExportFeedbackEffects(
        feedback = vm.exportFeedback,
        snackbarHostState = snackbarHostState,
        shareChooserTitle = stringResource(R.string.track_export_chooser),
        nothingToExportMessage = stringResource(R.string.transfer_nothing_to_export),
        savedMessage = if (saved) stringResource(R.string.transfer_saved) else null,
        onSavedShown = vm::consumeSaved,
    )

    TrackDetailContent(
        state = state,
        onBack = rememberClosePage(navController),
        onSave = onSave,
        onShare = vm::share,
        onFollow = {
            (state as? TrackDetailState.Ready)?.let { ready ->
                val id = ready.summary.id
                val running = followInfo
                when {
                    recordingActive -> followDialog = FollowDialog.Recording
                    running != null && running.trackId != id -> followDialog = FollowDialog.Replace(id, running.name)
                    else -> navController.openSubPage(Screen.TrackFollow.routeFor(id))
                }
            }
        },
        exporting = exporting,
        snackbarHostState = snackbarHostState,
        tiles = tiles,
    )

    when (val dialog = followDialog) {
        FollowDialog.Recording -> FollowRecordingDialog(onDismiss = { followDialog = null })
        is FollowDialog.Replace -> FollowReplaceDialog(
            current = dialog.currentName,
            onConfirm = {
                followDialog = null
                followVm.stop()
                navController.openSubPage(Screen.TrackFollow.routeFor(dialog.trackId))
            },
            onDismiss = { followDialog = null },
        )
        null -> Unit
    }
}

/** Eine Rueckfrage vor dem Nachfahren: eine Aufzeichnung laeuft, oder es wird schon einem anderen Track gefolgt. */
private sealed interface FollowDialog {
    data object Recording : FollowDialog
    data class Replace(val trackId: Long, val currentName: String?) : FollowDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackDetailContent(
    state: TrackDetailState,
    onBack: () -> Unit,
    onSave: () -> Unit = {},
    onShare: () -> Unit = {},
    onFollow: () -> Unit = {},
    exporting: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    tiles: MapTiles? = null,
) {
    val title = (state as? TrackDetailState.Ready)?.summary?.name ?: stringResource(R.string.track_detail_title)
    // Exportieren kann man nur, was Punkte hat (ein leerer Track haette nur eine nutzlose Datei).
    val canExport = state is TrackDetailState.Ready && state.bounds != null
    Scaffold(
        topBar = {
            TopAppBar(
                // Eine Zeile mit Auslassungspunkten; ohne `softWrap = false` bricht Compose an einer Leerstelle um und zeigt nur das erste Stueck, ohne Hinweis.
                title = { Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
                actions = {
                    if (canExport) {
                        IconButton(onClick = onFollow, modifier = Modifier.heightIn(min = 56.dp).testTag(TRACK_FOLLOW_OPEN_TEST_TAG)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.track_follow_open))
                        }
                        IconButton(onClick = onSave, enabled = !exporting, modifier = Modifier.heightIn(min = 56.dp).testTag(TRACK_EXPORT_SAVE_TEST_TAG)) {
                            Icon(AppIcons.Download, contentDescription = stringResource(R.string.track_cd_export_save))
                        }
                        IconButton(onClick = onShare, enabled = !exporting, modifier = Modifier.heightIn(min = 56.dp).testTag(TRACK_EXPORT_SHARE_TEST_TAG)) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.track_cd_export_share))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                TrackDetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                TrackDetailState.NotFound -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_not_found))
                TrackDetailState.Failed -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_failed))
                is TrackDetailState.Ready -> ReadyContent(state, tiles)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: TrackDetailState.Ready, tiles: MapTiles?) {
    val bounds = state.bounds
    if (bounds == null) {
        EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_empty))
        return
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            TrackMapView(state.points, bounds, Modifier.weight(1f).fillMaxSize(), tiles = tiles)
            Column(Modifier.width(240.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackStatsPanel(state.summary, Modifier.fillMaxWidth())
                ExportNote()
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TrackMapView(state.points, bounds, Modifier.weight(1f).fillMaxWidth(), tiles = tiles)
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackStatsPanel(state.summary, Modifier.fillMaxWidth())
                ExportNote()
            }
        }
    }
}

/** Der Hinweis zum GPX-Export: die Datei enthaelt die Wege des Nutzers. */
@Composable
private fun ExportNote() {
    Text(
        stringResource(R.string.track_export_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TRACK_EXPORT_NOTE_TEST_TAG),
    )
}

/** Die Kennzahlen des Tracks; ein Wert, den es nicht gibt, ist ein Strich, nie 0. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackStatsPanel(summary: TrackSummary, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val duration = if (summary.startMs != null && summary.endMs != null) summary.endMs - summary.startMs else null
    val points = androidx.compose.ui.res.pluralStringResource(R.plurals.tracks_points, summary.pointCount, summary.pointCount)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(R.string.track_stat_distance, formatTrackDistance(summary.distanceM, locale))
        Stat(R.string.track_stat_duration, formatTrackDuration(duration))
        Stat(R.string.track_stat_ascent, formatTrackMetres(summary.ascentM, locale))
        Stat(R.string.track_stat_descent, formatTrackMetres(summary.descentM, locale))
        Stat(R.string.track_stat_points, points)
        Stat(R.string.track_stat_start, summary.startMs?.let { formatDateTime(it, locale) } ?: TRACK_UNKNOWN)
        Stat(
            R.string.track_stat_source,
            stringResource(if (summary.source == TrackSource.RECORDED) R.string.tracks_source_recorded else R.string.tracks_source_imported),
        )
    }
}

@Composable
private fun Stat(labelRes: Int, value: String) {
    Column {
        SectionLabel(stringResource(labelRes))
        LiveValueText(value, style = MaterialTheme.typography.titleMedium.asLiveValue(), color = MaterialTheme.colorScheme.onSurface)
    }
}

// region — Previews

private fun previewSummary() = TrackSummary(
    id = 1, name = "2026-11-17 10:00", source = TrackSource.RECORDED, startMs = 1_800_000_000_000, endMs = 1_800_003_725_000, sessionId = null,
    distanceM = 12_345, ascentM = 140, descentM = null, pointCount = 1_840, createdMs = 1_800_000_000_000, running = false,
)

private fun previewPoints(): List<TrackPoint> = List(80) { i ->
    TrackPoint(null, 50.0 + 0.003 * Math.sin(i / 80.0 * 6.0), 10.0 + 0.006 * i / 80.0, null, null, null)
}

@Composable
private fun TrackDetailPreviewContent() {
    val points = previewPoints()
    val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    TrackDetailContent(TrackDetailState.Ready(previewSummary(), points, bounds), onBack = {})
}

@Preview(name = "TrackDetail · light", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackDetailLight() = PreviewTheme(dark = false) { TrackDetailPreviewContent() }

@Preview(name = "TrackDetail · dark", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackDetailDark() = PreviewTheme(dark = true) { TrackDetailPreviewContent() }

// endregion
