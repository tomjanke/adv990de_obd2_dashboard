package de.adv990de.obd2.ui.dashboardconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.ui.components.BottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardConfigScreen(
    navController: NavController,
    vm: DashboardConfigViewModel = hiltViewModel()
) {
    val selectedPids by vm.selectedPidIds.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Dashboard konfigurieren") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(vm.allPids, key = { it.id }) { pid ->
                ListItem(
                    modifier = Modifier.clickable { vm.togglePid(pid.id) },
                    headlineContent = { Text("${pid.name} (${pid.unit})") },
                    leadingContent = {
                        // onCheckedChange=null: the row-level click handler owns the toggle.
                        // Setting it non-null would create two separate tap targets on the
                        // same logical action and could fire togglePid twice.
                        Checkbox(
                            checked = pid.id in selectedPids,
                            onCheckedChange = null
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
