package de.adv990de.obd2.data

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Kopiert die fertig geschriebene Exportdatei [file] in die Datei, die der Nutzer mit dem Dateidialog des Systems gewaehlt hat ([target], von
 * `CreateDocument`). Der gemeinsame Schritt von "Speichern" bei den Fahrten und beim App-Log: erst wird in den Cache geschrieben und erst bei Erfolg
 * kopiert, ein Lesefehler fasst das Ziel also nie an. Ein Ziel, das sich nicht zum Schreiben oeffnen laesst, ist eine [IOException]. Das Loeschen der
 * Cache-Datei bleibt beim Aufrufer (`finally`), damit es auch bei einem Fehler hier geschieht.
 */
internal fun copyToTarget(resolver: ContentResolver, file: File, target: Uri) {
    val out = resolver.openOutputStream(target, "w") ?: throw IOException("The chosen file could not be opened for writing")
    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
}
