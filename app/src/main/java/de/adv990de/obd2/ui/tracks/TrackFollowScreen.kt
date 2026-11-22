package de.adv990de.obd2.ui.tracks

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.map.MapTiles
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.FollowTrackState
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.navigation.rememberClosePage
import de.adv990de.obd2.ui.permission.appSettingsIntent
import de.adv990de.obd2.ui.permission.rememberLocationPermissionState
import de.adv990de.obd2.ui.settings.PermissionHintRow
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue

internal const val FOLLOW_DIRECTION_FORWARD_TEST_TAG = "follow_direction_forward"
internal const val FOLLOW_DIRECTION_REVERSE_TEST_TAG = "follow_direction_reverse"
internal const val FOLLOW_STAT_OFF_TEST_TAG = "follow_stat_off"
internal const val FOLLOW_STAT_DONE_TEST_TAG = "follow_stat_done"
internal const val FOLLOW_STAT_LEFT_TEST_TAG = "follow_stat_left"
internal const val FOLLOW_SEARCHING_TEST_TAG = "follow_searching"
internal const val FOLLOW_PERMISSION_HINT_TEST_TAG = "follow_permission_hint"
internal const val FOLLOW_NOTE_TEST_TAG = "follow_note"
internal const val FOLLOW_TOO_SHORT_TEST_TAG = "follow_too_short"
internal const val FOLLOW_PROGRESS_TEST_TAG = "follow_progress"
internal const val FOLLOW_STOP_TEST_TAG = "follow_stop"
internal const val FOLLOW_STOP_BAR_TEST_TAG = "follow_stop_bar"

/**
 * Das Nachfahren eines Tracks (Route `track_follow/{id}`): die Karte mit der eigenen Position, daneben oder darunter Abstand zum Track, Zurueckgelegtes und Rest. **Keine Routenfuehrung**,
 * kein Ton, keine Ansage; die Karte zoomt nach der Geschwindigkeit, und vor einer bedeutsamen Richtungsaenderung blendet das Roadbook-HUD (kompakt) oben in der Karte ein. Der Bildschirm bleibt
 * an, solange die Seite sichtbar ist (wie das Dashboard). Das Nachfahren laeuft weiter, wenn man die Seite verlaesst (zum Dashboard), bis „Nachfahren beenden“. Die Position wird nur
 * verwendet, solange das Nachfahren laeuft und die App sichtbar ist, nicht gespeichert und nicht geloggt.
 */
@Composable
fun TrackFollowScreen(navController: NavController, vm: TrackFollowViewModel = hiltViewModel(), mapVm: MapLayerViewModel = hiltViewModel()) {
    val tiles by mapVm.tiles.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val permission = rememberLocationPermissionState()
    val context = LocalContext.current

    // Wie im Dashboard: der Bildschirm bleibt an, solange die Seite sichtbar ist.
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Die Berechtigung kann sich ausserhalb der App aendern (`permission` liest bei jedem ON_RESUME neu): das ViewModel liest mit.
    LaunchedEffect(permission.fine, permission.coarse) { vm.refreshPermission() }

    // Beim ersten Oeffnen ohne den genauen Standort fragt das System einmal; abgelehnt bleibt die Hinweiszeile, die in die App-Einstellungen fuehrt.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission.refresh() }
    LaunchedEffect(permission.fine) {
        if (!permission.fine && !asked) {
            asked = true
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val close = rememberClosePage(navController)
    TrackFollowContent(
        track = track,
        direction = direction,
        reading = reading,
        permissionMissing = !permission.fine,
        onDirection = vm::setDirection,
        onBack = close,
        onOpenSettings = { openAppSettings(context) },
        onStop = {
            vm.stop()
            close()
        },
        tiles = tiles,
    )
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(appSettingsIntent(context.packageName))
    } catch (_: ActivityNotFoundException) {
        // kein Einstellungsbildschirm erreichbar: es gibt nichts weiter zu tun
    }
}

/** Zustandslos fuer Vorschauen und Tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackFollowContent(
    track: FollowTrackState,
    direction: FollowDirection,
    reading: FollowReading,
    permissionMissing: Boolean,
    onDirection: (FollowDirection) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onStop: () -> Unit = {},
    tiles: MapTiles? = null,
) {
    val ready = track as? FollowTrackState.Ready
    val title = when (track) {
        is FollowTrackState.Ready -> track.name
        is FollowTrackState.TooShort -> track.name
        else -> stringResource(R.string.track_follow_title)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
                actions = {
                    // Der Stopp steht auch in der Kopfzeile: der Knopf im Bedienblock ist nur mit Blick nach unten zu finden.
                    if (ready != null) {
                        val stopDescription = stringResource(R.string.track_follow_stop)
                        TextButton(
                            onClick = onStop,
                            modifier = Modifier.heightIn(min = 56.dp).testTag(FOLLOW_STOP_BAR_TEST_TAG).semantics { contentDescription = stopDescription },
                        ) { Text(stringResource(R.string.track_follow_stop_short)) }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (track) {
                FollowTrackState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                FollowTrackState.NotFound -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_not_found))
                FollowTrackState.Failed -> EmptyState(Icons.Default.Place, stringResource(R.string.track_detail_failed))
                is FollowTrackState.TooShort -> EmptyState(Icons.Default.Place, stringResource(R.string.track_follow_too_short), Modifier.testTag(FOLLOW_TOO_SHORT_TEST_TAG))
                is FollowTrackState.Ready -> if (ready != null) ReadyContent(ready, direction, reading, permissionMissing, onDirection, onOpenSettings, onStop, tiles)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    track: FollowTrackState.Ready,
    direction: FollowDirection,
    reading: FollowReading,
    permissionMissing: Boolean,
    onDirection: (FollowDirection) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: () -> Unit,
    tiles: MapTiles?,
) {
    val locale = LocalLocale.current.platformLocale
    val marker = (reading as? FollowReading.Following)?.let {
        TrackMarker(it.position, it.result.bearingDeg, it.result.foot, formatTrackDistance(Math.round(it.result.distanceToTrackM), locale))
    }
    // Rueckwaerts wird die Linie umgekehrt gezeichnet: die Startmarke sitzt dann am aufgezeichneten Ende, die Zielmarke am Anfang — dort, wo man losfaehrt und ankommt.
    val drawn = remember(track.drawn, direction) { if (direction == FollowDirection.REVERSE) track.drawn.asReversed() else track.drawn }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val following = reading as? FollowReading.Following
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        FollowPanel(direction, reading, permissionMissing, onDirection, onOpenSettings, onStop, modifier)
    }
    // Die Karte mit dem kompakten HUD obenauf (mittig, ohne den Bedienblock unten rechts zu beruehren).
    val map: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            TrackMapView(drawn, track.bounds, Modifier.fillMaxSize(), marker, orientable = true, autoSpanM = following?.autoSpanM, tiles = tiles)
            FadingRoadbookHud(following?.hud, compact = true, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 72.dp))
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            map(Modifier.weight(1f).fillMaxSize())
            panel(Modifier.width(260.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            map(Modifier.weight(1f).fillMaxWidth())
            panel(Modifier.fillMaxWidth().padding(12.dp))
        }
    }
}

@Composable
private fun FollowPanel(
    direction: FollowDirection,
    reading: FollowReading,
    permissionMissing: Boolean,
    onDirection: (FollowDirection) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DirectionChoice(direction, onDirection)
        if (permissionMissing) {
            PermissionHintRow(
                text = stringResource(R.string.track_follow_permission),
                buttonLabel = stringResource(R.string.settings_permission_open_settings),
                onOpenSettings = onOpenSettings,
                modifier = Modifier.testTag(FOLLOW_PERMISSION_HINT_TEST_TAG),
            )
        } else if (reading is FollowReading.Following) {
            FollowStats(reading.result)
        } else {
            Text(
                stringResource(R.string.track_follow_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(FOLLOW_SEARCHING_TEST_TAG),
            )
        }
        Text(
            stringResource(R.string.track_follow_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(FOLLOW_NOTE_TEST_TAG),
        )
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(FOLLOW_STOP_TEST_TAG)) {
            Text(stringResource(R.string.track_follow_stop))
        }
    }
}

/**
 * Die zwei Richtungen als Segmente (mindestens 56 dp hoch, eine Handschuh-Geste genuegt: ein Tipp). Ohne Haekchen im Segment (die gewaehlte Haelfte ist gefuellt): das Haekchen nahm dem Wort
 * die Breite, „Wie aufgezeichnet“ war im schmalen Fenster nicht lesbar. Das Wort schrumpft notfalls, statt abgeschnitten zu werden.
 */
@Composable
private fun DirectionChoice(direction: FollowDirection, onDirection: (FollowDirection) -> Unit) {
    val description = stringResource(R.string.track_follow_dir_cd)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val options = listOf(
            Triple(FollowDirection.FORWARD, R.string.track_follow_dir_forward, FOLLOW_DIRECTION_FORWARD_TEST_TAG),
            Triple(FollowDirection.REVERSE, R.string.track_follow_dir_reverse, FOLLOW_DIRECTION_REVERSE_TEST_TAG),
        )
        options.forEachIndexed { index, (value, label, tag) ->
            SegmentedButton(
                selected = direction == value,
                onClick = { onDirection(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 56.dp).testTag(tag),
                icon = {},
            ) {
                Text(
                    stringResource(label),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

/** Abstand zum Track, Zurueckgelegtes, Rest (Plex Mono, `tnum`) und der Fortschritt als Balken; eine Beschreibung fuer TalkBack fasst es zusammen. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FollowStats(result: FollowResult) {
    val locale = LocalLocale.current.platformLocale
    val off = formatTrackDistance(Math.round(result.distanceToTrackM), locale)
    val done = formatTrackDistance(Math.round(result.alongM), locale)
    val left = formatTrackDistance(Math.round(result.remainingM), locale)
    val summary = stringResource(R.string.track_follow_summary, off, done, left)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Die drei Zahlen brechen um, wo die Breite nicht reicht (schmales Fenster, Panel im Querformat, grosse Schrift): keine wird abgeschnitten.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(R.string.track_follow_stat_off, off, FOLLOW_STAT_OFF_TEST_TAG)
            Stat(R.string.track_follow_stat_done, done, FOLLOW_STAT_DONE_TEST_TAG)
            Stat(R.string.track_follow_stat_left, left, FOLLOW_STAT_LEFT_TEST_TAG)
        }
        LinearProgressIndicator(progress = { result.progress.toFloat() }, modifier = Modifier.fillMaxWidth().testTag(FOLLOW_PROGRESS_TEST_TAG))
    }
}

@Composable
private fun Stat(labelRes: Int, value: String, tag: String) {
    Column {
        SectionLabel(stringResource(labelRes))
        LiveValueText(
            value,
            style = MaterialTheme.typography.titleLarge.asLiveValue(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(tag),
        )
    }
}

// region — Previews

/** Eine erfundene Strecke bei 50 N, 10 E — nie ein echter Weg. */
private fun previewPoints(): List<TrackPoint> = List(80) { i ->
    TrackPoint(null, 50.0 + 0.003 * Math.sin(i / 80.0 * 6.0), 10.0 + 0.006 * i / 80.0, null, null, null)
}

private fun previewReady(): FollowTrackState.Ready {
    val points = previewPoints()
    val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    return FollowTrackState.Ready("Sonntagsrunde", points, points, bounds)
}

private fun previewFollowing(): FollowReading.Following {
    val foot = previewPoints()[30]
    // Die erfundene Position liegt rund 100 m neben dem Track: so zeigt die Vorschau auch die gestrichelte Abstandslinie mit dem Fusspunkt und dem Abstand.
    val position = TrackPoint(null, foot.lat - 0.0009, foot.lon, null, null, null)
    return FollowReading.Following(
        position,
        FollowResult(distanceToTrackM = 100.0, alongM = 2_300.0, remainingM = 3_850.0, totalM = 6_150.0, segmentIndex = 30, bearingDeg = 80.0, foot = foot),
    )
}

@Composable
private fun TrackFollowPreviewContent(reading: FollowReading, permissionMissing: Boolean = false, direction: FollowDirection = FollowDirection.FORWARD) {
    TrackFollowContent(previewReady(), direction, reading, permissionMissing, onDirection = {}, onBack = {})
}

@Preview(name = "TrackFollow · following · light", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackFollowFollowingLight() = PreviewTheme(dark = false) { TrackFollowPreviewContent(previewFollowing()) }

@Preview(name = "TrackFollow · following · dark", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackFollowFollowingDark() = PreviewTheme(dark = true) { TrackFollowPreviewContent(previewFollowing(), direction = FollowDirection.REVERSE) }

@Preview(name = "TrackFollow · searching · dark", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackFollowSearchingDark() = PreviewTheme(dark = true) { TrackFollowPreviewContent(FollowReading.Searching) }

@Preview(name = "TrackFollow · no permission · light", showBackground = true, heightDp = 640, widthDp = 420)
@Composable
private fun PreviewTrackFollowNoPermissionLight() = PreviewTheme(dark = false) { TrackFollowPreviewContent(FollowReading.NoPermission, permissionMissing = true) }

// endregion
