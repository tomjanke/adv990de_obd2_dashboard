package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Das Formular des Logbuchs, rein: aus den getippten Texten wird ein Eintrag oder ein Fehler je Feld. */
class LogFormTest {

    private val today = LocalDate.of(2026, 10, 25)

    private fun fuelForm(
        odometer: String = "40362",
        amount: String = "14,37",
        cost: String = "27,43",
        full: Boolean = true,
        note: String = "",
        date: LocalDate = today,
    ) = LogForm(type = LogType.FUEL, date = date, odometer = odometer, amount = amount, cost = cost, fullTank = full, note = note)

    private fun check(form: LogForm, others: List<LogEntry> = emptyList(), id: Long = 0) = checkForm(form, id, others, today)

    private fun FormCheck.error(field: FormField) = errors[field]

    // --- ein gueltiger Eintrag ---

    @Test
    fun `a valid fuel form becomes an entry in whole units`() {
        val result = check(fuelForm(), id = 7)

        assertEquals(emptyMap<FormField, FormError>(), result.errors)
        assertEquals(
            LogEntry(id = 7, type = LogType.FUEL, date = today, odometerKm = 40_362, amountMl = 14_370, fullTank = true, costCents = 2_743, note = null, brand = null),
            result.entry,
        )
    }

    @Test
    fun `comma and point are the same decimal separator, and 0,1 + 0,2 style traps do not exist`() {
        assertEquals(1_500, check(fuelForm(amount = "1,5")).entry!!.amountMl)
        assertEquals(1_500, check(fuelForm(amount = "1.5")).entry!!.amountMl)
        assertEquals(10_030, check(fuelForm(amount = "10.03")).entry!!.amountMl)
        assertEquals(290, check(fuelForm(amount = "0,29")).entry!!.amountMl)
        assertEquals(1_010, check(fuelForm(cost = "10,1")).entry!!.costCents)
        assertEquals(1_010, check(fuelForm(cost = "10.10")).entry!!.costCents)
        assertEquals(2_743, check(fuelForm(cost = "27,43")).entry!!.costCents)
        assertEquals("19,99 sind 1999 Cent, nicht 1998", 1_999, check(fuelForm(cost = "19.99")).entry!!.costCents)
    }

    @Test
    fun `an empty cost is allowed and stays empty`() {
        assertNull(check(fuelForm(cost = "")).entry!!.costCents)
        assertNull(check(fuelForm(cost = "   ")).entry!!.costCents)
    }

    @Test
    fun `the note is trimmed, an empty one is none`() {
        assertEquals("Shell", check(fuelForm(note = "  Shell  ")).entry!!.note)
        assertNull(check(fuelForm(note = "   ")).entry!!.note)
    }

    @Test
    fun `a partial fill is a fill with the box off, other kinds have no such state`() {
        assertEquals(false, check(fuelForm(full = false)).entry!!.fullTank)
        assertEquals(true, check(fuelForm(full = true)).entry!!.fullTank)
        val oil = LogForm(type = LogType.OIL, date = today, odometer = "1000", amount = "150", fullTank = true)
        assertNull("bei Oel gibt es das nicht, auch wenn der Schalter noch an ist", check(oil).entry!!.fullTank)
    }

    // --- fehlende und ungueltige Eingaben ---

    @Test
    fun `an empty odometer or amount is missing, not invalid`() {
        val result = check(fuelForm(odometer = "", amount = ""))

        assertEquals(FormError.Missing(FormField.ODOMETER), result.error(FormField.ODOMETER))
        assertEquals(FormError.Missing(FormField.AMOUNT), result.error(FormField.AMOUNT))
        assertNull(result.entry)
    }

    @Test
    fun `text that is not a number is invalid`() {
        val result = check(fuelForm(odometer = "40k", amount = "viel", cost = "teuer"))

        assertEquals(FormError.Invalid(FormField.ODOMETER), result.error(FormField.ODOMETER))
        assertEquals(FormError.Invalid(FormField.AMOUNT), result.error(FormField.AMOUNT))
        assertEquals(FormError.Invalid(FormField.COST), result.error(FormField.COST))
    }

    @Test
    fun `more decimals than the unit has, a thousands separator or a sign is invalid - nothing is rounded silently`() {
        assertEquals("Liter haben die Aufloesung 1 ml, also drei Stellen", 14_371, check(fuelForm(amount = "14,371")).entry!!.amountMl)
        assertEquals(FormError.Invalid(FormField.AMOUNT), check(fuelForm(amount = "14,3711")).error(FormField.AMOUNT))
        assertEquals(FormError.Invalid(FormField.COST), check(fuelForm(cost = "27,431")).error(FormField.COST))
        assertEquals(FormError.Invalid(FormField.COST), check(fuelForm(cost = "1.000,00")).error(FormField.COST))
        assertEquals(FormError.Invalid(FormField.ODOMETER), check(fuelForm(odometer = "-5")).error(FormField.ODOMETER))
        assertEquals(FormError.Invalid(FormField.ODOMETER), check(fuelForm(odometer = "40.362")).error(FormField.ODOMETER))
        assertEquals(FormError.Invalid(FormField.AMOUNT), check(fuelForm(amount = "-1")).error(FormField.AMOUNT))
    }

    @Test
    fun `the amount of oil and coolant is whole milliliters`() {
        fun oil(amount: String) = LogForm(type = LogType.OIL, date = today, odometer = "1000", amount = amount)

        assertEquals(150, check(oil("150")).entry!!.amountMl)
        assertEquals("ml mit Komma ist vermutlich ein Liter-Wert: lieber abweisen als raten", FormError.Invalid(FormField.AMOUNT), check(oil("1,5")).error(FormField.AMOUNT))
        assertEquals(5_000, check(oil("5000")).entry!!.amountMl)
        assertEquals(FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange), check(oil("5001")).error(FormField.AMOUNT))
        assertEquals(FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange), check(oil("0")).error(FormField.AMOUNT))
    }

    // --- Regeln des Logbuchs ---

    @Test
    fun `the rules of the logbook end up at the field they belong to`() {
        assertEquals(FormError.Rule(FormField.ODOMETER, LogIssue.OdometerRange), check(fuelForm(odometer = "1000000")).error(FormField.ODOMETER))
        assertNull(check(fuelForm(odometer = "999999")).error(FormField.ODOMETER))
        assertEquals(FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange), check(fuelForm(amount = "100,01")).error(FormField.AMOUNT))
        assertNull(check(fuelForm(amount = "100")).error(FormField.AMOUNT))
        assertEquals(FormError.Rule(FormField.COST, LogIssue.CostRange), check(fuelForm(cost = "1000")).error(FormField.COST))
        assertEquals(FormError.Rule(FormField.COST, LogIssue.CostRange), check(fuelForm(cost = "0")).error(FormField.COST))
        assertEquals(FormError.Rule(FormField.DATE, LogIssue.DateInFuture), check(fuelForm(date = today.plusDays(1))).error(FormField.DATE))
        assertNull(check(fuelForm(date = today)).error(FormField.DATE))
        assertEquals(FormError.Rule(FormField.NOTE, LogIssue.NoteTooLong), check(fuelForm(note = "x".repeat(201))).error(FormField.NOTE))
        assertNull(check(fuelForm(note = "x".repeat(200))).error(FormField.NOTE))
    }

    @Test
    fun `a number too big for an int is out of range, not a crash`() {
        val result = check(fuelForm(odometer = "99999999999", amount = "99999999999", cost = "99999999999"))

        assertEquals(FormError.Rule(FormField.ODOMETER, LogIssue.OdometerRange), result.error(FormField.ODOMETER))
        assertEquals(FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange), result.error(FormField.AMOUNT))
        assertEquals(FormError.Rule(FormField.COST, LogIssue.CostRange), result.error(FormField.COST))
    }

    @Test
    fun `a number that would wrap around an int is out of range, it must not come back as a small valid one`() {
        // 4294967301 = 2^32 + 5: ohne Kappung wuerde daraus 5 (gueltig)
        val result = check(fuelForm(odometer = "4294967301", amount = "4294967,301", cost = "42949672,01"))

        assertEquals(FormError.Rule(FormField.ODOMETER, LogIssue.OdometerRange), result.error(FormField.ODOMETER))
        assertEquals(FormError.Rule(FormField.AMOUNT, LogIssue.AmountRange), result.error(FormField.AMOUNT))
        assertEquals(FormError.Rule(FormField.COST, LogIssue.CostRange), result.error(FormField.COST))
    }

    @Test
    fun `a field that cannot be read does not hide the errors of the other fields`() {
        val result = check(fuelForm(odometer = "abc", note = "x".repeat(201)))

        assertEquals(FormError.Invalid(FormField.ODOMETER), result.error(FormField.ODOMETER))
        assertEquals(FormError.Rule(FormField.NOTE, LogIssue.NoteTooLong), result.error(FormField.NOTE))
    }

    @Test
    fun `cost is only for fuel - a cost typed before the kind was changed is ignored, not an error`() {
        val tires = LogForm(type = LogType.TIRE_CHANGE, date = today, odometer = "37000", cost = "199,00", brand = "Heidenau K60")

        val result = check(tires)

        assertEquals(emptyMap<FormField, FormError>(), result.errors)
        assertNull(result.entry!!.costCents)
    }

    @Test
    fun `the brand of a tire change is required, trimmed and at most 80 characters - other kinds ignore it`() {
        fun tires(brand: String) = LogForm(type = LogType.TIRE_CHANGE, date = today, odometer = "37000", brand = brand)

        assertEquals(FormError.Rule(FormField.BRAND, LogIssue.BrandMissing), check(tires("")).error(FormField.BRAND))
        assertEquals(FormError.Rule(FormField.BRAND, LogIssue.BrandMissing), check(tires("   ")).error(FormField.BRAND))
        assertEquals("Heidenau K60", check(tires("  Heidenau K60 ")).entry!!.brand)
        assertNull(check(tires("x".repeat(80))).error(FormField.BRAND))
        assertEquals(FormError.Rule(FormField.BRAND, LogIssue.BrandTooLong), check(tires("x".repeat(81))).error(FormField.BRAND))
        val service = LogForm(type = LogType.SERVICE_SMALL, date = today, odometer = "37000", brand = "uebrig geblieben")
        assertNull("bei einer anderen Art zaehlt ein Rest im Feld nicht", check(service).entry!!.brand)
    }

    @Test
    fun `kinds without an amount need no amount`() {
        for (type in listOf(LogType.CHAIN_SMALL, LogType.CHAIN_LARGE, LogType.SERVICE_SMALL, LogType.SERVICE_LARGE, LogType.CHAIN_KIT_CHANGE)) {
            val result = check(LogForm(type = type, date = today, odometer = "1000", amount = "9999"))
            assertEquals("$type", emptyMap<FormField, FormError>(), result.errors)
            assertNull("$type: eine Menge im Feld wird verworfen", result.entry!!.amountMl)
        }
    }

    // --- Warnungen ---

    @Test
    fun `warnings come with a valid entry - and only then`() {
        val earlier = LogEntry(1, LogType.FUEL, today.minusDays(10), 41_000, 14_000, true, null, null, null)

        val warned = check(fuelForm(odometer = "40362"), others = listOf(earlier))

        assertNotNull("der Eintrag ist da, die Rueckfrage entscheidet", warned.entry)
        assertTrue("$warned", warned.warnings.any { it is LogIssue.OdometerOutOfOrder })

        val broken = check(fuelForm(odometer = "40362", amount = "abc"), others = listOf(earlier))
        assertTrue("mit einem Fehler keine Warnungen", broken.warnings.isEmpty())
    }

    // --- Bearbeiten: vom Eintrag zurueck in die Felder ---

    @Test
    fun `an entry shows in the form the way the user typed it, and checks to the same entry`() {
        val entries = listOf(
            LogEntry(5, LogType.FUEL, today, 40_362, 14_370, true, 2_743, "Shell, Autobahn", null),
            LogEntry(6, LogType.FUEL, today, 40_000, 15_000, false, 1_510, null, null),
            LogEntry(7, LogType.OIL, today, 40_100, 150, null, null, null, null),
            LogEntry(8, LogType.COOLANT, today, 40_100, 50, null, null, "bis Max", null),
            LogEntry(9, LogType.TIRE_CHANGE, today, 37_000, null, null, null, null, "Michelin Anakee Adventure 150/70-18"),
            LogEntry(10, LogType.CHAIN_KIT_CHANGE, today, 36_000, null, null, null, null, null),
            LogEntry(11, LogType.CHAIN_SMALL, today, 35_000, null, null, null, null, null),
            LogEntry(12, LogType.SERVICE_LARGE, today, 34_000, null, null, null, "Ventile", null),
        )
        for (entry in entries) {
            for (separator in listOf(',', '.')) {
                val result = check(formFromEntry(entry, separator), id = entry.id)
                assertEquals("${entry.type} mit '$separator': ${result.errors}", entry, result.entry)
            }
        }
    }

    @Test
    fun `an entry whose amount is not a multiple of 10 ml keeps its third decimal - editing never changes it silently`() {
        val entry = LogEntry(5, LogType.FUEL, today, 40_362, 14_375, true, 2_743, null, null)

        val form = formFromEntry(entry, ',')

        assertEquals("14,375", form.amount)
        assertEquals(entry, check(form, id = 5).entry)
    }

    @Test
    fun `the form of a fuel entry carries two decimals with the locale separator`() {
        val form = formFromEntry(LogEntry(5, LogType.FUEL, today, 40_362, 15_000, true, 1_510, null, null), ',')

        assertEquals("15,00", form.amount)
        assertEquals("15,10", form.cost)
        assertEquals("40362", form.odometer)
    }

    // --- Preis je Liter ---

    @Test
    fun `the price per liter is cost divided by liters, rounded to the cent`() {
        assertEquals(191, pricePerLiterCents(costCents = 2_743, amountMl = 14_370))
        assertEquals(150, pricePerLiterCents(costCents = 1_500, amountMl = 10_000))
        assertNull(pricePerLiterCents(costCents = 1_500, amountMl = 0))
    }

    // --- das oberste Feld mit Fehler ---

    @Test
    fun `the first field with an error is the topmost one on the page, not the first of the enum`() {
        val errors = mapOf<FormField, FormError>(
            FormField.NOTE to FormError.Rule(FormField.NOTE, LogIssue.NoteTooLong),
            FormField.BRAND to FormError.Missing(FormField.BRAND),
        )

        // Auf der Seite steht das Fabrikat ueber der Notiz, in der Enum steht NOTE vor BRAND.
        assertEquals(FormField.BRAND, firstFieldWithError(errors))
        assertEquals(FormField.ODOMETER, firstFieldWithError(errors + (FormField.ODOMETER to FormError.Invalid(FormField.ODOMETER))))
        assertEquals(FormField.DATE, firstFieldWithError(errors + (FormField.DATE to FormError.Rule(FormField.DATE, LogIssue.DateInFuture))))
    }

    @Test
    fun `without an error there is no field to scroll to`() {
        assertNull(firstFieldWithError(emptyMap()))
    }

    @Test
    fun `every field has a place in the page order, so none can be skipped`() {
        assertEquals(FormField.entries.toSet(), FORM_FIELD_ORDER.toSet())
        assertEquals(FormField.entries.size, FORM_FIELD_ORDER.size)
    }
}
