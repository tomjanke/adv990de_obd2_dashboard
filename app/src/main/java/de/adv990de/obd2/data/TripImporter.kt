package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.planTripImport
import de.adv990de.obd2.tripimport.readTripCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import de.adv990de.obd2.transfer.PackageFormat
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * Die groesste Datei, die der Fahrten-Import liest (256 MiB, [PackageFormat.MAX_TRIPS_BYTES]: Platz fuer die Hoechstzahl der Zeilen; 224 774 Zeilen waren 9,7 MiB),
 * alles darueber ist die falsche Datei. Die Grenze der Zeilenzahl ([de.adv990de.obd2.tripimport.tripImportRowLimit]) greift in der Regel zuerst.
 */
internal const val MAX_TRIP_IMPORT_BYTES = PackageFormat.MAX_TRIPS_BYTES

/**
 * Ein Eingabestrom, der nach mehr als [maxBytes] Bytes mit [FileTooLargeException] scheitert: gelesen wird hoechstens die Grenze plus ein Byte, nicht die ganze Datei. Gezaehlt
 * wird, was die Quelle liefert (das Aufrufen von `skip` zaehlt mit, `mark`/`reset` gibt es nicht).
 */
internal class LimitedInputStream(source: InputStream, private val maxBytes: Long) : FilterInputStream(source) {
    private var count = 0L

    private fun add(n: Long) {
        if (n <= 0) return
        count += n
        if (count > maxBytes) throw FileTooLargeException("The file is larger than ${maxBytes / (1024 * 1024)} MiB")
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    override fun markSupported(): Boolean = false
    override fun mark(readlimit: Int) = Unit
    override fun reset() = throw IOException("mark/reset not supported")
}

/**
 * Liest eine Fahrten-CSV (das Format des Exports, s. [SessionRepository.exportSessionsToCsv]) und macht daraus in zwei Schritten einen Import: [read] liest und prueft die ganze
 * Datei **im Strom** (der Text steht nie ganz im Speicher) und liefert den [TripImportPlan] (die Vorschau, **es wird nichts geschrieben**), [apply] schreibt ihn erst auf die
 * Bestaetigung des Nutzers, in einer Transaktion (alles oder nichts, s. [SessionRepository.importTrips]).
 *
 * Fehler: eine Quelle, die sich nicht oeffnen laesst, ist eine [IOException], eine Datei ueber [MAX_TRIP_IMPORT_BYTES] eine [FileTooLargeException]; eine Datei, die kein
 * Fahrten-Export ist, eine [de.adv990de.obd2.tripimport.TripFileException] (eine [IllegalArgumentException]); alles Weitere geht unveraendert an den Aufrufer. Der Plan wird gegen
 * die Fahrten **zum Zeitpunkt des Lesens** gebildet: eine Fahrt, die zwischen Vorschau und Bestaetigung aufgezeichnet wird, ist nicht beruecksichtigt (beruehrt sie eine Fahrt der
 * Datei, entstehen zwei Fahrten fuer dieselbe Zeit).
 */
class TripImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SessionRepository,
) {

    /** Die Grenze der Dateigroesse; nur als Naht fuer Tests veraenderbar (eine Datei von 96 MiB baut kein Test). */
    internal var maxBytes: Long = MAX_TRIP_IMPORT_BYTES

    suspend fun read(source: Uri, nowMs: Long): TripImportPlan = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("The chosen file could not be opened for reading")
        input.use { plan(it, nowMs) }
    }

    /** Wie [read], aber aus einem schon geoeffneten Strom (ein Eintrag eines Pakets); der Aufrufer schliesst ihn. */
    suspend fun plan(stream: InputStream, nowMs: Long): TripImportPlan = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val parsed = readTripCsv(InputStreamReader(LimitedInputStream(stream, maxBytes), Charsets.UTF_8), nowMs, checkCancelled = { job?.ensureActive() })
        currentCoroutineContext().ensureActive()
        planTripImport(parsed, repository.sessionSpans(), nowMs)
    }

    /** Schreibt die neuen Fahrten des Plans; liefert ihre Zahl. Ein Fehler laesst die Datenbank unveraendert. */
    suspend fun apply(plan: TripImportPlan): Int {
        if (plan.trips.isEmpty()) return 0
        repository.importTrips(plan.trips)
        return plan.trips.size
    }
}
