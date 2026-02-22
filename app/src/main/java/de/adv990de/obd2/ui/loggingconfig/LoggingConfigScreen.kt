package de.adv990de.obd2.ui.loggingconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggingConfigScreen(
    navController: NavController,
    vm: LoggingConfigViewModel = hiltViewModel()
) {
    val loggedPidIds by vm.loggedPidIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logging_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.logging_config_back_desc)
                        )
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Button(
                        onClick = { vm.selectAll() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                    ) {
                        Text(stringResource(R.string.logging_config_select_all))
                    }
                    OutlinedButton(
                        onClick = { vm.deselectAll() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    ) {
                        Text(stringResource(R.string.logging_config_deselect_all))
                    }
                }
                HorizontalDivider()
            }
            items(vm.allPids, key = { it.id }) { pid ->
                ListItem(
                    modifier = Modifier.clickable { vm.togglePid(pid.id) },
                    headlineContent = { Text("${pid.name} (${pid.unit})") },
                    leadingContent = {
                        // onCheckedChange=null: the row-level click handler owns the toggle.
                        Checkbox(
                            checked = pid.id in loggedPidIds,
                            onCheckedChange = null
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
