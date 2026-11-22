package de.adv990de.obd2.map

import de.adv990de.obd2.transfer.jsonDepthWithin
import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/*
 * Die eingespielte Karten-Datei: eine einzige PMTiles-Datei im App-Ordner. Dieser Teil kennt kein Android (nur Dateien), damit jede Regel mit echten Dateien JVM-getestet ist; die Huelle mit
 * Uri, Dateidialog, Hilt und App-Log ist `de.adv990de.obd2.data.MapRepository`.
 */

/** Was wir ueber die eingespielte Karte wissen. Orte in Grad. [name] und [attribution] stammen aus den Metadaten der Datei und koennen fehlen. */
data class MapInfo(
    val name: String?,
    val attribution: String?,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val bytes: Long,
) {
    /** `true`, wenn der Ort im Rechteck der Karte liegt (die Datei sagt nicht, ob dort wirklich Kacheln sind; das Rechteck ist ihre Ausdehnung). */
    fun covers(latDeg: Double, lonDeg: Double): Boolean = latDeg in minLat..maxLat && lonDeg in minLon..maxLon

    /** `true`, wenn das Rechteck [south], [west], [north], [east] ganz in der Karte liegt. */
    fun covers(south: Double, west: Double, north: Double, east: Double): Boolean = south >= minLat && north <= maxLat && west >= minLon && east <= maxLon

    /** `true`, wenn das Rechteck die Karte beruehrt. */
    fun overlaps(south: Double, west: Double, north: Double, east: Double): Boolean = south <= maxLat && north >= minLat && west <= maxLon && east >= minLon
}

/** Warum eine Datei nicht taugt oder nicht eingespielt werden konnte. */
enum class MapProblem {
    /** Keine PMTiles-Datei Version 3 (oder ein Kopf mit unsinnigen Werten). */
    NOT_PMTILES,

    /** PMTiles, aber keine Vektorkacheln (Rasterbilder) — die koennen wir nicht umfaerben und nicht zeichnen. */
    NOT_VECTOR,

    /** Vektorkacheln, aber in einer Packung, die wir nicht lesen (nur „keine“ und gzip). */
    UNSUPPORTED_PACKING,

    /** Ueber [MAX_MAP_BYTES]. */
    TOO_LARGE,

    /** Nicht genug freier Platz im App-Speicher. */
    NO_SPACE,

    /** Die Quelle liess sich nicht lesen oder die Datei nicht schreiben. */
    UNREADABLE,
}

sealed interface MapState {
    /** Noch nicht gelesen. */
    data object Loading : MapState

    data object NoMap : MapState

    data class Ready(val info: MapInfo) : MapState

    /** Eine Datei liegt da, taugt aber nicht (zum Beispiel beschaedigt). */
    data class Broken(val problem: MapProblem) : MapState
}

sealed interface MapImportResult {
    data class Success(val info: MapInfo) : MapImportResult

    data class Failure(val problem: MapProblem) : MapImportResult
}

/**
 * Die groesste Karten-Datei, die wir annehmen (8 GiB). Meine Schaetzung: eine Region wie Deutschland bis zur Stufe 15 passt vermutlich darunter, gemessen habe ich es nicht; die Grenze
 * schuetzt vor der falschen Datei (etwa dem ganzen Planeten), nicht vor dem Fassungsvermoegen des Telefons — dafuer gibt es die Pruefung des freien Platzes.
 */
const val MAX_MAP_BYTES = 8L * 1024 * 1024 * 1024

/** So viel freier Platz bleibt mindestens uebrig, wenn eine Karte eingespielt wird. */
const val MAP_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

/** Der Dateiname im App-Ordner — **persistierter Zustand, nie umbenennen**. */
const val MAP_FILE_NAME = "map.pmtiles"

private const val TEMP_FILE_NAME = "map.pmtiles.tmp"
private const val COPY_BUFFER_BYTES = 1024 * 1024

/** Eine [RandomAccessSource] auf einer Datei; Lesen ist ueber einen Kanal mit fester Lage unabhaengig von anderen Lesern. [close] gibt die Datei frei, danach liefert [read] `null`. */
class FileSource(file: File) : RandomAccessSource, Closeable {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    override suspend fun read(offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (offset < 0 || length < 0) return@withContext null
        try {
            // Die Aufrufer begrenzen [length] (hoechstens eine Kachel oder ein Verzeichnis); ein Bereich ausserhalb der Datei endet am Dateiende mit `null`.
            val buffer = ByteBuffer.allocate(length)
            var position = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n < 0) return@withContext null
                position += n
            }
            buffer.array()
        } catch (e: IOException) {
            null // auch eine geschlossene Datei (ClosedChannelException ist eine IOException)
        }
    }

    override fun close() {
        try { channel.close() } catch (_: IOException) { }
    }
}

/**
 * Verwaltet die Karten-Datei in [dir]: [import] kopiert eine Quelle hinein (ueber eine Zwischendatei, geprueft, dann ersetzt — eine kaputte Quelle laesst die alte Karte stehen),
 * [inspect] liest, was dort liegt, [archive] liefert den Leser fuer den Zeichner, [remove] loescht. [usableSpace] und [maxBytes] sind Nahtstellen fuer Tests.
 */
class MapFileStore(
    private val dir: File,
    // Nur eine untere Schranke: die App behauptet nie mehr freien Platz, als da ist; sie raeumt keine fremden Zwischenspeicher (getAllocatableBytes) und braucht das nicht.
    @SuppressLint("UsableSpace")
    private val usableSpace: () -> Long = { dir.usableSpace },
    private val maxBytes: Long = MAX_MAP_BYTES,
) {
    private val file get() = File(dir, MAP_FILE_NAME)
    private val tempFile get() = File(dir, TEMP_FILE_NAME)

    /** Ein Einspielen oder Loeschen zugleich; das Kopieren dauert Minuten, der Zeichner darf dabei nicht warten (er nutzt [cacheLock]). */
    private val changeLock = Mutex()
    private val cacheLock = Mutex()
    private var cached: Pair<FileSource, PmTilesArchive>? = null

    /** Der Zustand der Datei im Ordner: keine, brauchbar oder kaputt. */
    suspend fun inspect(): MapState = withContext(Dispatchers.IO) {
        if (!file.isFile) MapState.NoMap else inspectFile(file)
    }

    /**
     * Kopiert [open] in den Kartenordner. [knownSize] ist die Groesse der Quelle, wenn sie sie nennt (dann entscheidet sie schon vorher ueber [MapProblem.TOO_LARGE] und
     * [MapProblem.NO_SPACE]); sonst zaehlt, was gelesen wird. Ein Abbruch der Coroutine raeumt die halbe Kopie auf.
     */
    suspend fun import(open: () -> InputStream?, knownSize: Long? = null): MapImportResult = changeLock.withLock {
        withContext(Dispatchers.IO) {
            if (knownSize != null) {
                if (knownSize > maxBytes) return@withContext MapImportResult.Failure(MapProblem.TOO_LARGE)
                if (knownSize + MAP_SPACE_MARGIN_BYTES > usableSpace()) return@withContext MapImportResult.Failure(MapProblem.NO_SPACE)
            }
            dir.mkdirs()
            val tmp = tempFile // eine liegengebliebene Zwischendatei wird beim Schreiben ueberschrieben
            var keep = false
            try {
                val input = try { open() } catch (e: IOException) { null } ?: return@withContext MapImportResult.Failure(MapProblem.UNREADABLE)
                val copied = input.use { copy(it, tmp) }
                if (copied is MapProblem) return@withContext MapImportResult.Failure(copied)
                val state = inspectFile(tmp)
                if (state is MapState.Broken) return@withContext MapImportResult.Failure(state.problem)
                state as MapState.Ready
                closeCached()
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    return@withContext MapImportResult.Failure(MapProblem.UNREADABLE)
                }
                keep = true
                MapImportResult.Success(state.info)
            } finally {
                if (!keep) tmp.delete()
            }
        }
    }

    /** Kopiert [input] nach [target]; `Unit` bei Erfolg, sonst das Problem. Prueft die Grenze und den Abbruch je Block. */
    private suspend fun copy(input: InputStream, target: File): Any {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        try {
            target.outputStream().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return MapProblem.TOO_LARGE
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            // Der Platz ist das haeufigste Problem beim Schreiben; ist noch Platz, war es die Quelle.
            return if (usableSpace() < MAP_SPACE_MARGIN_BYTES) MapProblem.NO_SPACE else MapProblem.UNREADABLE
        }
        return Unit
    }

    /** Der Leser der eingespielten Karte oder `null` (keine oder kaputte Datei). Wird zwischengespeichert; ein Einspielen oder Loeschen setzt ihn zurueck. */
    suspend fun archive(): PmTilesArchive? = cacheLock.withLock {
        cached?.let { return it.second }
        withContext(Dispatchers.IO) {
            val f = file
            if (!f.isFile) return@withContext null
            val source = try { FileSource(f) } catch (e: IOException) { return@withContext null }
            val archive = PmTilesArchive.open(source)
            if (archive == null || !archive.supported) { source.close(); return@withContext null }
            cached = source to archive
            archive
        }
    }

    /** Loescht die Karte (und eine liegengebliebene Zwischendatei). */
    suspend fun remove() = changeLock.withLock {
        withContext(Dispatchers.IO) {
            closeCached()
            file.delete()
            tempFile.delete()
        }
    }

    private suspend fun closeCached() = cacheLock.withLock {
        cached?.first?.close()
        cached = null
    }

    private suspend fun inspectFile(f: File): MapState {
        val source = try { FileSource(f) } catch (e: IOException) { return MapState.Broken(MapProblem.UNREADABLE) }
        source.use {
            val archive = PmTilesArchive.open(source) ?: return MapState.Broken(MapProblem.NOT_PMTILES)
            if (archive.header.tileType != PmTileType.MVT) return MapState.Broken(MapProblem.NOT_VECTOR)
            if (!archive.supported) return MapState.Broken(MapProblem.UNSUPPORTED_PACKING)
            val (name, attribution) = parseMetadata(archive.metadataJson())
            val h = archive.header
            return MapState.Ready(MapInfo(name, attribution, h.minLon, h.minLat, h.maxLon, h.maxLat, h.minZoom, h.maxZoom, f.length()))
        }
    }
}

/** Name und Namensnennung aus den Metadaten (JSON); `null`, wenn sie fehlen oder der Text kein JSON-Objekt ist. */
internal fun parseMetadata(json: String?): Pair<String?, String?> {
    if (json == null || !jsonDepthWithin(json)) return null to null
    return try {
        val o = JSONObject(json)
        fun text(key: String) = if (o.has(key) && !o.isNull(key)) o.optString(key).trim().takeIf { it.isNotEmpty() } else null
        text("name") to text("attribution")
    } catch (e: JSONException) {
        null to null
    }
}

private inline fun <T : Closeable, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
