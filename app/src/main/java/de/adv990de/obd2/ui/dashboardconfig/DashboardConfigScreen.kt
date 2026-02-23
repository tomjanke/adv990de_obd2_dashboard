package de.adv990de.obd2.ui.dashboardconfig

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
    val rpmBlinkThreshold by vm.rpmBlinkThreshold.collectAsState()
    val rpmDynamicThreshold by vm.rpmDynamicThreshold.collectAsState()

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
            // ── RPM blink threshold ──────────────────────────────────────────
            item {
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
                HorizontalDivider()
                if (localThreshold > 0) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.dashboard_rpm_dynamic_label)) },
                        supportingContent = { Text(stringResource(R.string.dashboard_rpm_dynamic_desc)) },
                        trailingContent = {
                            Switch(
                                checked = rpmDynamicThreshold,
                                onCheckedChange = { vm.setRpmDynamicThreshold(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
