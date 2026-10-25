package de.adv990de.obd2.ui.logbook

import androidx.annotation.StringRes
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.FormError
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Texte und Zahlenformate des Logbuchs, ohne Compose: die Zuordnung von Art, Filter und Fehler zu ihren String-Ressourcen und die Zahlen fuer die Gerätesprache
 * (Komma oder Punkt, Tausendertrenner). Die Zahl wird hier formatiert, die Einheit steht in der Vorlage (`logbook_fmt_*`), damit sie in jeder Sprache ihre Stelle
 * hat. `LogbookStringsTest` haelt Schluessel, Sprachen und Formate fest.
 */

@StringRes
internal fun LogType.labelRes(): Int = when (this) {
    LogType.FUEL -> R.string.logbook_type_fuel
    LogType.OIL -> R.string.logbook_type_oil
    LogType.COOLANT -> R.string.logbook_type_coolant
    LogType.CHAIN_SMALL -> R.string.logbook_type_chain_small
    LogType.CHAIN_LARGE -> R.string.logbook_type_chain_large
    LogType.SERVICE_SMALL -> R.string.logbook_type_service_small
    LogType.SERVICE_LARGE -> R.string.logbook_type_service_large
    LogType.TIRE_CHANGE -> R.string.logbook_type_tire_change
    LogType.CHAIN_KIT_CHANGE -> R.string.logbook_type_chain_kit_change
}

@StringRes
internal fun LogFilter.labelRes(): Int = when (this) {
    LogFilter.All -> R.string.logbook_filter_all
    LogFilter.Fuel -> R.string.logbook_filter_fuel
    LogFilter.Oil -> R.string.logbook_filter_oil
    LogFilter.Coolant -> R.string.logbook_filter_coolant
    LogFilter.Maintenance -> R.string.logbook_filter_maintenance
}

/**
 * Der Text einer Regelverletzung. Die Menge hat je nach Art andere Einheit und Grenze (Liter beim Tanken, Milliliter bei Oel und Kuehlfluessigkeit); [type] ist
 * `null`, wo die Art nicht feststeht (eine abgelehnte Zeile des Imports), dann gilt ein neutraler Text.
 */
@StringRes
internal fun LogIssue.Error.messageRes(type: LogType?): Int = when (this) {
    LogIssue.OdometerRange -> R.string.logbook_error_odometer_range
    LogIssue.AmountMissing -> R.string.logbook_error_missing
    LogIssue.AmountRange -> when (type) {
        null -> R.string.logbook_error_amount_range
        LogType.FUEL -> R.string.logbook_error_amount_range_liters
        else -> R.string.logbook_error_amount_range_ml
    }
    LogIssue.DateInFuture -> R.string.logbook_error_date_future
    LogIssue.CostRange -> R.string.logbook_error_cost_range
    LogIssue.NoteTooLong -> R.string.logbook_error_note_long
    LogIssue.BrandMissing -> R.string.logbook_error_brand_missing
    LogIssue.BrandTooLong -> R.string.logbook_error_brand_long
    LogIssue.BrandNotAllowed -> R.string.logbook_error_brand_not_allowed
}

/** Der Text eines Formularfehlers am Feld: fehlt es, ist es keine Zahl der erwarteten Form, oder verletzt es eine Regel. */
@StringRes
internal fun FormError.messageRes(type: LogType): Int = when (this) {
    is FormError.Missing -> R.string.logbook_error_missing
    is FormError.Invalid -> when (field) {
        FormField.ODOMETER -> R.string.logbook_error_odometer_invalid
        FormField.AMOUNT -> if (type == LogType.FUEL) R.string.logbook_error_amount_invalid_liters else R.string.logbook_error_amount_invalid_ml
        FormField.COST -> R.string.logbook_error_cost_invalid
        // Datum, Notiz und Fabrikat sind Auswahl bzw. Freitext: sie koennen nicht "keine Zahl" sein.
        FormField.DATE, FormField.NOTE, FormField.BRAND -> R.string.logbook_error_missing
    }
    is FormError.Rule -> issue.messageRes(type)
}

internal fun formatOdometer(km: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(km)

internal fun formatMl(ml: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(ml)

/** Liter aus Millilitern: mindestens zwei Nachkommastellen, eine dritte nur, wenn die Menge kein Vielfaches von 10 ml ist; ohne Tausendertrenner (hoechstens 100 l). */
internal fun formatLiters(ml: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 3
    isGroupingUsed = false
}.format(BigDecimal.valueOf(ml.toLong(), 3))

/** Euro aus Cent mit zwei Nachkommastellen, ohne Tausendertrenner (hoechstens 999,99). */
internal fun formatEuros(cents: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
    isGroupingUsed = false
}.format(BigDecimal.valueOf(cents.toLong(), 2))

/** Liter je 100 km mit einer Nachkommastelle. */
internal fun formatL100(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)

/** Ein Tag im mittleren Stil der Sprache, z. B. `02.10.2026`. */
internal fun formatLogDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
