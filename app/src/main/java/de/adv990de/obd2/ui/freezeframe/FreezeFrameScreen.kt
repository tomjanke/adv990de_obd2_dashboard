package de.adv990de.obd2.ui.freezeframe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.ui.components.BottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeFrameScreen(navController: NavController, vm: FreezeFrameViewModel = hiltViewModel()) {
    val data by vm.data.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Freeze Frame") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                error != null -> Text("Fehler: $error", color = MaterialTheme.colorScheme.error)
                data.isEmpty() -> Text("Keine Freeze Frame Daten verfügbar.")
                else -> data.forEach { (k, v) -> Text("$k: $v") }
            }
        }
    }
}
