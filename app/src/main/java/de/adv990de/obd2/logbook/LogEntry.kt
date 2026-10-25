package de.adv990de.obd2.logbook

import java.time.LocalDate

/**
 * Ein Eintrag des Logbuchs (Tanken, Oel, Kuehlfluessigkeit, Kettenpflege, Service, Reifen- und Kettenkit-Wechsel) — rein, ohne Datenbank- und Android-Bezug.
 *
 * Gespeichert wird in ganzen Einheiten, nie in Fliesskomma: [amountMl] (Liter × 1000, nur Tanken/Oel/Kuehlfluessigkeit), [costCents] (Euro × 100,
 * nur Tanken), das Datum als Tag ([date], keine Uhrzeit). [fullTank] gibt es nur beim Tanken (`null` = nicht gesagt, gilt wie "voll"; `false` = Teiltankung).
 * [brand] ist das Fabrikat des Reifenwechsels (Pflicht dort, sonst `null`). [odometerKm] ist der Tachostand, vom Nutzer getippt (die App kennt keinen). [id] ist 0 bis zum ersten Speichern.
 */
data class LogEntry(
    val id: Long = 0,
    val type: LogType,
    val date: LocalDate,
    val odometerKm: Int,
    val amountMl: Int?,
    val fullTank: Boolean?,
    val costCents: Int?,
    val note: String?,
    val brand: String?,
)
