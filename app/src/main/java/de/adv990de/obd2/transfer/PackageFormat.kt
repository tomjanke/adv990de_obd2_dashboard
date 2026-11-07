package de.adv990de.obd2.transfer

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Das Paketformat 1: die Namen der Dateien, die Obergrenzen und der Dateiname des Pakets. Die Namen sind ein Vertrag ab dem ersten Release — ein späteres Format
 * ändert sie nie, es erhöht [VERSION].
 */
object PackageFormat {
    const val VERSION = 1

    /** Fest, nicht `BuildConfig.APPLICATION_ID`: Debug- und Release-App tauschen Pakete aus (P7). */
    const val APP_ID = "de.adv990de.obd2"
    const val MIME = "application/zip"
    const val MANIFEST = "manifest.json"

    /** Gehört zu den Fahrten (die FIN je Fahrt). */
    const val TRIPS_META = "trips_meta.csv"

    val KNOWN_ENTRIES: Set<String> = setOf(MANIFEST, TRIPS_META) + DataPart.entries.map { it.packageEntry }

    private const val KIB = 1024L
    private const val MIB = 1024L * KIB

    /** Entpackt insgesamt höchstens so viel (P3). */
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    /** Dateien im ZIP (nicht Fahrten oder Logbuch-Einträge). */
    const val MAX_ENTRIES = 100

    /** Die Obergrenze je bekannter Datei; ein unbekannter Name hat keine (0). */
    fun entryLimit(name: String): Long = when (name) {
        MANIFEST, DataPart.SETTINGS.packageEntry, DataPart.COUNTERS.packageEntry, DataPart.DTC_HISTORY.packageEntry -> 256 * KIB
        TRIPS_META -> 4 * MIB
        DataPart.TRIPS.packageEntry -> 96 * MIB
        DataPart.LOGBOOK.packageEntry -> 5 * MIB
        DataPart.APP_LOG.packageEntry -> MAX_TOTAL_BYTES
        else -> 0L
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

    /** `obd-dashboard_<UTC-Zeit auf die Millisekunde>.zip`. */
    fun fileName(nowMillis: Long): String = "obd-dashboard_${TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis))}.zip"
}
