package de.adv990de.obd2.logbook

import java.time.LocalDate

/** Der Stichtag der Logbuch-Tests ("heute"). */
internal val TEST_TODAY: LocalDate = LocalDate.of(2026, 10, 20)

/** Ein gueltiger Eintrag; die Vorgaben passen zur Art (Menge nur bei Tanken/Oel/Kuehlfluessigkeit, "voll" nur beim Tanken, Fabrikat nur beim Reifenwechsel). */
internal fun logEntry(
    type: LogType = LogType.FUEL,
    date: LocalDate = TEST_TODAY,
    km: Int = 1_000,
    amountMl: Int? = if (type.hasAmount) 10_000 else null,
    fullTank: Boolean? = if (type == LogType.FUEL) true else null,
    costCents: Int? = null,
    note: String? = null,
    brand: String? = if (type.hasBrand) "Michelin Anakee" else null,
    id: Long = 0,
) = LogEntry(id, type, date, km, amountMl, fullTank, costCents, note, brand)
