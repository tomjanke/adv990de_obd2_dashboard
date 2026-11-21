package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.R
import de.adv990de.obd2.track.GpxLimits
import de.adv990de.obd2.track.GpxTrack
import de.adv990de.obd2.track.TrackImportPlan
import de.adv990de.obd2.track.candidateKeys
import de.adv990de.obd2.track.planTrackImport
import de.adv990de.obd2.track.readGpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Liest eine GPX-Datei und macht daraus in zwei Schritten einen Import: [read] bzw. [plan] liest und prueft die ganze Datei **im Strom** und liefert den [TrackImportPlan]
 * (die Vorschau, **es wird nichts geschrieben**), [apply] schreibt ihn erst auf die Bestaetigung des Nutzers, alles oder nichts ([TrackRepository.importTracks]).
 *
 * Fehler: eine Quelle, die sich nicht oeffnen laesst, ist eine [IOException], eine Datei, die kein GPX ist oder ueber den Grenzen liegt, eine [de.adv990de.obd2.track.GpxFileException]
 * (eine [IllegalArgumentException]). Der Plan gilt fuer die Tracks **zum Zeitpunkt des Lesens**. **Positionen sind sensibel:** nichts davon landet im App-Log.
 */
class TrackImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
) {
    /** Die Grenzen beim Lesen; nur als Naht fuer Tests veraenderbar. */
    internal var limits: GpxLimits = GpxLimits()

    /** Der XML-Leser; die JVM-Tests geben einen eigenen (android.jar hat nur Stubs). */
    internal var newParser: (() -> XmlPullParser)? = null

    /** Der Name einer Spur ohne Namen: „Track 1“ in der Sprache des Geraets. */
    internal var fallbackName: (Int) -> String = { context.getString(R.string.track_import_fallback_name, it) }

    suspend fun read(source: Uri): TrackImportPlan = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("The chosen file could not be opened for reading")
        input.use { plan(it) }
    }

    /** Wie [read], aber aus einem schon geoeffneten Strom (ein Eintrag eines Pakets); der Aufrufer schliesst ihn. */
    suspend fun plan(stream: InputStream): TrackImportPlan = withContext(Dispatchers.IO) {
        val file = newParser?.let { readGpx(stream, limits, it) } ?: readGpx(stream, limits)
        currentCoroutineContext().ensureActive()
        val existing = if (file.tracks.any { it.points.isNotEmpty() }) repository.fingerprints(candidateKeys(file, fallbackName)) else emptyList()
        planTrackImport(file, existing, fallbackName)
    }

    /** Schreibt die neuen Tracks des Plans (ohne die Doppelten); liefert ihre Zahl. Ein Fehler laesst die Datenbank unveraendert. */
    suspend fun apply(plan: TrackImportPlan): Int {
        val fresh = plan.toInsert
        if (fresh.isEmpty()) return 0
        // Die Namen sind schon endgueltig; die Ersatznamen des Repositorys greifen nie.
        return repository.importTracks(fresh.map { GpxTrack(it.name, it.points) }) { "Track $it" }.size
    }
}
