package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// Die Dialoge und das Datumsfeld der Eintragsseite (`LogEntryScreen.kt`): ein Datumsdialog und zwei Rueckfragen, alle in einem eigenen Fenster —
// die 56-dp-Regel steht deshalb INNERHALB ihrer Slots (`ProvideMinTouchTarget`, s. CLAUDE.md "Touch-Ziele und Barrierefreiheit").

/** Das Datum: ein Feld, das den Tag zeigt und beim Antippen den Datumsdialog oeffnet (nicht nach heute). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(date: LocalDate, today: LocalDate, error: String?, locale: Locale, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val pickDate = stringResource(R.string.logbook_cd_pick_date)
    Box(modifier) {
        OutlinedTextField(
            value = formatLogDate(date, locale),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.logbook_field_date)) },
            trailingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(logbookFieldTestTag(FormField.DATE)),
        )
        // Ein schreibgeschuetztes Textfeld gibt Beruehrungen nicht weiter: eine durchsichtige Flaeche darueber nimmt den Tipp.
        Box(Modifier.matchParentSize().clickable(onClickLabel = pickDate) { showPicker = true })
    }
    if (showPicker) {
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                ProvideMinTouchTarget {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                            showPicker = false
                        },
                        modifier = Modifier.heightIn(min = TouchMinHeight).testTag(LOGBOOK_DATE_OK_TEST_TAG),
                    ) { Text(stringResource(R.string.logbook_date_ok)) }
                }
            },
            dismissButton = {
                ProvideMinTouchTarget {
                    TextButton(onClick = { showPicker = false }, modifier = Modifier.heightIn(min = TouchMinHeight)) { Text(stringResource(R.string.cancel)) }
                }
            },
        ) {
            ProvideMinTouchTarget { DatePicker(state = state) }
        }
    }
}

/** "Trotzdem speichern?" mit den Gruenden: eine Warnung blockiert das Speichern, bis der Nutzer bestaetigt. */
@Composable
internal fun WarningsDialog(warnings: List<LogIssue.Warning>, onConfirm: () -> Unit, onCheck: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    AlertDialog(
        onDismissRequest = onCheck,
        title = { Text(stringResource(R.string.logbook_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                warnings.forEach { Text(warningText(it, locale)) }
            }
        },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = TouchMinHeight).testTag(LOGBOOK_WARNING_CONFIRM_TEST_TAG)) {
                    Text(stringResource(R.string.logbook_warning_confirm))
                }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = TouchMinHeight).testTag(LOGBOOK_WARNING_CHECK_TEST_TAG)) {
                    Text(stringResource(R.string.logbook_warning_check))
                }
            }
        },
    )
}

@Composable
private fun warningText(warning: LogIssue.Warning, locale: Locale): String = when (warning) {
    is LogIssue.OdometerOutOfOrder -> stringResource(
        R.string.logbook_warning_out_of_order,
        formatLogDate(warning.other.date, locale),
        formatOdometer(warning.other.odometerKm, locale),
    )
    is LogIssue.PriceImplausible -> stringResource(
        R.string.logbook_warning_price,
        stringResource(R.string.logbook_fmt_euro_per_liter, formatEuros(warning.centsPerLiter, locale)),
    )
    is LogIssue.ConsumptionImplausible -> stringResource(
        R.string.logbook_warning_consumption,
        stringResource(R.string.logbook_fmt_l100, formatL100(warning.litersPer100Km, locale)),
    )
}

/** "Tankanzeige zuruecksetzen?" nach einer neuen vollen Tankung von heute. */
@Composable
internal fun GaugeDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(R.string.logbook_gauge_title)) },
        text = { Text(stringResource(R.string.logbook_gauge_message)) },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onYes, modifier = Modifier.heightIn(min = TouchMinHeight).testTag(LOGBOOK_GAUGE_YES_TEST_TAG)) { Text(stringResource(R.string.logbook_yes)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onNo, modifier = Modifier.heightIn(min = TouchMinHeight).testTag(LOGBOOK_GAUGE_NO_TEST_TAG)) { Text(stringResource(R.string.logbook_no)) }
            }
        },
    )
}

// region — Previews

private val PREVIEW_OTHER_ENTRY = LogEntry(1, LogType.FUEL, LocalDate.of(2026, 10, 2), 40_362, 14_370, true, 2_743, null, null)

@Preview(name = "Warnings dialog (light)", showBackground = true)
@Composable
private fun PreviewLogbookWarningsDialogLight() {
    PreviewTheme(dark = false) {
        WarningsDialog(
            warnings = listOf(LogIssue.OdometerOutOfOrder(PREVIEW_OTHER_ENTRY), LogIssue.PriceImplausible(2_217), LogIssue.ConsumptionImplausible(26.6)),
            onConfirm = {},
            onCheck = {},
        )
    }
}

@Preview(name = "Fuel gauge dialog (dark)", showBackground = true)
@Composable
private fun PreviewLogbookGaugeDialogDark() {
    PreviewTheme(dark = true) { GaugeDialog(onYes = {}, onNo = {}) }
}

// endregion
