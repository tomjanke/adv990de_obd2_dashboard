package de.adv990de.obd2.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.ui.components.BottomNav
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController, vm: HistoryViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsState()
    val shareUri by vm.shareUri.collectAsState()
    val exportError by vm.exportError.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Session exportieren"))
            vm.consumeShareUri()
        }
    }

    LaunchedEffect(exportError) {
        exportError?.let { snackbarHostState.showSnackbar("Export fehlgeschlagen: $it") }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Fahrtenverlauf") }) },
        bottomBar = { BottomNav(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (sessions.isEmpty()) {
                item { Text("Keine gespeicherten Fahrten.", modifier = Modifier.padding(16.dp)) }
            }
            items(sessions) { session ->
                SessionRow(session, onExport = { vm.export(session.id) })
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRow(session: SessionEntity, onExport: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(fmt.format(Date(session.startTime))) },
        supportingContent = { Text(session.vehicleVin ?: "FIN unbekannt") },
        trailingContent = {
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Share, contentDescription = "CSV exportieren")
            }
        }
    )
}
