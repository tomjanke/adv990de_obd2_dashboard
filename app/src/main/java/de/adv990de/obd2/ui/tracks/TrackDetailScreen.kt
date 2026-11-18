package de.adv990de.obd2.ui.tracks

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.navigation.rememberClosePage
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue

/** Die Ansicht eines Tracks (Route `track_detail/{id}`): Zeichnung ohne Karte, darunter oder daneben die Kennzahlen. */
@Composable
fun TrackDetailScreen(navController: NavController, vm: TrackDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    TrackDetailContent(state = state, onBack = rememberClosePage(navController))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackDetailContent(state: TrackDetailState, onBack: () -> Unit) {
    val title = (state as? TrackDetailState.Ready)?.summary?.name ?: stringResource(R.string.track_detail_title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                TrackDetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                TrackDetailState.NotFound -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_not_found))
                TrackDetailState.Failed -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_failed))
                is TrackDetailState.Ready -> ReadyContent(state)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: TrackDetailState.Ready) {
    val bounds = state.bounds
    if (bounds == null) {
        EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_empty))
        return
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            TrackMapView(state.points, bounds, Modifier.weight(1f).fillMaxSize())
            TrackStatsPanel(state.summary, Modifier.width(240.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TrackMapView(state.points, bounds, Modifier.weight(1f).fillMaxWidth())
            TrackStatsPanel(state.summary, Modifier.fillMaxWidth().padding(12.dp))
        }
    }
}

/** Die Kennzahlen des Tracks; ein Wert, den es nicht gibt, ist ein Strich, nie 0. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackStatsPanel(summary: TrackSummary, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val duration = if (summary.startMs != null && summary.endMs != null) summary.endMs - summary.startMs else null
    val points = androidx.compose.ui.res.pluralStringResource(R.plurals.tracks_points, summary.pointCount, summary.pointCount)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(R.string.track_stat_distance, formatTrackDistance(summary.distanceM, locale))
        Stat(R.string.track_stat_duration, formatTrackDuration(duration))
        Stat(R.string.track_stat_ascent, formatTrackMetres(summary.ascentM, locale))
        Stat(R.string.track_stat_descent, formatTrackMetres(summary.descentM, locale))
        Stat(R.string.track_stat_points, points)
        Stat(R.string.track_stat_start, summary.startMs?.let { formatDateTime(it, locale) } ?: TRACK_UNKNOWN)
        Stat(
            R.string.track_stat_source,
            stringResource(if (summary.source == TrackSource.RECORDED) R.string.tracks_source_recorded else R.string.tracks_source_imported),
        )
    }
}

@Composable
private fun Stat(labelRes: Int, value: String) {
    Column {
        SectionLabel(stringResource(labelRes))
        LiveValueText(value, style = MaterialTheme.typography.titleMedium.asLiveValue(), color = MaterialTheme.colorScheme.onSurface)
    }
}

// region — Previews

private fun previewSummary() = TrackSummary(
    id = 1, name = "2026-11-17 10:00", source = TrackSource.RECORDED, startMs = 1_800_000_000_000, endMs = 1_800_003_725_000, sessionId = null,
    distanceM = 12_345, ascentM = 140, descentM = null, pointCount = 1_840, createdMs = 1_800_000_000_000, running = false,
)

private fun previewPoints(): List<TrackPoint> = List(80) { i ->
    TrackPoint(null, 50.0 + 0.003 * Math.sin(i / 80.0 * 6.0), 10.0 + 0.006 * i / 80.0, null, null, null)
}

@Composable
private fun TrackDetailPreviewContent() {
    val points = previewPoints()
    val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    TrackDetailContent(TrackDetailState.Ready(previewSummary(), points, bounds), onBack = {})
}

@Preview(name = "TrackDetail · light", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackDetailLight() = PreviewTheme(dark = false) { TrackDetailPreviewContent() }

@Preview(name = "TrackDetail · dark", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackDetailDark() = PreviewTheme(dark = true) { TrackDetailPreviewContent() }

// endregion
