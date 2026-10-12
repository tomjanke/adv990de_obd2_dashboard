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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.theme.asLiveValue

/** Test-only selector for the loading [LinearProgressIndicator] — it carries no text and no
 *  other stable anchor to distinguish it from the rest of the screen. Purely a semantics marker,
 *  no effect on production behavior. */
internal const val FREEZE_FRAME_LOADING_TEST_TAG = "freeze_frame_loading_indicator"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeFrameScreen(navController: NavController, vm: FreezeFrameViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val isConnected by vm.isConnected.collectAsStateWithLifecycle()
    // Reads when the page opens and again whenever the connection changes: connect -> read, drop -> clear.
    // Without a connection load() reads nothing.
    LaunchedEffect(isConnected) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.freeze_frame_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .testTag(FREEZE_FRAME_LOADING_TEST_TAG)
                )
            }
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentError = error
                when {
                    // Nothing was read — "no data available" would claim an answer the vehicle never gave.
                    !isConnected -> Text(
                        stringResource(R.string.not_connected),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    currentError != null -> {
                        Text(
                            stringResource(R.string.freeze_frame_error_prefix, currentError.asString()),
                            color = MaterialTheme.colorScheme.error,
                        )
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
