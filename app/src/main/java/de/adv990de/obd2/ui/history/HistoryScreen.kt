package de.adv990de.obd2.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import de.adv990de.obd2.R
import de.adv990de.obd2.data.db.entity.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController, vm: HistoryViewModel = hiltViewModel()) {
    val sessions = vm.sessions.collectAsLazyPagingItems()
    val shareUri by vm.shareUri.collectAsState()
    val exportError by vm.exportError.collectAsState()
    val deleteConfirmation by vm.deleteConfirmation.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.history_share_chooser)))
            } catch (_: android.content.ActivityNotFoundException) {
                snackbarHostState.showSnackbar(context.getString(R.string.history_share_no_app))
            }
            vm.consumeShareUri()
        }
    }

    LaunchedEffect(exportError) {
        exportError?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.history_export_error_prefix, it))
            vm.consumeExportError()
        }
    }

    deleteConfirmation?.let { session ->
        val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm, fmt.format(Date(session.startTime)))) },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete() }) {
                    Text(stringResource(R.string.history_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelDelete() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp)
            ) {
                items(sessions.itemCount, key = { sessions.peek(it)?.id ?: it }) { i ->
                    sessions[i]?.let { session ->
                        SessionRow(
                            session,
                            onExport = { vm.export(session.id) },
                            onDelete = { vm.requestDelete(session) }
                        )
                        HorizontalDivider()
                    }
                }
                if (sessions.loadState.append is LoadState.Loading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
                }
            }
            if (sessions.itemCount == 0 && sessions.loadState.refresh !is LoadState.Loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_history),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRow(session: SessionEntity, onExport: () -> Unit, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(fmt.format(Date(session.startTime))) },
        supportingContent = { Text(session.vehicleVin ?: stringResource(R.string.history_vin_unknown)) },
        trailingContent = {
            Row {
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.history_cd_export))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_cd_delete))
                }
            }
        }
    )
}
