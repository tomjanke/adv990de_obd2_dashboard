package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.components.ConfirmDeleteDialog
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.ui.formatDateTimeSeconds
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.navigation.openDataTransfer
import de.adv990de.obd2.ui.transfer.TransferMode
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.ui.theme.PreviewTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogsScreen(
    navController: NavController,
    vm: AppLogsViewModel = hiltViewModel()
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val undoBatchId by vm.undoBatchId.collectAsStateWithLifecycle()
    val confirmationVisible by vm.clearConfirmationVisible.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The shared local-delete contract: trash icon -> confirmation -> the entries disappear at once
    // -> "Rückgängig" snackbar for the length of the undo window -> only then really deleted.
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = stringResource(R.string.app_logs_deleted),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoClear,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
                actions = {
                    // Shortcut to the page "Daten sichern und wiederherstellen" with the app log chosen (CSV). 56 dp touch target (glove-friendly); disabled while there is
                    // nothing to export — which includes the undo window of a delete-all, when the list is already hidden.
                    IconButton(
                        onClick = { navController.openDataTransfer(Screen.DataTransfer.routeFor(TransferMode.EXPORT, DataPart.APP_LOG)) },
                        enabled = logs.isNotEmpty(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.app_logs_export))
                    }
                    // 56 dp touch target (glove-friendly); disabled while there is nothing to delete —
                    // which includes the undo window, when the list is already hidden.
                    IconButton(
                        onClick = vm::requestClear,
                        enabled = logs.isNotEmpty(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.app_logs_clear))
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                text = stringResource(R.string.app_logs_empty),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(logs, key = { it.id }) { entry ->
                    LogEntryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmationVisible) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.app_logs_clear_title),
            message = stringResource(R.string.app_logs_clear_message),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = vm::confirmClear,
            onDismiss = vm::cancelClear,
        )
    }
}

internal fun parseLogLevelOrDefault(raw: String): LogLevel =
    runCatching { LogLevel.valueOf(raw) }.getOrDefault(LogLevel.INFO)

@Composable
private fun LogEntryRow(entry: AppLogEntity) {
    val level = parseLogLevelOrDefault(entry.level)
    val chip = logLevelChipColors(level, MaterialTheme.colorScheme)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formatDateTimeSeconds(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
        }
        SuggestionChip(
            onClick = {},
            label = { Text(level.name, style = MaterialTheme.typography.labelSmall) },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = chip.container,
                labelColor = chip.content
            )
        )
    }
}

// region — Previews
//
// AppLogsScreen selbst braucht `hiltViewModel()`; die Zeile ist zustandslos — je Stufe eine, damit die Chip-Farben im Blick bleiben.

private fun previewLog(level: LogLevel, message: String) =
    AppLogEntity(id = level.ordinal.toLong(), timestamp = 1_791_000_000_000L, level = level.name, message = message)

@Composable
private fun LogRowsPreviewContent() {
    Column {
        LogEntryRow(previewLog(LogLevel.INFO, "Connected to OBDII"))
        LogEntryRow(previewLog(LogLevel.WARN, "Reconnect attempt 2/5 failed: Timeout"))
        LogEntryRow(previewLog(LogLevel.ERROR, "Recording failed, the live display continues: database or disk is full"))
    }
}

@Preview(name = "LogEntryRow · all levels (light)", showBackground = true)
@Composable
private fun PreviewLogEntryRowsLight() {
    PreviewTheme(dark = false) { LogRowsPreviewContent() }
}

@Preview(name = "LogEntryRow · all levels (dark)", showBackground = true)
@Composable
private fun PreviewLogEntryRowsDark() {
    PreviewTheme(dark = true) { LogRowsPreviewContent() }
}

// endregion
