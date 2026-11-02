package de.adv990de.obd2.ui.history

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import de.adv990de.obd2.R
import kotlinx.coroutines.launch
import de.adv990de.obd2.data.decodeSessionDtcs
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.VinDecoder
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.components.ConfirmDeleteDialog
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.ExportFeedbackEffects
import de.adv990de.obd2.ui.components.SwipeToDeleteRow
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.ui.theme.PreviewTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    header: @Composable () -> Unit = {},
    vm: HistoryViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel(),
) {
    val sessions = vm.sessions.collectAsLazyPagingItems()
    val activeSessionId by vm.activeSessionId.collectAsStateWithLifecycle()
    val canDeleteAll by vm.canDeleteAll.collectAsStateWithLifecycle()
    val undoBatchId by vm.undoBatchId.collectAsStateWithLifecycle()
    val undoCount by vm.undoCount.collectAsStateWithLifecycle()
    val deleteAllConfirmationVisible by vm.deleteAllConfirmationVisible.collectAsStateWithLifecycle()
    val canExportAll by vm.canExportAll.collectAsStateWithLifecycle()
    val exportAllDialogVisible by vm.exportAllDialogVisible.collectAsStateWithLifecycle()
    val finishedTripCount by vm.finishedTripCount.collectAsStateWithLifecycle()
    val useImperial by displayVm.useImperial.collectAsStateWithLifecycle()
    val savedCount by vm.savedCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // "Speichern": das Dateidialog-System fragt nach Ort und Namen (z. B. Downloads); das Ergebnis ist die Uri der neuen Datei, null bei Abbruch.
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.saveAllTo(uri)
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Die Fahrt, deren Details offen sind (nur die Id: eine Drehung baut die Seite neu auf, der Dialog gehoert dazu).
    var detailSessionId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Strings auf Composable-Ebene aufloesen: context.getString() in einem LaunchedEffect
    // umgeht die Recomposition bei Sprach- oder Konfigurationswechsel.
    val saveUnavailableMessage = stringResource(R.string.history_save_unavailable)

    ExportFeedbackEffects(
        feedback = vm.exportFeedback,
        snackbarHostState = snackbarHostState,
        shareChooserTitle = stringResource(R.string.history_share_chooser),
        nothingToExportMessage = stringResource(R.string.history_export_none),
        savedMessage = savedCount?.let { pluralStringResource(R.plurals.history_export_saved, it, it) },
        onSavedShown = vm::consumeSaved,
    )

    // The shared local-delete contract: row trash or swipe -> the trip disappears at once -> the
    // "Rückgängig" snackbar for the length of the undo window -> only then really deleted.
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = pluralStringResource(R.plurals.history_deleted, undoCount, undoCount),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoDelete,
    )

    if (exportAllDialogVisible) {
        ExportAllDialog(
            tripCount = finishedTripCount,
            onShare = vm::exportAll,
            onSave = {
                vm.cancelExportAll()
                try {
                    saveLauncher.launch(vm.exportAllFileName())
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbarHostState.showSnackbar(saveUnavailableMessage) }
                }
            },
            onDismiss = vm::cancelExportAll,
        )
    }

    if (deleteAllConfirmationVisible) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.history_delete_all_title),
            message = stringResource(R.string.history_delete_all_message),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = vm::confirmDeleteAll,
            onDismiss = vm::cancelDeleteAll,
        )
    }

    // Die Fahrt wird aus den geladenen Zeilen gesucht: ist sie (noch) nicht geladen, bleibt der Dialog zu und die Id erhalten.
    detailSessionId?.let { id ->
        sessions.itemSnapshotList.items.firstOrNull { it.id == id }?.let { session ->
            // Die Messwerte (F11) werden beim Oeffnen gelesen und bleiben, solange derselbe Dialog offen ist; eine Drehung liest sie neu (wenige Millisekunden).
            val stats by produceState<SessionStatsState>(SessionStatsState.Loading, id) { value = vm.loadStats(id) }
            SessionDetailDialog(session, isRecording = session.id == activeSessionId, stats = stats, useImperial = useImperial, onDismiss = { detailSessionId = null })
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    // Alle Fahrten in einer CSV: Teilen oder auf dem Geraet speichern. Gleiche Voraussetzung wie der Papierkorb daneben:
                    // mindestens eine abgeschlossene, sichtbare Fahrt (die laufende Aufzeichnung ist nicht dabei).
                    IconButton(
                        onClick = vm::requestExportAll,
                        enabled = canExportAll,
                        modifier = Modifier.size(RowButtonSize),
                    ) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.history_cd_export_all))
                    }
                    // 56 dp touch target (glove-friendly); disabled while there is nothing to delete —
                    // which includes the undo window, when the list is already hidden, and a list
                    // that holds only the trip being recorded.
                    IconButton(
                        onClick = vm::requestDeleteAll,
                        enabled = canDeleteAll,
                        modifier = Modifier.size(RowButtonSize),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_cd_delete_all))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        // Der Umschalter des Hosts (TripsAndFaultsScreen) steht ueber der Liste, wie auf der DTC-Seite.
        Column(Modifier.fillMaxSize().padding(padding)) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp)
                ) {
                    items(sessions.itemCount, key = { sessions.peek(it)?.id ?: it }) { i ->
                        sessions[i]?.let { session ->
                            val isRecording = session.id == activeSessionId
                            // The swipe is only an addition to the trash button; the trip that is being
                            // recorded can be neither swiped nor deleted.
                            SwipeToDeleteRow(
                                onDelete = { vm.deleteSession(session) },
                                modifier = Modifier.testTag(historyRowTestTag(session.id)),
                                enabled = !isRecording,
                            ) {
                                SessionRow(
                                    session,
                                    isRecording = isRecording,
                                    onExport = { vm.export(session.id) },
                                    onDelete = { vm.deleteSession(session) },
                                    onOpen = { detailSessionId = session.id },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    if (sessions.loadState.append is LoadState.Loading) {
                        item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
                    }
                }
                if (sessions.itemCount == 0 && sessions.loadState.refresh !is LoadState.Loading) {
                    EmptyState(
                        icon = ImageVector.vectorResource(R.drawable.ic_history),
                        text = stringResource(R.string.history_empty),
                    )
                }
            }
        }
    }
}

/** Glove-friendly touch size of the row and top-bar buttons (CLAUDE.md design principle 4). */
private val RowButtonSize = 56.dp

/** Test tag of one history row — the full-width swipe container the UI tests swipe. */
internal fun historyRowTestTag(sessionId: Long) = "history_row_$sessionId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRow(
    session: SessionEntity,
    isRecording: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val badge = remember(session.dtcCodes) { sessionDtcBadge(decodeSessionDtcs(session.dtcCodes)) }
    ListItem(
        // Die ganze Zeile oeffnet die Details der Fahrt; Teilen und Loeschen bleiben eigene Knoepfe.
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.history_cd_open_details), onClick = onOpen),
        headlineContent = { Text(formatDateTime(session.startTime)) },
        supportingContent = {
            Column {
                // Aeltere Fahrten (bis v1.23.1) tragen die rohe Antwort auf Modus 09 ("NO DATA", Hexdump): nur eine
                // echte FIN wird angezeigt, alles andere gilt als unbekannt.
                Text(session.vehicleVin?.takeIf(VinDecoder::isValidVin) ?: stringResource(R.string.history_vin_unknown))
                // Nur bei Fehlercodes ein Zeichen (rot, wenn ein bestaetigter dabei ist, sonst gelb); die Zeile darunter, damit sie auch
                // auf schmalen Geraeten neben den Knoepfen Platz hat.
                badge?.let {
                    val chip = sessionDtcChipColors(it, MaterialTheme.colorScheme)
                    Badge(containerColor = chip.container, contentColor = chip.content, modifier = Modifier.padding(top = 4.dp)) {
                        Text(pluralStringResource(R.plurals.history_dtc_badge, it.count, it.count))
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) {
                    // "Live"/"connected" semantics (CLAUDE.md: tertiary), not the error pink of the default Badge.
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) { Text(stringResource(R.string.history_recording_active)) }
                }
                IconButton(onClick = onExport, modifier = Modifier.size(RowButtonSize)) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.history_cd_export))
                }
                // No delete button for the trip that is being recorded — deleting it would break the recording.
                if (!isRecording) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(RowButtonSize)) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_cd_delete))
                    }
                }
            }
        }
    )
}

// region — Previews
//
// HistoryScreen selbst braucht `hiltViewModel()`; die Zeile einer Fahrt ist zustandslos.

private val PREVIEW_SESSION = SessionEntity(id = 7, startTime = 1_791_000_000_000L, endTime = 1_791_003_600_000L, vehicleVin = "WP0ZZZ99ZTS392124")

@Preview(name = "SessionRow · finished (light)", showBackground = true)
@Composable
private fun PreviewSessionRowLight() {
    PreviewTheme(dark = false) { SessionRow(PREVIEW_SESSION, isRecording = false, onExport = {}, onDelete = {}, onOpen = {}) }
}

@Preview(name = "SessionRow · finished (dark)", showBackground = true)
@Composable
private fun PreviewSessionRowDark() {
    PreviewTheme(dark = true) { SessionRow(PREVIEW_SESSION, isRecording = false, onExport = {}, onDelete = {}, onOpen = {}) }
}

@Preview(name = "SessionRow · recording, no VIN (dark)", showBackground = true)
@Composable
private fun PreviewSessionRowRecordingDark() {
    PreviewTheme(dark = true) {
        SessionRow(PREVIEW_SESSION.copy(endTime = null, vehicleVin = null), isRecording = true, onExport = {}, onDelete = {}, onOpen = {})
    }
}

// Mit Fehlercodes: bestaetigt und ausstehend (rotes Zeichen) und nur ausstehend (gelbes Zeichen), in beiden Flaechen.
private val PREVIEW_SESSION_FAULTS = PREVIEW_SESSION.copy(id = 8, dtcCodes = "P0301,P0420?")
private val PREVIEW_SESSION_PENDING = PREVIEW_SESSION.copy(id = 9, dtcCodes = "P0171?")

@Composable
private fun SessionRowsWithFaultsPreviewContent() {
    Column {
        SessionRow(PREVIEW_SESSION_FAULTS, isRecording = false, onExport = {}, onDelete = {}, onOpen = {})
        SessionRow(PREVIEW_SESSION_PENDING, isRecording = false, onExport = {}, onDelete = {}, onOpen = {})
    }
}

@Preview(name = "SessionRow · with fault codes (light)", showBackground = true)
@Composable
private fun PreviewSessionRowFaultsLight() {
    PreviewTheme(dark = false) { SessionRowsWithFaultsPreviewContent() }
}

@Preview(name = "SessionRow · with fault codes (dark)", showBackground = true)
@Composable
private fun PreviewSessionRowFaultsDark() {
    PreviewTheme(dark = true) { SessionRowsWithFaultsPreviewContent() }
}

// endregion
