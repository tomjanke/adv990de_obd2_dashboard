package de.adv990de.obd2.transfer

enum class ExportFormat { PACKAGE, CSV }

/** Die Arten, die es auch als einzelne CSV-Datei gibt. */
val CSV_PARTS: Set<DataPart> = setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG)

/** Die eine Art, wenn genau sie (und nichts sonst) gewählt ist und es sie als CSV gibt (D6), sonst `null`. */
fun csvPartOf(parts: Set<DataPart>): DataPart? = parts.singleOrNull()?.takeIf { it in CSV_PARTS }

/** CSV nur, wenn [csvPartOf] eine Art liefert, sonst immer das Paket. */
fun effectiveFormat(parts: Set<DataPart>, wanted: ExportFormat): ExportFormat =
    if (wanted == ExportFormat.CSV && csvPartOf(parts) != null) ExportFormat.CSV else ExportFormat.PACKAGE
