package de.adv990de.obd2.logbook

import java.math.BigDecimal
import java.time.LocalDate

/** Die Felder des Formulars, an denen ein Fehler stehen kann. */
enum class FormField { DATE, ODOMETER, AMOUNT, COST, NOTE, BRAND }

/** Die Reihenfolge, in der die Felder auf der Eintragsseite stehen, von oben nach unten (nicht die der Enum: das Fabrikat steht ueber der Notiz). */
val FORM_FIELD_ORDER = listOf(FormField.DATE, FormField.ODOMETER, FormField.AMOUNT, FormField.COST, FormField.BRAND, FormField.NOTE)

/** Das oberste Feld der Seite, an dem ein Fehler steht — dorthin scrollt die Seite nach einem fehlgeschlagenen Speichern; null ohne Fehler. */
fun firstFieldWithError(errors: Map<FormField, FormError>): FormField? = FORM_FIELD_ORDER.firstOrNull { it in errors }

/** Was an einem Feld nicht stimmt: es fehlt ([Missing]), es ist keine Zahl der erwarteten Form ([Invalid]) oder eine Regel des Logbuchs ist verletzt ([Rule]). */
sealed interface FormError {
    val field: FormField

    data class Missing(override val field: FormField) : FormError
    data class Invalid(override val field: FormField) : FormError
    data class Rule(override val field: FormField, val issue: LogIssue.Error) : FormError
}

/**
 * Die getippten Texte des Formulars, so wie das Feld sie hat. Aus ihnen wird mit [checkForm] ein [LogEntry] oder ein Fehler je Feld. [amount] sind Liter beim
 * Tanken (Komma oder Punkt, bis zu drei Nachkommastellen) und ganze Milliliter bei Oel und Kuehlfluessigkeit; [cost] Euro (zwei Nachkommastellen). Felder, die die
 * gewaehlte Art nicht hat, werden ignoriert — ein Rest vom vorigen Tippen ist kein Fehler.
 */
data class LogForm(
    val type: LogType = LogType.FUEL,
    val date: LocalDate,
    val odometer: String = "",
    val amount: String = "",
    val cost: String = "",
    val fullTank: Boolean = true,
    val note: String = "",
    val brand: String = "",
)

/**
 * Das Ergebnis von [checkForm]. [entry] gibt es nur, wenn kein Feld einen Fehler hat; [warnings] nur dann auch (Warnungen ueber ungueltige Werte waeren sinnlos)
 * und brauchen die Rueckfrage "Trotzdem speichern?".
 */
data class FormCheck(val entry: LogEntry?, val errors: Map<FormField, FormError>, val warnings: List<LogIssue.Warning>)

private val DECIMAL_LITERS = Regex("""\d+([.,]\d{1,3})?""")
private val DECIMAL_EUROS = Regex("""\d+([.,]\d{1,2})?""")
private val WHOLE_NUMBER = Regex("""\d+""")

private sealed interface Parsed {
    data class Ok(val value: Int) : Parsed
    data object Empty : Parsed
    data object Bad : Parsed
}

/** Eine zu grosse Zahl wird auf [Int.MAX_VALUE] gekappt: sie liegt dann ausserhalb jedes Bereichs und die Regeln lehnen sie ab, statt dass es abstuerzt. */
private fun BigDecimal.toIntClamped(): Int = toBigInteger().let { if (it > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE else it.toInt() }

private fun parseWhole(text: String): Parsed {
    val t = text.trim()
    if (t.isEmpty()) return Parsed.Empty
    if (!WHOLE_NUMBER.matches(t)) return Parsed.Bad
    return Parsed.Ok(BigDecimal(t).toIntClamped())
}

/** Eine Dezimalzahl ([pattern]) mit Punkt oder Komma, um [scale] Stellen verschoben (Liter → ml: 3, Euro → Cent: 2); ohne Rundung. */
private fun parseDecimal(text: String, pattern: Regex, scale: Int): Parsed {
    val t = text.trim()
    if (t.isEmpty()) return Parsed.Empty
    if (!pattern.matches(t)) return Parsed.Bad
    return Parsed.Ok(BigDecimal(t.replace(',', '.')).movePointRight(scale).toIntClamped())
}

private fun fieldOf(error: LogIssue.Error): FormField = when (error) {
    LogIssue.OdometerRange -> FormField.ODOMETER
    LogIssue.AmountMissing, LogIssue.AmountRange -> FormField.AMOUNT
    LogIssue.DateInFuture -> FormField.DATE
    LogIssue.CostRange -> FormField.COST
    LogIssue.NoteTooLong -> FormField.NOTE
    LogIssue.BrandMissing, LogIssue.BrandTooLong, LogIssue.BrandNotAllowed -> FormField.BRAND
}

/**
 * Liest und prueft das Formular. [id] ist die des bearbeiteten Eintrags (0 beim neuen), [others] die uebrigen Eintraege (ohne den bearbeiteten) fuer die
 * Warnungen, [today] kommt vom Aufrufer. Ein Feld, das sich nicht lesen laesst, versteckt die Fehler der uebrigen Felder nicht (der Nutzer sieht alles auf einmal).
 */
fun checkForm(form: LogForm, id: Long, others: List<LogEntry>, today: LocalDate): FormCheck {
    val type = form.type
    val errors = LinkedHashMap<FormField, FormError>()

    val odometer = parseWhole(form.odometer)
    when (odometer) {
        Parsed.Empty -> errors[FormField.ODOMETER] = FormError.Missing(FormField.ODOMETER)
        Parsed.Bad -> errors[FormField.ODOMETER] = FormError.Invalid(FormField.ODOMETER)
        is Parsed.Ok -> Unit
    }

    val amount = when {
        !type.hasAmount -> null
        type == LogType.FUEL -> parseDecimal(form.amount, DECIMAL_LITERS, scale = 3)
        else -> parseWhole(form.amount)
    }
    when (amount) {
        Parsed.Empty -> errors[FormField.AMOUNT] = FormError.Missing(FormField.AMOUNT)
        Parsed.Bad -> errors[FormField.AMOUNT] = FormError.Invalid(FormField.AMOUNT)
        else -> Unit
    }

    val cost = if (type == LogType.FUEL) parseDecimal(form.cost, DECIMAL_EUROS, scale = 2) else Parsed.Empty
    if (cost == Parsed.Bad) errors[FormField.COST] = FormError.Invalid(FormField.COST)

    // Mit Platzhaltern fuer die unlesbaren Felder pruefen, damit die Regeln der uebrigen Felder trotzdem greifen; ihre Fehler werden danach verworfen.
    val candidate = LogEntry(
        id = id,
        type = type,
        date = form.date,
        odometerKm = (odometer as? Parsed.Ok)?.value ?: 0,
        amountMl = if (type.hasAmount) (amount as? Parsed.Ok)?.value ?: 1 else null,
        fullTank = if (type == LogType.FUEL) form.fullTank else null,
        costCents = (cost as? Parsed.Ok)?.value,
        note = form.note.trim().ifEmpty { null },
        brand = if (type.hasBrand) form.brand.trim().ifEmpty { null } else null,
    )
    val issues = validate(candidate, others, today)
    for (error in issues.filterIsInstance<LogIssue.Error>()) {
        val field = fieldOf(error)
        if (field !in errors) errors[field] = FormError.Rule(field, error)
    }
    if (errors.isNotEmpty()) return FormCheck(null, errors, emptyList())
    return FormCheck(candidate, emptyMap(), issues.filterIsInstance<LogIssue.Warning>())
}

private fun formatScaled(value: Int, scale: Int, minDecimals: Int, separator: Char): String {
    val text = BigDecimal.valueOf(value.toLong(), scale).toPlainString()
    val (whole, fraction) = if ('.' in text) text.substringBefore('.') to text.substringAfter('.') else text to ""
    val trimmed = fraction.trimEnd('0').padEnd(minDecimals, '0')
    return if (trimmed.isEmpty()) whole else "$whole$separator$trimmed"
}

/**
 * Der Eintrag als Formular zum Bearbeiten, so wie der Nutzer ihn tippen wuerde: Liter mit mindestens zwei Nachkommastellen (eine dritte nur, wenn die Menge kein Vielfaches
 * von 10 ml ist — ein Bearbeiten aendert nie still den Wert), Euro mit zwei, jeweils mit dem Dezimaltrennzeichen [decimalSeparator] des Geraets.
 */
fun formFromEntry(entry: LogEntry, decimalSeparator: Char): LogForm = LogForm(
    type = entry.type,
    date = entry.date,
    odometer = entry.odometerKm.toString(),
    amount = entry.amountMl?.let {
        if (entry.type == LogType.FUEL) formatScaled(it, 3, 2, decimalSeparator) else it.toString()
    }.orEmpty(),
    cost = entry.costCents?.let { formatScaled(it, 2, 2, decimalSeparator) }.orEmpty(),
    fullTank = entry.fullTank ?: true,
    note = entry.note.orEmpty(),
    brand = entry.brand.orEmpty(),
)

/** Preis je Liter in Cent, auf den Cent gerundet (ganzzahlig gerechnet, wie die Warnung in [validate]); `null` ohne Menge. */
fun pricePerLiterCents(costCents: Int, amountMl: Int): Int? {
    if (amountMl <= 0) return null
    val ml = amountMl.toLong()
    return ((costCents.toLong() * 1000 + ml / 2) / ml).toInt()
}
