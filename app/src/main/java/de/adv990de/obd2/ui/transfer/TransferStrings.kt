package de.adv990de.obd2.ui.transfer

import androidx.annotation.StringRes
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ExportCounts
import de.adv990de.obd2.transfer.DataPart

/** Der Name einer Art in den Zeilen der Seite. */
@StringRes
internal fun partLabelRes(part: DataPart): Int = when (part) {
    DataPart.TRIPS -> R.string.transfer_part_trips
    DataPart.LOGBOOK -> R.string.transfer_part_logbook
    DataPart.SETTINGS -> R.string.transfer_part_settings
    DataPart.COUNTERS -> R.string.transfer_part_counters
    DataPart.DTC_HISTORY -> R.string.transfer_part_dtc_history
    DataPart.APP_LOG -> R.string.transfer_part_app_log
}

/** Die Mengenform der Zahl einer Art oder `null`, wenn sie keine Menge hat (Einstellungen, Zaehler, in Phase 1 auch die Fehlerhistorie). */
internal fun partCountPluralRes(part: DataPart): Int? = when (part) {
    DataPart.TRIPS -> R.plurals.transfer_count_trips
    DataPart.LOGBOOK -> R.plurals.transfer_count_entries
    DataPart.APP_LOG -> R.plurals.transfer_count_lines
    else -> null
}

/** Die Reihenfolge der Zeilen beim Export: die Reihenfolge der Seite, begrenzt auf das, was diese Version kann. */
internal val EXPORT_ROW_ORDER: List<DataPart> =
    listOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.SETTINGS, DataPart.COUNTERS, DataPart.DTC_HISTORY, DataPart.APP_LOG).filter { it in DataPart.AVAILABLE }

/** Eine Zeile ohne Eintraege ist ausgegraut und nicht waehlbar; ohne bekannte Menge (noch nicht gelesen) ist sie waehlbar. */
internal fun ExportCounts?.isPartEmpty(part: DataPart): Boolean = this?.isEmpty(part) == true
