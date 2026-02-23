package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.db.entity.AppLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timestampFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogsScreen(
    navController: NavController,
    vm: AppLogsViewModel = hiltViewModel()
) {
    val logs by vm.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearAll() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.app_logs_clear))
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.app_logs_empty), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(logs, key = { it.id }) { entry ->
                    LogEntryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: AppLogEntity) {
    val level = runCatching { LogLevel.valueOf(entry.level) }.getOrDefault(LogLevel.INFO)
    val chipColor = when (level) {
        LogLevel.INFO  -> MaterialTheme.colorScheme.primaryContainer
        LogLevel.WARN  -> MaterialTheme.colorScheme.tertiaryContainer
        LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val chipOnColor = when (level) {
        LogLevel.INFO  -> MaterialTheme.colorScheme.onPrimaryContainer
        LogLevel.WARN  -> MaterialTheme.colorScheme.onTertiaryContainer
        LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                timestampFormatter.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
        }
        SuggestionChip(
            onClick = {},
            label = { Text(level.name, style = MaterialTheme.typography.labelSmall) },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = chipColor,
                labelColor = chipOnColor
            )
        )
    }
}
