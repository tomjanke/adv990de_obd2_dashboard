package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.components.BottomNav
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(navController: NavController, vm: DashboardViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD2 Monitor") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        if (!state.isConnected) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        state.isReconnecting -> {
                            CircularProgressIndicator()
                            Text(
                                "Verbindung unterbrochen – Versuch ${state.reconnectAttempt}/${state.reconnectTotal}…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.error != null -> Text(
                            "Verbindungsfehler: ${state.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        else -> Text("Nicht verbunden. Bitte unter Einstellungen ein Gerät auswählen.")
                    }
                }
            }
        } else if (state.readings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.readings, key = { it.pid.id }) { reading -> SensorCard(reading) }
            }
        }
    }
}

@Composable
fun SensorCard(value: SensorValue) {
    Card(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(value.pid.name, style = MaterialTheme.typography.labelMedium)
            val displayValue = if (value.value.isNaN() || value.value.isInfinite()) "--"
                               else "%.1f".format(value.value)
            Text(
                "$displayValue ${value.pid.unit}",
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}
