package de.adv990de.obd2.transfer

import java.io.OutputStream
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Eine Datei im Paket: ihr Name (aus [PackageFormat.KNOWN_ENTRIES]) und was hineinkommt. */
class PackageEntry(val name: String, val write: suspend (Writer) -> Unit)

/**
 * Schreibt [entries] in der gegebenen Reihenfolge als ZIP nach [out] (UTF-8, Deflate, Zeit jedes Eintrags = [createdMs]). [out] wird **nicht** geschlossen, aber der
 * ZIP-Abschluss geschrieben (`finish()`); ein Fehler in einem `write` geht unverändert nach außen.
 */
suspend fun writePackage(out: OutputStream, createdMs: Long, entries: List<PackageEntry>) {
    val zip = ZipOutputStream(out, StandardCharsets.UTF_8)
    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
    // Der Writer schließt den ZIP-Strom nie: nach jedem Eintrag wird nur geleert.
    val writer = zip.writer(StandardCharsets.UTF_8)
    for (entry in entries) {
        zip.putNextEntry(ZipEntry(entry.name).apply { time = createdMs })
        entry.write(writer)
        writer.flush()
        zip.closeEntry()
    }
    zip.finish()
    zip.flush()
}
