package de.adv990de.obd2.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.adv990de.obd2.R
import de.adv990de.obd2.map.MapInfo
import de.adv990de.obd2.map.MapProblem
import de.adv990de.obd2.map.MapState
import de.adv990de.obd2.ui.components.ConfirmDeleteDialog
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Test-only selectors der Zeile „Karte“ und ihrer Dialoge. */
internal const val MAP_ROW_TEST_TAG = "settings_map_row"
internal const val MAP_INFO_DIALOG_TEST_TAG = "settings_map_info_dialog"
internal const val MAP_REMOVE_BUTTON_TEST_TAG = "settings_map_remove"
internal const val MAP_REPLACE_BUTTON_TEST_TAG = "settings_map_replace"
internal const val MAP_FAILED_DIALOG_TEST_TAG = "settings_map_failed_dialog"

/**
 * Die Zeile „Karte“ samt Dateidialog: ohne Karte oeffnet ein Tipp den Dateidialog, mit Karte einen Dialog mit den Angaben zur Datei (Ausdehnung, Stufen, Groesse) und den Knoepfen „andere Datei
 * waehlen“ und „entfernen“ (mit Rueckfrage). Die Datei wird **nicht** heruntergeladen: sie kommt aus dem Telefon.
 */
@Composable
internal fun MapSettingSection(vm: MapSettingsViewModel) {
    val state by vm.mapState.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    MapSettingRow(
        state = state,
        importState = importState,
        onPick = {
            try {
                picker.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                vm.reportUnavailable()
            }
        },
        onRemove = vm::remove,
        onDismissFailure = vm::dismissFailure,
    )
}

/** Zustandslos fuer Vorschau und Tests. */
@Composable
internal fun MapSettingRow(
    state: MapState,
    importState: MapImportUi,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val working = importState is MapImportUi.Working
    val summary = when {
        working -> stringResource(R.string.settings_map_working)
        state is MapState.Ready -> stringResource(R.string.settings_map_ready, state.info.name ?: stringResource(R.string.settings_map_default_name), Formatter.formatFileSize(context, state.info.bytes))
        state is MapState.Broken -> stringResource(R.string.settings_map_broken)
        state is MapState.Loading -> stringResource(R.string.settings_map_working)
        else -> stringResource(R.string.settings_map_none)
    }
    SettingsLinkRow(
        title = stringResource(R.string.settings_map_title),
        summary = summary,
        onClick = {
            if (working) return@SettingsLinkRow
            if (state is MapState.Ready) showInfo = true else onPick()
        },
        modifier = modifier.testTag(MAP_ROW_TEST_TAG),
    )
    val ready = state as? MapState.Ready
    if (showInfo && ready != null) {
        MapInfoDialog(
            info = ready.info,
            onReplace = { showInfo = false; onPick() },
            onRemove = { showInfo = false; confirmRemove = true },
            onDismiss = { showInfo = false },
        )
    }
    if (confirmRemove) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_map_remove_title),
            message = stringResource(R.string.settings_map_remove_message),
            confirmLabel = stringResource(R.string.settings_map_remove),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { confirmRemove = false; onRemove() },
            onDismiss = { confirmRemove = false },
        )
    }
    val failed = importState as? MapImportUi.Failed
    if (failed != null) MapFailedDialog(failed.problem, onDismissFailure)
}

@Composable
private fun MapInfoDialog(info: MapInfo, onReplace: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MAP_INFO_DIALOG_TEST_TAG),
        title = { Text(info.name ?: stringResource(R.string.settings_map_default_name)) },
        text = {
            // Scrollbar: im Querformat (448 dp hoch) reicht der Platz neben den drei 56-dp-Knoepfen nicht, die Quellenangabe (ODbL-Pflicht) lag unter dem Rand.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_map_coverage, info.minLat, info.maxLat, info.minLon, info.maxLon))
                Text(stringResource(R.string.settings_map_zoom, info.minZoom, info.maxZoom))
                Text(Formatter.formatFileSize(context, info.bytes))
                Text(stringResource(R.string.map_attribution))
            }
        },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel gilt in seinen Slots nur mit ProvideMinTouchTarget.
        confirmButton = {
            ProvideMinTouchTarget {
                Column {
                    TextButton(onClick = onReplace, modifier = Modifier.heightIn(min = 56.dp).testTag(MAP_REPLACE_BUTTON_TEST_TAG)) { Text(stringResource(R.string.settings_map_replace)) }
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 56.dp).testTag(MAP_REMOVE_BUTTON_TEST_TAG)) { Text(stringResource(R.string.settings_map_remove)) }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.close)) }
                }
            }
        },
    )
}

@Composable
private fun MapFailedDialog(problem: MapProblem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MAP_FAILED_DIALOG_TEST_TAG),
        title = { Text(stringResource(R.string.settings_map_failed_title)) },
        text = { Text(stringResource(mapProblemText(problem))) },
        confirmButton = {
            ProvideMinTouchTarget { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.close)) } }
        },
    )
}

/** Der Text zu einem Problem. */
internal fun mapProblemText(problem: MapProblem): Int = when (problem) {
    MapProblem.NOT_PMTILES -> R.string.settings_map_problem_not_pmtiles
    MapProblem.NOT_VECTOR -> R.string.settings_map_problem_not_vector
    MapProblem.UNSUPPORTED_PACKING -> R.string.settings_map_problem_unsupported
    MapProblem.TOO_LARGE -> R.string.settings_map_problem_too_large
    MapProblem.NO_SPACE -> R.string.settings_map_problem_no_space
    MapProblem.UNREADABLE -> R.string.settings_map_problem_unreadable
}

private val previewInfo = MapInfo("Beispiel", null, 9.0, 49.0, 11.0, 51.0, 2, 14, 240L * 1024 * 1024)

@Preview(name = "MapSettingRow · none · light", showBackground = true)
@Composable
private fun PreviewMapSettingRowNoneLight() = PreviewTheme(dark = false) { MapSettingRow(MapState.NoMap, MapImportUi.Idle, {}, {}, {}) }

@Preview(name = "MapSettingRow · ready · dark", showBackground = true)
@Composable
private fun PreviewMapSettingRowReadyDark() = PreviewTheme(dark = true) { MapSettingRow(MapState.Ready(previewInfo), MapImportUi.Idle, {}, {}, {}) }
