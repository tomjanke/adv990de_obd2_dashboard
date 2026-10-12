package de.adv990de.obd2.ui.dtc

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.components.ConfirmDeleteDialog
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.SwipeToDeleteRow
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.ui.formatDate
import de.adv990de.obd2.ui.navigation.Screen

/** The two views of the DTC page: what the vehicle reports now, and what it has reported before. */
private enum class DtcView(@StringRes val labelRes: Int) {
    Current(R.string.dtc_tab_current),
    History(R.string.dtc_tab_history),
}

/** Glove-friendly touch size of the segments and the buttons (CLAUDE.md design principle 4). */
private val TouchSize = 56.dp

/** Test tag of the scrollable area that holds the error and the "no fault codes" state (pull-to-refresh tests). */
internal const val DTC_PULL_AREA_TEST_TAG = "dtc_pull_area"

/**
 * Inhalt, auf dem Pull-to-Refresh greift. `PullToRefreshBox` erkennt den Zug nur ueber einen scrollbaren
 * Nachkommen: Ein nacktes `Text`/`EmptyState` ist keiner — im Fehler- und im Leerzustand liess sich die Seite
 * deshalb nicht aktualisieren (B20). Der Container ist genau so hoch wie der sichtbare Bereich
 * ([BoxWithConstraintsScope.maxHeight] fuer Zentrierung) und scrollt/ueberscrollt nur symbolisch.
 */
@Composable
private fun PullableBox(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(DTC_PULL_AREA_TEST_TAG),
        content = content,
    )
}

/** Test tag of one history row — the full-width swipe container the UI tests swipe. */
internal fun dtcHistoryRowTestTag(code: String) = "dtc_history_row_$code"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(navController: NavController, vm: DtcViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    val history by vm.history.collectAsState()
    val undoBatchId by vm.undoBatchId.collectAsState()
    val undoCount by vm.undoCount.collectAsState()
    val confirmationVisible by vm.deleteAllConfirmationVisible.collectAsState()
    var view by rememberSaveable { mutableStateOf(DtcView.Current) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    // Reads when the page opens and whenever the connection changes: connect -> read (and record),
    // drop -> clear the stale list. Without a connection refresh() reads nothing.
    LaunchedEffect(isConnected) { vm.refresh() }

    // The shared local-delete contract: row trash or swipe -> the entry disappears at once -> the
    // "Rückgängig" snackbar for the length of the undo window -> only then really deleted.
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = pluralStringResource(R.plurals.dtc_history_deleted, undoCount, undoCount),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoDelete,
    )

    if (confirmationVisible) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.dtc_history_delete_all_title),
            message = stringResource(R.string.dtc_history_delete_all_message),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = vm::confirmDeleteAll,
            onDismiss = vm::cancelDeleteAll,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dtc_title)) },
                actions = {
                    // Only in the history view, 56 dp, disabled while there is nothing to delete (which
                    // includes the undo window, when the list is already hidden).
                    if (view == DtcView.History) {
                        IconButton(
                            onClick = vm::requestDeleteAll,
                            enabled = history.isNotEmpty(),
                            modifier = Modifier.size(TouchSize),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dtc_history_cd_delete_all))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ViewSwitch(selected = view, onSelect = { view = it })
            when (view) {
                DtcView.Current -> CurrentContent(
                    state = state,
                    isConnected = isConnected,
                    onRefresh = vm::refresh,
                    onOpenFreezeFrame = { navController.navigate(Screen.FreezeFrame.route) },
                    modifier = Modifier.weight(1f),
                )
                DtcView.History -> HistoryContent(
                    history = history,
                    onDelete = vm::deleteEntry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** "Aktuell | Historie" — segments ≥ 56 dp, the minimum height sits on EACH button (the row alone does not stretch them). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(selected: DtcView, onSelect: (DtcView) -> Unit) {
    val views = DtcView.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        views.forEachIndexed { index, view ->
            SegmentedButton(
                selected = view == selected,
                onClick = { onSelect(view) },
                shape = SegmentedButtonDefaults.itemShape(index, views.size),
                modifier = Modifier.heightIn(min = TouchSize),
            ) {
                Text(stringResource(view.labelRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrentContent(
    state: DtcUiState,
    isConnected: Boolean,
    onRefresh: () -> Unit,
    onOpenFreezeFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val currentError = state.error
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            when {
                // Nothing was read — saying "no fault codes" here would be a wrong all-clear.
                !isConnected -> EmptyState(
                    icon = ImageVector.vectorResource(R.drawable.ic_bluetooth),
                    text = stringResource(R.string.not_connected),
                )
                // Fehler: Grund UND ein Knopf zum erneuten Versuchen (bis 1.24.4 gab es nur den Text; der Weg
                // zurueck war ein Wisch, den es in dieser Ansicht nicht gab, s. PullableBox). 56 dp, Handschuh-tauglich.
                currentError != null -> PullableBox {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(currentError.asString(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRefresh, modifier = Modifier.heightIn(min = TouchSize)) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                state.dtcs.isEmpty() && !state.isLoading -> PullableBox {
                    EmptyState(
                        icon = Icons.Default.CheckCircle,
                        text = stringResource(R.string.dtc_empty),
                        modifier = Modifier.height(maxHeight),
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.dtcs, key = { it.code }) { dtc -> DtcRow(dtc) }
                }
            }
        }
        if (isConnected && state.dtcs.isNotEmpty()) {
            HorizontalDivider()
            TextButton(
                onClick = onOpenFreezeFrame,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dtc_freeze_frame_link)) }
        }
    }
}

@Composable
private fun HistoryContent(
    history: List<DtcHistoryItem>,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        EmptyState(
            icon = ImageVector.vectorResource(R.drawable.ic_history),
            text = stringResource(R.string.dtc_history_empty),
            modifier = modifier,
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(history, key = { it.entry.code }) { item ->
                // The swipe is only an addition to the trash button (design principle 4).
                SwipeToDeleteRow(
                    onDelete = { onDelete(item.entry.code) },
                    modifier = Modifier.testTag(dtcHistoryRowTestTag(item.entry.code)),
                ) {
                    DtcHistoryRow(item, onDelete = { onDelete(item.entry.code) })
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcRow(dtc: DtcEntry) {
    ListItem(
        headlineContent = { Text(dtc.code) },
        supportingContent = { Text(dtc.description) },
        trailingContent = {
            if (dtc.isPending) Badge { Text(stringResource(R.string.dtc_badge_pending)) }
        }
    )
    HorizontalDivider()
}

/** A recorded code: description, first/last sighting, "Aktiv" while the vehicle reports it, "Ausstehend" if last seen pending. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DtcHistoryRow(item: DtcHistoryItem, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.entry.code) },
        supportingContent = {
            Column {
                Text(item.entry.description)
                Text(
                    stringResource(
                        R.string.dtc_history_dates,
                        formatDate(item.entry.firstSeen),
                        formatDate(item.entry.lastSeen),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.isActive) Badge { Text(stringResource(R.string.dtc_badge_active)) }
                if (item.entry.lastWasPending) Badge { Text(stringResource(R.string.dtc_badge_pending)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(TouchSize)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dtc_history_cd_delete))
                }
            }
        }
    )
}
