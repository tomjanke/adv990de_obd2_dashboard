package de.adv990de.obd2.ui.dashboardconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardConfigScreen(
    navController: NavController,
    vm: DashboardConfigViewModel = hiltViewModel()
) {
    val selectedPids      by vm.selectedPidIds.collectAsState()
    val isConnected       by vm.isConnected.collectAsState()
    val allPids           by vm.allPids.collectAsState()
    val rpmBlinkThreshold by vm.rpmBlinkThreshold.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dashboard_config_back_desc))
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (isConnected) {
                item {
                    Text(
                        stringResource(R.string.dashboard_config_connected_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider()
                }
            }
            items(allPids, key = { it.id }) { pid ->
                // Prevent deselecting the last PID — an empty selection would cause a silent
                // fallback to defaults in DashboardViewModel, confusing the user.
                val isLastSelected = pid.id in selectedPids && selectedPids.size == 1
                ListItem(
                    modifier = Modifier.clickable(enabled = !isLastSelected) { vm.togglePid(pid.id) },
                    headlineContent = { Text("${pid.name} (${pid.unit})") },
                    leadingContent = {
                        // onCheckedChange=null: the row-level click handler owns the toggle.
                        // Setting it non-null would create two separate tap targets on the
                        // same logical action and could fire togglePid twice.
                        Checkbox(
                            checked = pid.id in selectedPids,
                            onCheckedChange = null,
                            enabled = !isLastSelected
                        )
                    }
                )
                HorizontalDivider()
            }
            // ── RPM blink threshold ──────────────────────────────────────────
            item {
                HorizontalDivider()
                var localThreshold by remember(rpmBlinkThreshold) { mutableIntStateOf(rpmBlinkThreshold) }
                val thresholdLabel = if (localThreshold == 0)
                    stringResource(R.string.dashboard_rpm_threshold_disabled)
                else
                    "$localThreshold rpm"
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dashboard_rpm_threshold_label)) },
                    supportingContent = { Text(thresholdLabel) }
                )
                Slider(
                    value = localThreshold.toFloat(),
                    onValueChange = { localThreshold = it.toInt() },
                    onValueChangeFinished = { vm.setRpmBlinkThreshold(localThreshold) },
                    valueRange = 0f..10000f,
                    steps = 19,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
