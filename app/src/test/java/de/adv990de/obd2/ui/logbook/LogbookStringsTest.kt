package de.adv990de.obd2.ui.logbook

import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.FormError
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Texte und Zahlenformate des Logbuchs: jede Art und jeder Filter hat ihren Text, beide Sprachen haben dieselben Schluessel, und Zahlen folgen der Gerätesprache. */
class LogbookStringsTest {

    private fun resourceName(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    private fun names(locale: String, tag: String): Set<String> {
        val xml = projectRoot.resolve("app/src/main/res/$locale/strings.xml").readText()
        return Regex("""<$tag name="(logbook_[^"]+|trips_section_logbook)"""").findAll(xml).map { it.groupValues[1] }.toSet()
    }

    private fun texts(locale: String): Map<String, String> {
        val xml = projectRoot.resolve("app/src/main/res/$locale/strings.xml").readText()
        return Regex("""<string name="((?:logbook_[^"]+)|trips_section_logbook)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // --- Schluessel und Sprachen ---

    @Test
    fun `every kind and every filter has a text named after it`() {
        for (type in LogType.entries) assertEquals("logbook_type_${type.name.lowercase()}", resourceName(type.labelRes()))
        for (filter in LogFilter.entries) assertEquals("logbook_filter_${filter.name.lowercase()}", resourceName(filter.labelRes()))
    }

    @Test
    fun `both languages have the same logbook texts and plurals`() {
        assertEquals(names("values", "string"), names("values-de", "string"))
        assertEquals(names("values", "plurals"), names("values-de", "plurals"))
        assertTrue("es gibt Texte", names("values", "string").size > 80)
    }

    @Test
    fun `no logbook text is empty, and the German one differs from the English one except for units and counters`() {
        val en = texts("values")
        val de = texts("values-de")
        val same = setOf(
            "logbook_fmt_km", "logbook_fmt_liters", "logbook_fmt_ml", "logbook_fmt_euro", "logbook_fmt_euro_per_liter", "logbook_fmt_l100", "logbook_counter",
            "logbook_row_join", "logbook_date_ok",
        )
        for ((name, text) in en) {
            assertTrue("$name leer (en)", text.isNotBlank())
            assertTrue("$name leer (de)", de.getValue(name).isNotBlank())
            if (name !in same) assertTrue("$name ist in beiden Sprachen gleich: $text", text != de.getValue(name))
        }
    }

    @Test
    fun `a form error has a text - for each way a field can fail`() {
        val cases = listOf(
            FormError.Missing(FormField.ODOMETER) to "logbook_error_missing",
            FormError.Invalid(FormField.ODOMETER) to "logbook_error_odometer_invalid",
            FormError.Invalid(FormField.AMOUNT) to "logbook_error_amount_invalid_liters",
            FormError.Invalid(FormField.COST) to "logbook_error_cost_invalid",
            FormError.Rule(FormField.ODOMETER, LogIssue.OdometerRange) to "logbook_error_odometer_range",
            FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange) to "logbook_error_amount_range_liters",
            FormError.Rule(FormField.DATE, LogIssue.DateInFuture) to "logbook_error_date_future",
            FormError.Rule(FormField.COST, LogIssue.CostRange) to "logbook_error_cost_range",
            FormError.Rule(FormField.NOTE, LogIssue.NoteTooLong) to "logbook_error_note_long",
            FormError.Rule(FormField.BRAND, LogIssue.BrandMissing) to "logbook_error_brand_missing",
            FormError.Rule(FormField.BRAND, LogIssue.BrandTooLong) to "logbook_error_brand_long",
            FormError.Rule(FormField.BRAND, LogIssue.BrandNotAllowed) to "logbook_error_brand_not_allowed",
        )
        for ((error, name) in cases) assertEquals(name, resourceName(error.messageRes(LogType.FUEL)))
    }

    @Test
    fun `the amount messages say liters for fuel and milliliters for oil and coolant`() {
        assertEquals("logbook_error_amount_invalid_ml", resourceName(FormError.Invalid(FormField.AMOUNT).messageRes(LogType.OIL)))
        assertEquals("logbook_error_amount_invalid_ml", resourceName(FormError.Invalid(FormField.AMOUNT).messageRes(LogType.COOLANT)))
        assertEquals("logbook_error_amount_range_ml", resourceName(LogIssue.AmountRange.messageRes(LogType.OIL)))
        assertEquals("logbook_error_amount_range_liters", resourceName(LogIssue.AmountRange.messageRes(LogType.FUEL)))
        assertEquals("beim Import kennt eine abgelehnte Zeile oft keine Art: ein neutraler Text", "logbook_error_amount_range", resourceName(LogIssue.AmountRange.messageRes(null)))
    }

    @Test
    fun `every rule error of the logbook has a text`() {
        val all = listOf(
            LogIssue.OdometerRange, LogIssue.AmountMissing, LogIssue.AmountRange, LogIssue.DateInFuture, LogIssue.CostRange,
            LogIssue.NoteTooLong, LogIssue.BrandMissing, LogIssue.BrandTooLong, LogIssue.BrandNotAllowed,
        )
        for (issue in all) assertTrue("$issue", resourceName(issue.messageRes(LogType.FUEL)).startsWith("logbook_error_"))
    }

    // --- Zahlen ---

    @Test
    fun `numbers follow the language of the device`() {
        assertEquals("40.362", formatOdometer(40_362, Locale.GERMANY))
        assertEquals("40,362", formatOdometer(40_362, Locale.US))
        assertEquals("14,37", formatLiters(14_370, Locale.GERMANY))
        assertEquals("14.37", formatLiters(14_370, Locale.US))
        assertEquals("27,43", formatEuros(2_743, Locale.GERMANY))
        assertEquals("5.000", formatMl(5_000, Locale.GERMANY))
        assertEquals("7,7", formatL100(7.7, Locale.GERMANY))
        assertEquals("7.7", formatL100(7.7, Locale.US))
    }

    @Test
    fun `liters and euros always show two decimals, liters a third one only when there is one`() {
        assertEquals("15,00", formatLiters(15_000, Locale.GERMANY))
        assertEquals("0,29", formatLiters(290, Locale.GERMANY))
        assertEquals("14,375", formatLiters(14_375, Locale.GERMANY))
        assertEquals("15,00", formatEuros(1_500, Locale.GERMANY))
        assertEquals("0,05", formatEuros(5, Locale.GERMANY))
        assertEquals("kein Tausendertrenner bei Betraegen unter 1000", "999,99", formatEuros(99_999, Locale.GERMANY))
    }

    @Test
    fun `a date shows in the medium style of the language`() {
        assertEquals("02.10.2026", formatLogDate(LocalDate.of(2026, 10, 2), Locale.GERMANY))
        assertEquals("Oct 2, 2026", formatLogDate(LocalDate.of(2026, 10, 2), Locale.US))
    }
}
