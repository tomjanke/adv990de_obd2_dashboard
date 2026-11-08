package de.adv990de.obd2.ui.transfer

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ExportCounts
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.TransferResult
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.ExportFeedbackEffects
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.theme.PreviewTheme
import kotlinx.coroutines.launch

/** Test tags der Seite. */
internal const val TRANSFER_MODE_EXPORT_TEST_TAG = "transfer_mode_export"
internal const val TRANSFER_MODE_IMPORT_TEST_TAG = "transfer_mode_import"
internal fun transferPartRowTestTag(part: DataPart) = "transfer_part_${part.name}"
internal fun transferImportPartTestTag(part: DataPart) = "transfer_import_part_${part.name}"
internal const val TRANSFER_FORMAT_PACKAGE_TEST_TAG = "transfer_format_package"
internal const val TRANSFER_FORMAT_CSV_TEST_TAG = "transfer_format_csv"
internal const val TRANSFER_CSV_HINT_TEST_TAG = "transfer_csv_hint"
internal const val TRANSFER_NOTE_APP_LOG_TEST_TAG = "transfer_note_app_log"
internal const val TRANSFER_NOTE_NO_BACKUP_TEST_TAG = "transfer_note_no_backup"
internal const val TRANSFER_NOTE_RECORDING_TEST_TAG = "transfer_note_recording"
internal const val TRANSFER_NOTE_VIN_TEST_TAG = "transfer_note_vin"
internal const val TRANSFER_SAVE_TEST_TAG = "transfer_save"
internal const val TRANSFER_SHARE_TEST_TAG = "transfer_share"
internal const val TRANSFER_PICK_TEST_TAG = "transfer_pick"
internal const val TRANSFER_CONFIRM_TEST_TAG = "transfer_confirm"
internal const val TRANSFER_CANCEL_TEST_TAG = "transfer_cancel"
internal const val TRANSFER_CLOSE_TEST_TAG = "transfer_close"
internal const val TRANSFER_NOTHING_TEST_TAG = "transfer_import_nothing"
internal const val TRANSFER_NEWER_FORMAT_TEST_TAG = "transfer_newer_format"
internal const val TRANSFER_APP_LOG_IGNORED_TEST_TAG = "transfer_app_log_ignored"
internal const val TRANSFER_WORKING_TEST_TAG = "transfer_working"

/** Glove-friendly touch height of segments and buttons; a part row is a setting row (64 dp). */
private val ControlMinHeight = 56.dp
private val PartRowMinHeight = 64.dp

/** Was die Export-Ansicht zeigt (zustandslos: Vorschauen und Tests setzen es von Hand). */
internal data class ExportUi(
    val counts: ExportCounts?,
    val selection: Set<DataPart>,
    val format: ExportFormat,
    val csvAllowed: Boolean,
    val canExport: Boolean,
    /** Ein Export laeuft: „Speichern“ und „Teilen“ sind aus (ein Doppeltipp startete sonst zwei). */
    val exporting: Boolean = false,
)

internal class ExportActions(
    val onToggle: (DataPart) -> Unit,
    val onFormat: (ExportFormat) -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
)

internal class ImportActions(
    val onPick: () -> Unit,
    val onToggle: (DataPart) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Die Seite „Daten sichern und wiederherstellen“ (Einstellungen → Info & Wartung; Abkuerzungen aus den Symbolen der Listen). Der Rahmen haelt nur ViewModel, Launcher und Navigation;
 * der Inhalt steht in [DataTransferExportContent] und [DataTransferImportContent] (zustandslos). Eine eigene scrollbare Seite, kein Dialog: im Querformat ist die Hoehe knapp.
 * `ActivityNotFoundException` — ein System ohne Dateidialog — sagt eine Snackbar, fuer **beide** Launcher (Speichern und Importieren).
 *
 * [pick] (Abkuerzung „Fahrten importieren“): der Dateidialog oeffnet **einmal** sofort (der Merker ueberlebt eine Drehung); bricht der Nutzer ab, steht er im Modus „Importieren“.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataTransferScreen(navController: NavController, pick: Boolean = false, vm: DataTransferViewModel = hiltViewModel()) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle()
    val csvAllowed by vm.csvAllowed.collectAsStateWithLifecycle()
    val canExport by vm.canExport.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saveUnavailableMessage = stringResource(R.string.history_save_unavailable)

    fun safely(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(saveUnavailableMessage) }
        }
    }

    // „Speichern“: das Dateidialog-System fragt nach Ort und Namen; zwei Launcher, damit sich der Dateityp nicht aendert, waehrend einer laeuft.
    val packageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PackageFormat.MIME)) { uri -> if (uri != null) vm.saveTo(uri) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.saveTo(uri) }
    // „Datei wählen“: jede Art (der Name sagt nichts ueber den Inhalt); null bei Abbruch.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickImport(uri) }

    var pickHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pick) {
        if (pick && !pickHandled) {
            pickHandled = true
            safely { importLauncher.launch(arrayOf("*/*")) }
        }
    }

    ExportFeedbackEffects(
        feedback = vm.exportFeedback,
        snackbarHostState = snackbarHostState,
        shareChooserTitle = stringResource(R.string.transfer_title),
        nothingToExportMessage = stringResource(R.string.transfer_nothing_to_export),
        savedMessage = if (saved) stringResource(R.string.transfer_saved) else null,
        onSavedShown = vm::consumeSaved,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.heightIn(min = PartRowMinHeight)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState())) {
            TransferModeSwitch(mode, onSelect = vm::setMode)
            when (mode) {
                TransferMode.EXPORT -> DataTransferExportContent(
                    state = ExportUi(counts, selection, format, csvAllowed, canExport, exporting),
                    actions = ExportActions(
                        onToggle = vm::toggle,
                        onFormat = vm::setFormat,
                        onSave = {
                            safely {
                                val name = vm.suggestedFileName()
                                if (format == ExportFormat.CSV) csvLauncher.launch(name) else packageLauncher.launch(name)
                            }
                        },
                        onShare = vm::share,
                    ),
                )
                TransferMode.IMPORT -> DataTransferImportContent(
                    state = importState,
                    actions = ImportActions(
                        onPick = { safely { importLauncher.launch(arrayOf("*/*")) } },
                        onToggle = vm::toggleImportPart,
                        onConfirm = vm::confirmImport,
                        onDismiss = vm::dismissImport,
                    ),
                )
            }
        }
    }
}

/** „Exportieren | Importieren“ — Segmente ≥ 56 dp, die Mindesthoehe sitzt an JEDEM Segment (die Zeile allein dehnt sie nicht). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferModeSwitch(mode: TransferMode, onSelect: (TransferMode) -> Unit) {
    val modes = TransferMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        modes.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == mode,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.heightIn(min = ControlMinHeight).testTag(if (item == TransferMode.EXPORT) TRANSFER_MODE_EXPORT_TEST_TAG else TRANSFER_MODE_IMPORT_TEST_TAG),
            ) {
                Text(stringResource(if (item == TransferMode.EXPORT) R.string.transfer_mode_export else R.string.transfer_mode_import))
            }
        }
    }
}

/** Eine Zeile mit Kaestchen (Ganz-Zeilen-Toggle mit der Rolle Checkbox, ≥ 64 dp); [enabled] = false: ausgegraut und ohne Wirkung. */
@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = PartRowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        label()
    }
}

/** „Fahrten · 61“, „Fahrten · noch nichts“ oder nur der Name, solange die Mengen nicht gelesen sind. */
@Composable
private fun partRowText(part: DataPart, counts: ExportCounts?): String {
    val name = stringResource(partLabelRes(part))
    val count = counts?.of(part) ?: return name
    if (count == 0) return stringResource(R.string.transfer_part_row, name, stringResource(R.string.transfer_part_empty))
    val plural = partCountPluralRes(part) ?: return name
    return stringResource(R.string.transfer_part_row, name, pluralStringResource(plural, count, count))
}

/** Die Export-Ansicht: die Teile zum Ankreuzen, das Dateiformat, die Hinweise und die Knoepfe. Zustandslos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataTransferExportContent(state: ExportUi, actions: ExportActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.transfer_export_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (part in EXPORT_ROW_ORDER) {
            val empty = state.counts.isPartEmpty(part)
            CheckRow(
                checked = part in state.selection,
                enabled = !empty,
                onToggle = { actions.onToggle(part) },
                modifier = Modifier.testTag(transferPartRowTestTag(part)),
            ) {
                Text(
                    partRowText(part, state.counts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (empty) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel(stringResource(R.string.transfer_format_label), modifier = Modifier.padding(horizontal = 16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = state.format == ExportFormat.PACKAGE,
                onClick = { actions.onFormat(ExportFormat.PACKAGE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.heightIn(min = ControlMinHeight).testTag(TRANSFER_FORMAT_PACKAGE_TEST_TAG),
            ) { Text(stringResource(R.string.transfer_format_package)) }
            SegmentedButton(
                selected = state.format == ExportFormat.CSV,
                onClick = { actions.onFormat(ExportFormat.CSV) },
                enabled = state.csvAllowed,
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.heightIn(min = ControlMinHeight).testTag(TRANSFER_FORMAT_CSV_TEST_TAG),
            ) { Text(stringResource(R.string.transfer_format_csv)) }
        }
        if (!state.csvAllowed) {
            Note(stringResource(R.string.transfer_format_csv_hint), Modifier.testTag(TRANSFER_CSV_HINT_TEST_TAG))
        }
        if (DataPart.APP_LOG in state.selection) {
            Note(stringResource(R.string.transfer_note_app_log), Modifier.testTag(TRANSFER_NOTE_APP_LOG_TEST_TAG))
        }
        // Zwei Hinweise nur der Export-Ansicht: die laufende Fahrt gehoert nicht in die Datei (nur bei gewaehlten Fahrten), und nichts sichert die Daten von selbst (immer).
        if (DataPart.TRIPS in state.selection) {
            Note(stringResource(R.string.transfer_note_recording_excluded), Modifier.testTag(TRANSFER_NOTE_RECORDING_TEST_TAG))
        }
        // Das Paket traegt die FIN je Fahrt (trips_meta.csv); die CSV der Fahrten nicht.
        if (DataPart.TRIPS in state.selection && state.format == ExportFormat.PACKAGE) {
            Note(stringResource(R.string.transfer_note_vin), Modifier.testTag(TRANSFER_NOTE_VIN_TEST_TAG))
        }
        Note(stringResource(R.string.transfer_note_no_backup), Modifier.testTag(TRANSFER_NOTE_NO_BACKUP_TEST_TAG))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.onSave,
                enabled = state.canExport && !state.exporting,
                modifier = Modifier.weight(1f).heightIn(min = ControlMinHeight).testTag(TRANSFER_SAVE_TEST_TAG),
            ) { Text(stringResource(R.string.history_export_save)) }
            OutlinedButton(
                onClick = actions.onShare,
                enabled = state.canExport && !state.exporting,
                modifier = Modifier.weight(1f).heightIn(min = ControlMinHeight).testTag(TRANSFER_SHARE_TEST_TAG),
            ) { Text(stringResource(R.string.history_export_share)) }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Die Import-Ansicht: Datei waehlen, Vorschau je Teil, Ergebnis oder Fehler. Zustandslos. */
@Composable
internal fun DataTransferImportContent(state: TransferImportState, actions: ImportActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            TransferImportState.Idle -> {
                Text(stringResource(R.string.transfer_import_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = actions.onPick, modifier = Modifier.fillMaxWidth().heightIn(min = ControlMinHeight).testTag(TRANSFER_PICK_TEST_TAG)) {
                    Text(stringResource(R.string.transfer_import_pick))
                }
            }
            TransferImportState.Working -> Row(
                Modifier.testTag(TRANSFER_WORKING_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.transfer_working))
            }
            is TransferImportState.Preview -> ImportPreview(state, actions)
            is TransferImportState.Done -> {
                for ((part, outcome) in state.result.outcomes) PartResultLine(part, outcome)
                CloseButton(actions.onDismiss)
            }
            is TransferImportState.Failed -> {
                Text(state.text.asString(), color = MaterialTheme.colorScheme.error)
                CloseButton(actions.onDismiss)
            }
        }
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = ControlMinHeight).testTag(TRANSFER_CLOSE_TEST_TAG)) {
        Text(stringResource(R.string.transfer_import_close))
    }
}

@Composable
private fun ImportPreview(state: TransferImportState.Preview, actions: ImportActions) {
    val preview = state.preview
    if (preview.newerFormat) {
        Text(stringResource(R.string.transfer_warning_newer_format), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TRANSFER_NEWER_FORMAT_TEST_TAG))
    }
    if (preview.ignoredAppLog) {
        Text(
            stringResource(R.string.transfer_note_app_log_ignored),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TRANSFER_APP_LOG_IGNORED_TEST_TAG),
        )
    }
    if (preview.parts.isEmpty()) {
        Text(stringResource(R.string.transfer_import_nothing), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(TRANSFER_NOTHING_TEST_TAG))
    }
    for ((part, plan) in preview.parts) {
        val readable = plan !is PartPlan.Unreadable
        Column(Modifier.testTag(transferImportPartTestTag(part))) {
            CheckRow(checked = readable && part in state.selected, enabled = readable, onToggle = { actions.onToggle(part) }, modifier = Modifier.padding(horizontal = 0.dp)) {
                Text(stringResource(partLabelRes(part)), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                when (plan) {
                    is PartPlan.Trips -> TripsPreviewBody(plan.plan, meta = plan.meta)
                    is PartPlan.Logbook -> LogbookPreviewBody(plan.plan)
                    is PartPlan.DtcHistory -> DtcHistoryPreviewBody(plan.plan)
                    is PartPlan.Unreadable -> UnreadablePartBody(plan.reason)
                }
            }
        }
        HorizontalDivider()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.weight(1f).heightIn(min = ControlMinHeight).testTag(TRANSFER_CANCEL_TEST_TAG)) {
            Text(stringResource(R.string.cancel))
        }
        Button(
            onClick = actions.onConfirm,
            enabled = state.selected.isNotEmpty(),
            modifier = Modifier.weight(1f).heightIn(min = ControlMinHeight).testTag(TRANSFER_CONFIRM_TEST_TAG),
        ) { Text(stringResource(R.string.transfer_import_confirm)) }
    }
}

// region — Previews

private val NO_EXPORT_ACTIONS = ExportActions({}, {}, {}, {})
private val NO_IMPORT_ACTIONS = ImportActions({}, {}, {}, {})
private val PREVIEW_COUNTS = ExportCounts(trips = 61, logbook = 104, appLog = 312)

@Composable
private fun ExportPreviewScreen(mode: TransferMode = TransferMode.EXPORT, state: ExportUi) {
    Column {
        TransferModeSwitch(mode, onSelect = {})
        DataTransferExportContent(state, NO_EXPORT_ACTIONS)
    }
}

@Preview(name = "DataTransfer · export package (light)", showBackground = true)
@Composable
private fun PreviewDataTransferExportPackageLight() {
    PreviewTheme(dark = false) {
        ExportPreviewScreen(state = ExportUi(PREVIEW_COUNTS, DataPart.DEFAULT_EXPORT, ExportFormat.PACKAGE, csvAllowed = false, canExport = true))
    }
}

@Preview(name = "DataTransfer · export package (dark)", showBackground = true)
@Composable
private fun PreviewDataTransferExportPackageDark() {
    PreviewTheme(dark = true) {
        ExportPreviewScreen(state = ExportUi(PREVIEW_COUNTS, DataPart.DEFAULT_EXPORT, ExportFormat.PACKAGE, csvAllowed = false, canExport = true))
    }
}

@Preview(name = "DataTransfer · export csv shortcut (light)", showBackground = true)
@Composable
private fun PreviewDataTransferExportCsvLight() {
    PreviewTheme(dark = false) {
        ExportPreviewScreen(state = ExportUi(PREVIEW_COUNTS, setOf(DataPart.LOGBOOK), ExportFormat.CSV, csvAllowed = true, canExport = true))
    }
}

@Preview(name = "DataTransfer · export empty part and app log (dark)", showBackground = true)
@Composable
private fun PreviewDataTransferExportEmptyDark() {
    PreviewTheme(dark = true) {
        ExportPreviewScreen(
            state = ExportUi(ExportCounts(trips = 0, logbook = 104, appLog = 312), setOf(DataPart.LOGBOOK, DataPart.APP_LOG), ExportFormat.PACKAGE, csvAllowed = false, canExport = true),
        )
    }
}

@Composable
private fun ImportPreviewScreen(state: TransferImportState) {
    Column {
        TransferModeSwitch(TransferMode.IMPORT, onSelect = {})
        DataTransferImportContent(state, NO_IMPORT_ACTIONS)
    }
}

private fun previewImportState(): TransferImportState.Preview {
    val preview = TransferPreview(
        kind = FileKind.PACKAGE,
        newerFormat = false,
        ignoredAppLog = true,
        parts = linkedMapOf(
            DataPart.LOGBOOK to PartPlan.Logbook(previewLogbookPlan()),
            DataPart.TRIPS to PartPlan.Trips(previewTripPlan(duplicates = 4)),
            DataPart.DTC_HISTORY to PartPlan.DtcHistory(previewDtcPlan()),
            DataPart.SETTINGS to PartPlan.Unreadable(UnreadableReason.FORMAT),
        ),
    )
    return TransferImportState.Preview(preview, selected = setOf(DataPart.LOGBOOK, DataPart.TRIPS))
}

@Preview(name = "DataTransfer · import idle (light)", showBackground = true)
@Composable
private fun PreviewDataTransferImportIdleLight() {
    PreviewTheme(dark = false) { ImportPreviewScreen(TransferImportState.Idle) }
}

@Preview(name = "DataTransfer · import preview (light)", showBackground = true)
@Composable
private fun PreviewDataTransferImportPreviewLight() {
    PreviewTheme(dark = false) { ImportPreviewScreen(previewImportState()) }
}

@Preview(name = "DataTransfer · import preview (dark)", showBackground = true)
@Composable
private fun PreviewDataTransferImportPreviewDark() {
    PreviewTheme(dark = true) { ImportPreviewScreen(previewImportState()) }
}

@Preview(name = "DataTransfer · import result with an error (dark)", showBackground = true)
@Composable
private fun PreviewDataTransferImportDoneDark() {
    PreviewTheme(dark = true) {
        ImportPreviewScreen(
            TransferImportState.Done(TransferResult(linkedMapOf(DataPart.LOGBOOK to PartOutcome.Done(98), DataPart.TRIPS to PartOutcome.Failed(java.io.IOException())))),
        )
    }
}

@Preview(name = "DataTransfer · import failed (light)", showBackground = true)
@Composable
private fun PreviewDataTransferImportFailedLight() {
    PreviewTheme(dark = false) { ImportPreviewScreen(TransferImportState.Failed(UiText.StringResource(R.string.transfer_error_not_a_backup))) }
}

// endregion
