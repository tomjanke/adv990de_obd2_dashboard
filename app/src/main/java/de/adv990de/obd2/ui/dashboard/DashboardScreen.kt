package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.BottomNav
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(navController: NavController, vm: DashboardViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Icon(
                        painterResource(R.drawable.ic_bluetooth),
                        contentDescription = stringResource(
                            if (state.isConnected) R.string.dashboard_bt_connected_desc
                            else R.string.dashboard_bt_disconnected_desc
                        ),
                        tint = if (state.isConnected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
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
                                stringResource(
                                    R.string.reconnecting_progress,
                                    state.reconnectAttempt,
                                    state.reconnectTotal
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.error != null -> Text(
                            stringResource(R.string.connection_error_prefix, state.error!!.asString()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        else -> Text(stringResource(R.string.not_connected))
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
                if (state.hasDtcs) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            onClick = { navController.navigate(Screen.Dtc.route) },
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.dashboard_dtc_warning),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
                items(state.readings, key = { it.pid.id }) { reading ->
                    SensorCard(reading, state.sparklines[reading.pid.id] ?: emptyList())
                }
            }
        }
    }
}

@Composable
fun SensorCard(value: SensorValue, sparkline: List<Float> = emptyList()) {
    val displayValue = if (value.value.isNaN() || value.value.isInfinite()) "--"
                       else "%.1f".format(value.value)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = "${value.pid.name}: $displayValue ${value.pid.unit}" }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(value.pid.name, style = MaterialTheme.typography.labelMedium)
            Text(
                "$displayValue ${value.pid.unit}",
                style = MaterialTheme.typography.headlineMedium
            )
            Sparkline(sparkline, Modifier.fillMaxWidth().height(32.dp))
        }
    }
}

@Composable
private fun Sparkline(points: List<Float>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).coerceAtLeast(0.001f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i / (points.size - 1f) * size.width
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
