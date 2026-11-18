package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.writeGpx
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Exportiert **einen** Track als GPX 1.1 ([writeGpx]): entweder als teilbare [Uri] ([exportToGpx], fuer das Teilen-Menue) oder in eine Datei, die der Nutzer mit dem Dateidialog gewaehlt
 * hat ([saveGpx]). Wie bei den CSV-Exporten ([CsvExportFiles]): erst in den Cache schreiben und erst bei Erfolg herausgeben, jeder Export in einer eigenen neuen Datei, Exporte aelter als
 * einen Tag werden vorab entfernt, ein Fehler — auch ein Abbruch — hinterlaesst keine halbe Datei.
 *
 * **Die Datei enthaelt die Wege des Nutzers** (jeden aufgezeichneten Punkt samt Zeit und Hoehe, soweit bekannt); wer sie teilt, gibt sie weiter — die Oberflaeche sagt es. Eine laufende
 * Aufzeichnung wird mit den Punkten bis jetzt exportiert. Ein Track, den es nicht mehr gibt, ist eine [IllegalArgumentException], einer ohne Punkt eine [IllegalStateException] (eine GPX-Datei
 * ohne einen Punkt waere eine nutzlose Datei).
 */
class TrackExporter @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: TrackRepository,
) {
    private val files = CsvExportFiles(context)

    /** Writes the track into a new file in the cache and returns a [Uri] the share chooser can hand on (the file provider grants read access). */
    suspend fun exportToGpx(trackId: Long): Uri {
        val track = load(trackId)
        return files.share({ trackFileName(track.name, it) }) { writeGpx(it, track.name, track.points) }
    }

    /**
     * Writes the same GPX into the file the user chose with the system file dialog ([target], from `CreateDocument`). The GPX goes to the cache first and is copied only when it is
     * complete ([copyToTarget]); the cache file is removed afterwards, whatever happened.
     */
    suspend fun saveGpx(target: Uri, trackId: Long) {
        val track = load(trackId)
        files.save(target, { trackFileName(track.name, it) }) { writeGpx(it, track.name, track.points) }
    }

    private class Loaded(val name: String, val points: List<TrackPoint>)

    private suspend fun load(trackId: Long): Loaded {
        val summary = repository.tracks.first().firstOrNull { it.id == trackId }
            ?: throw IllegalArgumentException("The track no longer exists")
        val points = repository.points(trackId)
        check(points.isNotEmpty()) { "The track has no points" }
        return Loaded(summary.name, points)
    }
}
