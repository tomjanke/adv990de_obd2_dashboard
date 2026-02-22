package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel

fun convertUnit(value: Float, unit: String, imperial: Boolean): Pair<Float, String> =
    when {
        !imperial      -> value to unit
        unit == "km/h" -> value * 0.621371f to "mph"
        unit == "°C"   -> value * 9f / 5f + 32f to "°F"
        else           -> value to unit
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    vm: DashboardViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val placeholderReadings by vm.placeholderReadings.collectAsState()
    val useImperial by displayVm.useImperial.collectAsState()
    // Show real readings when connected; fall back to placeholder (value=0) tiles when not.
    val displayReadings = if (state.readings.isNotEmpty()) state.readings else placeholderReadings

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
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
    ) { padding ->
        // While connected but waiting for first readings: brief full-screen spinner.
        if (state.isConnected && state.readings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                    start = 8.dp,
                    end = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Status banner (disconnected) ──────────────────────────
                if (!state.isConnected) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when {
                                state.isReconnecting -> Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .width(20.dp)
                                            .height(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        stringResource(
                                            R.string.reconnecting_progress,
                                            state.reconnectAttempt,
                                            state.reconnectTotal
                                        ),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                state.error != null -> Text(
                                    stringResource(R.string.connection_error_prefix, state.error!!.asString()),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                                else -> Text(
                                    stringResource(R.string.not_connected),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // ── DTC warning ───────────────────────────────────────────
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

                // ── Sensor cards ──────────────────────────────────────────
                items(displayReadings, key = { it.pid.id }) { reading ->
                    SensorCard(reading, state.sparklines[reading.pid.id] ?: emptyList(), useImperial)
                }
            }
        }
    }
}

@Composable
fun SensorCard(value: SensorValue, sparkline: List<Float> = emptyList(), useImperial: Boolean = false) {
    val (convertedValue, displayUnit) = if (value.value.isNaN() || value.value.isInfinite()) {
        value.value to value.pid.unit
    } else {
        convertUnit(value.value, value.pid.unit, useImperial)
    }
    val displayValue = if (value.value.isNaN() || value.value.isInfinite()) "--"
                       else if (value.pid.unit == "V") "%.1f".format(convertedValue)
                       else "%.0f".format(convertedValue)
    val scheme = MaterialTheme.colorScheme
    val accentColor = remember(value.pid.id, scheme) {
        val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error, scheme.outline)
        colors[(value.pid.id.hashCode() and Int.MAX_VALUE) % colors.size]
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics { contentDescription = "${value.pid.name}: $displayValue $displayUnit" }
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    value.pid.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        displayValue,
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        " $displayUnit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Sparkline(sparkline, Modifier.fillMaxWidth().height(32.dp))
            }
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
