package de.adv990de.obd2.transfer

import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.tripimport.TripImportPlan

/** Die gewaehlte Datei ist keine Sicherung dieser App und keine CSV, die sich einlesen laesst ([kind] sagt, warum). */
class TransferFileException(val kind: Kind, message: String) : IllegalArgumentException(message) {
    enum class Kind {
        /** Ein ZIP ohne gueltiges Manifest dieser App (oder beschaedigt). */
        NOT_A_BACKUP,

        /** Die CSV des App-Logs: ausgeben ja, einlesen nein. */
        APP_LOG_NOT_IMPORTABLE,

        /** Weder Paket noch eine der CSV-Arten dieser App. */
        UNRECOGNIZED,
    }
}

/** Warum ein Teil eines Pakets nicht gelesen werden konnte (die anderen Teile sind davon unabhaengig). */
enum class UnreadableReason { FORMAT, TOO_LARGE, UNREADABLE }

/**
 * Was `trips_meta.csv` zu den **neuen** Fahrten sagt: [withVin] und [withCodesRecorded] zaehlen Fahrten, [invalidVins] verworfene FIN (die Fahrt bleibt), [orphanRows] Zeilen ohne neue
 * Fahrt (nicht in `trips.csv` oder die Fahrt ist schon in der App oder zu alt: verworfen), [unreadable] = die Datei liess sich nicht lesen (die Fahrten kommen ohne Angaben).
 */
data class TripMetaSummary(val withVin: Int, val withCodesRecorded: Int, val invalidVins: Int, val orphanRows: Int, val unreadable: Boolean)

/** Was ein Import je Teil tun wuerde (die Vorschau); nichts davon ist schon geschrieben. */
sealed interface PartPlan {
    data class Trips(val plan: TripImportPlan, val meta: TripMetaSummary = TripMetaSummary(0, 0, 0, 0, false)) : PartPlan
    data class Logbook(val plan: ImportPlan) : PartPlan
    data class DtcHistory(val plan: DtcHistoryPlan) : PartPlan
    data class Unreadable(val reason: UnreadableReason) : PartPlan
}

/** Hat der Teil etwas, das ein Import schreiben wuerde? ([PartPlan.Unreadable] nie.) */
val PartPlan.hasNews: Boolean
    get() = when (this) {
        is PartPlan.Trips -> plan.trips.isNotEmpty()
        is PartPlan.Logbook -> plan.toInsert.isNotEmpty()
        is PartPlan.DtcHistory -> plan.newCodes + plan.updatedCodes > 0
        is PartPlan.Unreadable -> false
    }

data class TransferPreview(
    /** [FileKind.PACKAGE] oder die CSV-Art. */
    val kind: FileKind,
    /** Das Manifest nennt ein neueres Format, als diese Version kennt. */
    val newerFormat: Boolean,
    /** Das Paket enthaelt `applog.csv`: es wird nicht eingelesen. */
    val ignoredAppLog: Boolean,
    /** In [DataPart.IMPORT_ORDER]. */
    val parts: Map<DataPart, PartPlan>,
)

sealed interface PartOutcome {
    data class Done(val count: Int, val skipped: Int = 0) : PartOutcome
    data class Failed(val cause: Throwable) : PartOutcome
}

/** Nur die verarbeiteten Teile, in [DataPart.IMPORT_ORDER]. */
data class TransferResult(val outcomes: Map<DataPart, PartOutcome>)
