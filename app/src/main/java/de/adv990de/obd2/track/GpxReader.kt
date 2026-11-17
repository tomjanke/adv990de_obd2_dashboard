package de.adv990de.obd2.track

import android.util.Xml
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.LimitedInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import java.time.OffsetDateTime

/** Die Grenzen beim Lesen einer GPX-Datei. **Meine Wahl, ungemessen** (die echten Dateien des Nutzers sind weit kleiner); der Test haelt die Standardwerte fest. */
data class GpxLimits(
    val maxBytes: Long = 64L * 1024 * 1024,
    val maxPoints: Int = 2_000_000,
    val maxTracks: Int = 500,
    val maxWaypoints: Int = 100_000,
    val maxDepth: Int = 16,
)

data class Waypoint(val name: String?, val lat: Double, val lon: Double, val ele: Float?)

/** Eine Spur der Datei; die Segmente einer `trk` sind zu einer Punktliste verbunden. */
data class GpxTrack(val name: String?, val points: List<TrackPoint>)

/** [rejectedPoints] zaehlt Punkte (Spur, Route, Wegpunkt) mit fehlender oder unmoeglicher Koordinate. */
data class GpxFile(val tracks: List<GpxTrack>, val waypoints: List<Waypoint>, val rejectedPoints: Int)

/** Eine Datei, die sich nicht als GPX lesen laesst: [Kind.NOT_GPX] (kein oder kaputtes XML, andere Wurzel, zu tief verschachtelt), [Kind.TOO_LARGE], [Kind.TOO_MANY_POINTS]. */
class GpxFileException(val kind: Kind, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause) {
    enum class Kind { NOT_GPX, TOO_LARGE, TOO_MANY_POINTS }
}

private const val MAX_TEXT_CHARS = 1024

/** Der Parser von Android (kXML); die JVM-Tests geben einen eigenen mit. */
private fun androidParser(): XmlPullParser = Xml.newPullParser()

/**
 * Liest eine GPX-Datei **im Strom** (nie die ganze Datei im Speicher; mehr als [GpxLimits.maxBytes] Bytes werden nicht gelesen). Gelesen werden `trk`/`trkseg`/`trkpt` und
 * `rte`/`rtept` (als Spur) und `wpt`; `name`, `ele` und `time` der Punkte; alles andere (Metadaten, Erweiterungen) wird uebergangen.
 *
 * Sicherheit: **jedes DOCTYPE macht die Datei zu keiner GPX-Datei** (ein GPX hat keines; so gibt es weder externe Entitaeten noch Entitaetsbomben — der Parser von Android expandiert interne Entitaeten auch mit ausgeschaltetem `FEATURE_PROCESS_DOCDECL`), die Verschachtelung ist
 * auf [GpxLimits.maxDepth] begrenzt (ein zu tiefer Baum wuerde bei einem rekursiven Leser den Stapel sprengen), Koordinaten werden geprueft (Breite hoechstens 90,
 * Laenge hoechstens 180, endlich), eine ungueltige Zeit macht nur die Zeit zu `null` (der Punkt bleibt), eine Zeit ohne Zone ebenso (sie waere geraten).
 * Alle Zahlen werden mit Punkt gelesen, unabhaengig von der Geraetesprache.
 *
 * Jeder Fehler im XML ist ein [GpxFileException] mit [GpxFileException.Kind.NOT_GPX] — eine kaputte Datei beendet nie die App.
 */
fun readGpx(input: InputStream, limits: GpxLimits = GpxLimits(), newParser: () -> XmlPullParser = ::androidParser): GpxFile {
    val reader = GpxReading(limits)
    try {
        val parser = newParser()
        parser.setInput(LimitedInputStream(input, limits.maxBytes), null)
        reader.run(parser)
    } catch (e: GpxFileException) {
        throw e
    } catch (e: FileTooLargeException) {
        throw GpxFileException(GpxFileException.Kind.TOO_LARGE, "The file is too large", e)
    } catch (e: XmlPullParserException) {
        throw GpxFileException(GpxFileException.Kind.NOT_GPX, "Not a GPX file", e)
    } catch (e: RuntimeException) {
        // ein Parser darf bei kaputten Eingaben auch mit einer RuntimeException scheitern (z. B. IndexOutOfBounds); das ist eine kaputte Datei, kein Programmfehler dieser App
        throw GpxFileException(GpxFileException.Kind.NOT_GPX, "Not a GPX file", e)
    }
    return reader.result()
}

private class GpxReading(private val limits: GpxLimits) {
    private val stack = ArrayList<String>()
    private val tracks = ArrayList<GpxTrack>()
    private val waypoints = ArrayList<Waypoint>()
    private var rejected = 0
    private var totalPoints = 0
    private var sawRoot = false

    private var trackName: String? = null
    private var trackPoints: ArrayList<TrackPoint>? = null
    private var point: PointBuilder? = null
    private var capture: StringBuilder? = null

    private class PointBuilder(val lat: Double?, val lon: Double?) {
        var ele: Float? = null
        var timeMs: Long? = null
        var name: String? = null
    }

    fun result(): GpxFile {
        if (!sawRoot) throw GpxFileException(GpxFileException.Kind.NOT_GPX, "No gpx element")
        return GpxFile(tracks, waypoints, rejected)
    }

    fun run(parser: XmlPullParser) {
        // nextToken statt next: nur so meldet der Parser ein DOCTYPE (next ueberspringt es); der Parser von Android expandiert interne Entitaeten auch ohne
        // FEATURE_PROCESS_DOCDECL (am Geraet gemessen: eine Entitaetsbombe endete mit OutOfMemoryError), und ein GPX hat nie ein DOCTYPE
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> {
                    // der Parser von Android meldet ein vorzeitiges Dateiende nicht als Fehler: eine abgeschnittene Datei ist keine vollstaendige Datei
                    if (stack.isNotEmpty()) throw GpxFileException(GpxFileException.Kind.NOT_GPX, "The file ends inside an element")
                    return
                }
                XmlPullParser.DOCDECL -> throw GpxFileException(GpxFileException.Kind.NOT_GPX, "A DOCTYPE is not allowed")
                XmlPullParser.START_TAG -> start(parser)
                XmlPullParser.END_TAG -> end()
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> parser.text?.let { text ->
                    capture?.let { if (it.length < MAX_TEXT_CHARS) it.append(text, 0, minOf(text.length, MAX_TEXT_CHARS - it.length)) }
                }
            }
        }
    }

    private fun parent() = stack.lastOrNull()

    private fun start(parser: XmlPullParser) {
        val name = parser.name.substringAfter(':')
        if (stack.size + 1 > limits.maxDepth) throw GpxFileException(GpxFileException.Kind.NOT_GPX, "Nested too deep")
        if (stack.isEmpty()) {
            if (name != "gpx") throw GpxFileException(GpxFileException.Kind.NOT_GPX, "Root is not gpx")
            sawRoot = true
        }
        val parent = parent()
        stack.add(name)
        capture = null
        when (name) {
            "trk", "rte" -> if (parent == "gpx") {
                if (tracks.size >= limits.maxTracks) throw GpxFileException(GpxFileException.Kind.TOO_MANY_POINTS, "Too many tracks")
                trackName = null
                trackPoints = ArrayList()
            }
            "trkpt" -> if (parent == "trkseg" && trackPoints != null) point = pointFrom(parser)
            "rtept" -> if (parent == "rte" && trackPoints != null) point = pointFrom(parser)
            "wpt" -> if (parent == "gpx") point = pointFrom(parser)
            // den Namen jedes Elements mitschneiden; wem er gehoert, entscheidet end() nach dem Elternelement
            "name" -> capture = StringBuilder()
            "ele", "time" -> if ((parent == "trkpt" || parent == "rtept" || parent == "wpt") && point != null) capture = StringBuilder()
        }
    }

    private fun end() {
        val name = stack.removeAt(stack.size - 1)
        val parent = parent()
        val text = capture?.toString()?.trim()
        capture = null
        when (name) {
            "name" -> if (parent == "wpt") point?.name = text else if (parent == "trk" || parent == "rte") trackName = text?.ifEmpty { null }
            "ele" -> if (parent == "trkpt" || parent == "rtept" || parent == "wpt") point?.ele = text?.toFloatOrNull()?.takeIf { it.isFinite() }
            "time" -> if (parent == "trkpt" || parent == "rtept" || parent == "wpt") point?.timeMs = parseTime(text)
            "trkpt", "rtept" -> finishPoint()
            "wpt" -> finishWaypoint()
            "trk", "rte" -> if (parent == "gpx") finishTrack()
        }
    }

    private fun pointFrom(parser: XmlPullParser) = PointBuilder(coordinate(parser, "lat", 90.0), coordinate(parser, "lon", 180.0))

    private fun coordinate(parser: XmlPullParser, attribute: String, limit: Double): Double? {
        val v = parser.getAttributeValue(null, attribute)?.trim()?.toDoubleOrNull() ?: return null
        return v.takeIf { it.isFinite() && it >= -limit && it <= limit }
    }

    private fun finishPoint() {
        val p = point ?: return
        point = null
        val lat = p.lat
        val lon = p.lon
        if (lat == null || lon == null) {
            rejected++
            return
        }
        if (totalPoints >= limits.maxPoints) throw GpxFileException(GpxFileException.Kind.TOO_MANY_POINTS, "Too many points")
        totalPoints++
        trackPoints?.add(TrackPoint(p.timeMs, lat, lon, p.ele, null, null))
    }

    private fun finishWaypoint() {
        val p = point ?: return
        point = null
        val lat = p.lat
        val lon = p.lon
        if (lat == null || lon == null) {
            rejected++
            return
        }
        if (waypoints.size >= limits.maxWaypoints) throw GpxFileException(GpxFileException.Kind.TOO_MANY_POINTS, "Too many waypoints")
        waypoints.add(Waypoint(p.name?.ifEmpty { null }, lat, lon, p.ele))
    }

    private fun finishTrack() {
        val pts = trackPoints
        trackPoints = null
        if (pts != null && pts.isNotEmpty()) tracks.add(GpxTrack(trackName, pts))
    }

    /** ISO 8601 mit `Z` oder Versatz; alles andere ist unbekannt (eine Zeit ohne Zone waere geraten). */
    private fun parseTime(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }
}
