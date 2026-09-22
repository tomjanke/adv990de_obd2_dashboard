package de.adv990de.obd2.ui.freezeframe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.theme.asLiveValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeFrameScreen(navController: NavController, vm: FreezeFrameViewModel = hiltViewModel()) {
    val data by vm.data.collectAsState()
    val error by vm.error.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.freeze_frame_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dashboard_config_back_desc))
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    error != null -> {
                        Text(stringResource(R.string.freeze_frame_error_prefix, error!!), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                    }
                    data.isEmpty() -> Text(stringResource(R.string.freeze_frame_empty))
                    else -> data.forEach { (pidId, formattedValue) ->
                        // Keyed by PID id, not the English PidDefinition.name — resolve the
                        // localized label here, the same pattern SensorCard uses for the Dashboard.
                        val pidLabelRes = PidRegistry.all[pidId]?.labelRes
                        val label = if (pidLabelRes != null) stringResource(pidLabelRes) else pidId
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LiveValueText(
                                value = formattedValue,
                                style = MaterialTheme.typography.titleMedium.asLiveValue(),
                                color = MaterialTheme.colorScheme.onSurface,
                                animated = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
