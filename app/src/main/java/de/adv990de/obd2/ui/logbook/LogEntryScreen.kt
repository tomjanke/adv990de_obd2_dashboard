package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.FormError
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogForm
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAX_BRAND_LENGTH
import de.adv990de.obd2.logbook.MAX_NOTE_LENGTH
import de.adv990de.obd2.ui.settings.SettingsSwitchRow
import de.adv990de.obd2.ui.theme.PreviewTheme
import java.time.LocalDate

/** Glove-friendly touch height (CLAUDE.md design principle 4). */
internal val TouchMinHeight = 56.dp

/** Test tags. */
internal fun logbookFieldTestTag(field: FormField) = "logbook_field_${field.name}"
internal fun logbookKindTestTag(type: LogType) = "logbook_kind_${type.name}"
internal const val LOGBOOK_SAVE_TEST_TAG = "logbook_save"
internal const val LOGBOOK_FULL_TANK_TEST_TAG = "logbook_full_tank"
internal const val LOGBOOK_WARNING_CONFIRM_TEST_TAG = "logbook_warning_confirm"
internal const val LOGBOOK_WARNING_CHECK_TEST_TAG = "logbook_warning_check"
internal const val LOGBOOK_GAUGE_YES_TEST_TAG = "logbook_gauge_yes"
internal const val LOGBOOK_GAUGE_NO_TEST_TAG = "logbook_gauge_no"
internal const val LOGBOOK_DATE_OK_TEST_TAG = "logbook_date_ok"

/** Alles, was die Eintragsseite zeigt (ohne ViewModel, damit Vorschauen und Tests sie direkt komponieren). */
internal class LogEntryUi(
    val form: LogForm,
    val isEditing: Boolean,
    val errors: Map<FormField, FormError>,
    val lastEntry: LogEntry?,
    val pricePerLiterCents: Int?,
    val isSaving: Boolean,
    val today: LocalDate,
    /** Nach einem Speichern-Versuch mit Fehlern: das oberste Feld mit Fehler, zu dem die Seite scrollt (s. [LogEntryViewModel.scrollToError]). */
    val scrollToError: ScrollToError? = null,
)

/** Was die Eintragsseite tun kann. */
internal class LogEntryActions(
    val onType: (LogType) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onOdometer: (String) -> Unit,
    val onAmount: (String) -> Unit,
    val onCost: (String) -> Unit,
    val onFullTank: (Boolean) -> Unit,
    val onNote: (String) -> Unit,
    val onBrand: (String) -> Unit,
    val onSave: () -> Unit,
)

internal val NoLogEntryActions = LogEntryActions({}, {}, {}, {}, {}, {}, {}, {}, {})

/**
 * Ein Eintrag des Logbuchs, neu oder zum Bearbeiten: eine eigene, scrollbare Seite, **kein Dialog** (im Querformat verdeckt die Tastatur die Knoepfe eines Dialogs).
 * Fehler stehen am Feld, Warnungen im Rueckfragedialog; die Taste "Fertig" der Tastatur speichert. Nach einer neuen vollen Tankung von heute fragt ein Dialog, ob die
 * Tankanzeige auf voll soll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogEntryScreen(navController: NavController, vm: LogEntryViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val errors by vm.visibleErrors.collectAsStateWithLifecycle()
    val lastEntry by vm.lastEntry.collectAsStateWithLifecycle()
    val price by vm.pricePerLiterCents.collectAsStateWithLifecycle()
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val warnings by vm.pendingWarnings.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val scrollToError by vm.scrollToError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveErrorMessage = saveError?.let { stringResource(R.string.logbook_save_error, it) }

    // Fertig gespeichert (oder der Eintrag ist nicht mehr da): zurueck zur Liste. Erst nach der Frage nach der Tankanzeige, falls sie kommt.
    LaunchedEffect(outcome) { if (outcome == SaveOutcome.Done) navController.popBackStack() }
    LaunchedEffect(notFound) { if (notFound) navController.popBackStack() }
    LaunchedEffect(saveErrorMessage) {
        saveErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSaveError()
        }
    }

    if (warnings.isNotEmpty()) WarningsDialog(warnings, onConfirm = vm::confirmWarnings, onCheck = vm::dismissWarnings)
    if (outcome == SaveOutcome.AskFuelGaugeReset) GaugeDialog(onYes = vm::resetFuelGauge, onNo = vm::declineFuelGaugeReset)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isEditing) R.string.logbook_entry_edit_title else R.string.logbook_entry_new_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.heightIn(min = TouchMinHeight)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LogEntryContent(
            ui = LogEntryUi(form, vm.isEditing, errors, lastEntry, price, isSaving, LocalDate.now(), scrollToError),
            actions = LogEntryActions(
                onType = vm::setType, onDate = vm::setDate, onOdometer = vm::setOdometer, onAmount = vm::setAmount, onCost = vm::setCost,
                onFullTank = vm::setFullTank, onNote = vm::setNote, onBrand = vm::setBrand, onSave = vm::save,
            ),
            modifier = Modifier.padding(padding),
        )
    }
}

/** Die Felder der Seite: Art, Datum, Kilometerstand, je nach Art Liter/Kosten/voll, Menge oder Fabrikat, Notiz und der Knopf "Speichern". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogEntryContent(ui: LogEntryUi, actions: LogEntryActions, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val form = ui.form
    val type = form.type

    @Composable
    fun errorText(field: FormField): String? = ui.errors[field]?.let { stringResource(it.messageRes(type)) }

    // Nach einem Speichern-Versuch mit Fehlern scrollt die Seite zum obersten Feld mit Fehler: im Querformat passt sie nicht auf den Schirm, der Knopf steht unten, der Fehler oben.
    val scrollTargets = remember { FormField.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(ui.scrollToError) { ui.scrollToError?.let { scrollTargets.getValue(it.field).bringIntoView() } }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Die Art: beim neuen Eintrag waehlbar, beim Bearbeiten gesperrt (sie bestimmt, was der Eintrag bedeutet).
        if (ui.isEditing) {
            Text(stringResource(type.labelRes()), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            Text(stringResource(R.string.logbook_field_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogType.entries.forEach { kind ->
                    FilterChip(
                        selected = kind == type,
                        onClick = { actions.onType(kind) },
                        label = { Text(stringResource(kind.labelRes())) },
                        modifier = Modifier.heightIn(min = TouchMinHeight).testTag(logbookKindTestTag(kind)),
                    )
                }
            }
        }

        DateField(date = form.date, today = ui.today, error = errorText(FormField.DATE), locale = locale, onPick = actions.onDate, modifier = Modifier.bringIntoViewRequester(scrollTargets.getValue(FormField.DATE)))

        OutlinedTextField(
            value = form.odometer,
            onValueChange = actions.onOdometer,
            label = { Text(stringResource(R.string.logbook_field_odometer)) },
            isError = ui.errors.containsKey(FormField.ODOMETER),
            supportingText = {
                val hint = ui.lastEntry?.let {
                    stringResource(R.string.logbook_last_reading, formatOdometer(it.odometerKm, locale), formatLogDate(it.date, locale))
                }
                (errorText(FormField.ODOMETER) ?: hint)?.let { Text(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(scrollTargets.getValue(FormField.ODOMETER)).testTag(logbookFieldTestTag(FormField.ODOMETER)),
        )

        if (type.hasAmount) {
            OutlinedTextField(
                value = form.amount,
                onValueChange = actions.onAmount,
                label = { Text(stringResource(if (type == LogType.FUEL) R.string.logbook_field_liters else R.string.logbook_field_amount_ml)) },
                isError = ui.errors.containsKey(FormField.AMOUNT),
                supportingText = errorText(FormField.AMOUNT)?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (type == LogType.FUEL) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(scrollTargets.getValue(FormField.AMOUNT)).testTag(logbookFieldTestTag(FormField.AMOUNT)),
            )
        }

        if (type == LogType.FUEL) {
            OutlinedTextField(
                value = form.cost,
                onValueChange = actions.onCost,
                label = { Text(stringResource(R.string.logbook_field_cost)) },
                isError = ui.errors.containsKey(FormField.COST),
                supportingText = {
                    val price = ui.pricePerLiterCents?.let {
                        stringResource(R.string.logbook_price_per_liter, stringResource(R.string.logbook_fmt_euro_per_liter, formatEuros(it, locale)))
                    }
                    (errorText(FormField.COST) ?: price)?.let { Text(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(scrollTargets.getValue(FormField.COST)).testTag(logbookFieldTestTag(FormField.COST)),
            )
            SettingsSwitchRow(
                title = stringResource(R.string.logbook_full_tank_title),
                summary = stringResource(R.string.logbook_full_tank_summary),
                checked = form.fullTank,
                onCheckedChange = actions.onFullTank,
                modifier = Modifier.testTag(LOGBOOK_FULL_TANK_TEST_TAG),
            )
        }

        if (type.hasBrand) {
            OutlinedTextField(
                value = form.brand,
                onValueChange = actions.onBrand,
                label = { Text(stringResource(R.string.logbook_field_brand)) },
                isError = ui.errors.containsKey(FormField.BRAND),
                supportingText = { Text(errorText(FormField.BRAND) ?: stringResource(R.string.logbook_counter, form.brand.length, MAX_BRAND_LENGTH)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(scrollTargets.getValue(FormField.BRAND)).testTag(logbookFieldTestTag(FormField.BRAND)),
            )
        }

        OutlinedTextField(
            value = form.note,
            onValueChange = actions.onNote,
            label = { Text(stringResource(R.string.logbook_field_note)) },
            isError = ui.errors.containsKey(FormField.NOTE),
            supportingText = { Text(errorText(FormField.NOTE) ?: stringResource(R.string.logbook_counter, form.note.length, MAX_NOTE_LENGTH)) },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // "Fertig" speichert, wenn die Eingabe gueltig ist; sonst zeigt der Versuch die Fehler an den Feldern.
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(scrollTargets.getValue(FormField.NOTE)).testTag(logbookFieldTestTag(FormField.NOTE)),
        )

        Button(
            onClick = actions.onSave,
            enabled = !ui.isSaving,
            // Der Abstand steht VOR der Mindesthoehe: dahinter zoege er von den 56 dp ab (gemessen: 48 dp).
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = TouchMinHeight).testTag(LOGBOOK_SAVE_TEST_TAG),
        ) {
            Text(stringResource(R.string.logbook_save))
        }
    }
}

// region — Previews
//
// LogEntryScreen selbst braucht `hiltViewModel()`; der Inhalt ist zustandslos.

private val PREVIEW_TODAY = LocalDate.of(2026, 10, 25)

private val PREVIEW_LAST = LogEntry(1, LogType.FUEL, LocalDate.of(2026, 10, 2), 40_362, 14_370, true, 2_743, null, null)

private fun previewUi(form: LogForm, errors: Map<FormField, FormError> = emptyMap(), editing: Boolean = false, price: Int? = null) =
    LogEntryUi(form, editing, errors, PREVIEW_LAST, price, isSaving = false, today = PREVIEW_TODAY)

@Preview(name = "LogEntryContent · fuel (light)", showBackground = true, heightDp = 900)
@Composable
private fun PreviewLogEntryFuelLight() {
    PreviewTheme(dark = false) {
        LogEntryContent(previewUi(LogForm(date = PREVIEW_TODAY, odometer = "40500", amount = "14,37", cost = "27,43"), price = 191), NoLogEntryActions)
    }
}

@Preview(name = "LogEntryContent · errors (dark)", showBackground = true, heightDp = 900)
@Composable
private fun PreviewLogEntryErrorsDark() {
    PreviewTheme(dark = true) {
        LogEntryContent(
            previewUi(
                LogForm(date = PREVIEW_TODAY, odometer = "abc", amount = "", cost = "1000"),
                errors = mapOf(
                    FormField.ODOMETER to FormError.Invalid(FormField.ODOMETER),
                    FormField.AMOUNT to FormError.Missing(FormField.AMOUNT),
                    FormField.COST to FormError.Rule(FormField.COST, LogIssue.CostRange),
                ),
            ),
            NoLogEntryActions,
        )
    }
}

@Preview(name = "LogEntryContent · tire change (light)", showBackground = true, heightDp = 700)
@Composable
private fun PreviewLogEntryTireLight() {
    PreviewTheme(dark = false) {
        LogEntryContent(previewUi(LogForm(type = LogType.TIRE_CHANGE, date = PREVIEW_TODAY, odometer = "40500", brand = "Michelin Anakee Adventure 150/70-18")), NoLogEntryActions)
    }
}

@Preview(name = "LogEntryContent · editing oil (dark)", showBackground = true, heightDp = 700)
@Composable
private fun PreviewLogEntryEditOilDark() {
    PreviewTheme(dark = true) {
        LogEntryContent(previewUi(LogForm(type = LogType.OIL, date = PREVIEW_TODAY, odometer = "40100", amount = "150", note = "bis Max"), editing = true), NoLogEntryActions)
    }
}

// endregion
