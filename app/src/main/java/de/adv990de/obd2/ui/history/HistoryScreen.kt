package de.adv990de.obd2.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import de.adv990de.obd2.R
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.VinDecoder
import de.adv990de.obd2.ui.components.ConfirmDeleteDialog
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.SwipeToDeleteRow
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.ui.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController, vm: HistoryViewModel = hiltViewModel()) {
    val sessions = vm.sessions.collectAsLazyPagingItems()
    val shareUri by vm.shareUri.collectAsStateWithLifecycle()
    val exportError by vm.exportError.collectAsStateWithLifecycle()
    val activeSessionId by vm.activeSessionId.collectAsStateWithLifecycle()
    val canDeleteAll by vm.canDeleteAll.collectAsStateWithLifecycle()
    val undoBatchId by vm.undoBatchId.collectAsStateWithLifecycle()
    val undoCount by vm.undoCount.collectAsStateWithLifecycle()
    val deleteAllConfirmationVisible by vm.deleteAllConfirmationVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Strings auf Composable-Ebene aufloesen: context.getString() in einem LaunchedEffect
    // umgeht die Recomposition bei Sprach- oder Konfigurationswechsel.
    val shareChooserTitle = stringResource(R.string.history_share_chooser)
    val shareNoAppMessage = stringResource(R.string.history_share_no_app)
    val exportErrorMessage = exportError?.let { stringResource(R.string.history_export_error_prefix, it) }

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, shareChooserTitle))
            } catch (_: android.content.ActivityNotFoundException) {
                snackbarHostState.showSnackbar(shareNoAppMessage)
            }
            vm.consumeShareUri()
        }
    }

    LaunchedEffect(exportErrorMessage) {
        exportErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeExportError()
        }
    }

    // The shared local-delete contract: row trash or swipe -> the trip disappears at once -> the
    // "Rückgängig" snackbar for the length of the undo window -> only then really deleted.
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = pluralStringResource(R.plurals.history_deleted, undoCount, undoCount),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoDelete,
    )

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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
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
                                onDelete = { vm.deleteSession(session) }
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
) {
    ListItem(
        headlineContent = { Text(formatDateTime(session.startTime)) },
        // Aeltere Fahrten (bis v1.23.1) tragen die rohe Antwort auf Modus 09 ("NO DATA", Hexdump): nur eine
        // echte FIN wird angezeigt, alles andere gilt als unbekannt.
        supportingContent = { Text(session.vehicleVin?.takeIf(VinDecoder::isValidVin) ?: stringResource(R.string.history_vin_unknown)) },
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
