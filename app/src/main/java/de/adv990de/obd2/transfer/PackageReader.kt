package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.FileTooLargeException
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Die gewählte Datei ist kein Paket dieser App (keine ZIP-Datei, beschädigt oder zu viele Dateien darin). */
class NotAPackageException(message: String) : IllegalArgumentException(message)

/**
 * Liest ein Paket **ohne etwas zu entpacken**: nur die bekannten Namen ([PackageFormat.KNOWN_ENTRIES], exakter Vergleich) werden je geöffnet, ein Pfad aus dem Paket wird nie
 * als Datei benutzt (kein „Zip Slip“); Verzeichnisse und alles Unbekannte sind unsichtbar. Bei doppeltem Namen gilt der erste. Die Gesamtgrenze gilt über alle geöffneten
 * Einträge zusammen und zählt, was wirklich entpackt wird, nicht die Angabe im Kopf.
 */
class PackageReader private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    private val duplicates: Set<String>,
    private val file: File,
    maxTotalBytes: Long,
    private val limitOf: (String) -> Long,
) : Closeable {

    private var remaining = maxTotalBytes

    companion object {
        /** [maxTotalBytes] und [limitOf] sind nur Nähte für Tests. */
        fun open(
            file: File,
            maxEntries: Int = PackageFormat.MAX_ENTRIES,
            maxTotalBytes: Long = PackageFormat.MAX_TOTAL_BYTES,
            limitOf: (String) -> Long = PackageFormat::entryLimit,
        ): PackageReader {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw NotAPackageException("not a zip file: ${e.message}")
            } catch (e: IOException) {
                throw NotAPackageException("cannot read the file: ${e.message}")
            }
            try {
                val known = LinkedHashMap<String, ZipEntry>()
                val duplicates = HashSet<String>()
                var count = 0
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    if (++count > maxEntries) throw NotAPackageException("too many files in the zip")
                    if (!entry.isDirectory && entry.name in PackageFormat.KNOWN_ENTRIES) if (known.putIfAbsent(entry.name, entry) != null) duplicates += entry.name
                }
                return PackageReader(zip, known, duplicates, file, maxTotalBytes, limitOf)
            } catch (e: NotAPackageException) {
                zip.close()
                throw e
            } catch (e: ZipException) {
                zip.close()
                throw NotAPackageException("damaged zip file: ${e.message}")
            } catch (e: IOException) {
                zip.close()
                throw NotAPackageException("cannot read the zip file: ${e.message}")
            }
        }
    }

    /** Nur bekannte, gewöhnliche Dateien. */
    fun has(name: String): Boolean = name in entries

    /**
     * `null`, wenn der Eintrag fehlt. Der Aufrufer schließt den Strom. Er liefert höchstens `limitOf(name)` Bytes und höchstens den Rest der Gesamtgrenze; mehr wirft
     * [FileTooLargeException].
     */
    fun openEntry(name: String): InputStream? {
        val entry = entries[name] ?: return null
        // ZipFile.getInputStream löst über den Namen auf (bei doppeltem Namen gewänne der letzte): dann der erste, der Reihe nach gelesen.
        val input = if (name in duplicates) firstEntryStream(name) else zip.getInputStream(entry)
        return LimitedEntryStream(input, name, limitOf(name))
    }

    private fun firstEntryStream(name: String): InputStream {
        val sequential = ZipInputStream(file.inputStream().buffered())
        try {
            while (true) {
                val next = sequential.nextEntry ?: throw IOException("entry $name vanished")
                if (!next.isDirectory && next.name == name) return sequential
            }
        } catch (e: Throwable) {
            sequential.close()
            throw e
        }
    }

    /** `null` = fehlt oder nicht lesbar (→ „keine Sicherung dieser App“). */
    fun manifest(): PackageManifest? = try {
        openEntry(PackageFormat.MANIFEST)?.use { parseManifest(String(it.readBytes(), Charsets.UTF_8)) }
    } catch (_: IOException) {
        null
    }

    override fun close() = zip.close()

    private inner class LimitedEntryStream(input: InputStream, private val name: String, private val limit: Long) : FilterInputStream(input) {
        private var read = 0L

        private fun allowed(): Long = minOf(limit - read, remaining)

        private fun tooLarge(): Nothing = throw FileTooLargeException("$name is larger than allowed")

        override fun read(): Int {
            if (allowed() <= 0) {
                if (`in`.read() < 0) return -1
                tooLarge()
            }
            val b = `in`.read()
            if (b >= 0) {
                read++
                remaining--
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val room = allowed()
            val n = `in`.read(b, off, minOf(len.toLong(), maxOf(room, 0) + 1).toInt())
            if (n < 0) return -1
            if (n > room) tooLarge()
            read += n
            remaining -= n
            return n
        }

        override fun skip(n: Long): Long {
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported(): Boolean = false
    }
}
