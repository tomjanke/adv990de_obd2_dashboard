package de.adv990de.obd2.track

import de.adv990de.obd2.track.GpxFileException.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.util.Locale

/** Nur erfundene Koordinaten. Der Parser ist der echte kXML der JVM-Tests; auf dem Geraet laeuft derselbe Leser mit dem Parser von Android (GpxReaderDeviceTest). */
class GpxReaderTest {

    private var savedLocale: Locale? = null

    @Before
    fun germanLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        savedLocale?.let { Locale.setDefault(it) }
    }

    private fun gpx(body: String, header: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>") =
        "$header<gpx version=\"1.1\" creator=\"x\" xmlns=\"http://www.topografix.com/GPX/1/1\">$body</gpx>"

    private fun read(text: String, limits: GpxLimits = GpxLimits()): GpxFile = read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), limits)

    private fun read(input: InputStream, limits: GpxLimits = GpxLimits()): GpxFile = readGpx(input, limits) { KXmlParser() }

    private fun kindOf(text: String, limits: GpxLimits = GpxLimits()): Kind {
        try {
            read(text, limits)
        } catch (e: GpxFileException) {
            return e.kind
        }
        fail("expected a GpxFileException")
        throw AssertionError()
    }

    private fun trk(vararg pts: String, name: String? = "Runde") =
        "<trk>${name?.let { "<name>$it</name>" } ?: ""}<trkseg>${pts.joinToString("")}</trkseg></trk>"

    private fun pt(lat: String, lon: String, inner: String = "") = "<trkpt lat=\"$lat\" lon=\"$lon\">$inner</trkpt>"

    @Test
    fun `a simple track is read with all its fields`() {
        val file = read(gpx(trk(pt("48.1234567", "11.7654321", "<ele>523.4</ele><time>2026-11-17T10:00:00Z</time>"), pt("48.1235", "11.7655"))))
        assertEquals(1, file.tracks.size)
        val t = file.tracks[0]
        assertEquals("Runde", t.name)
        assertEquals(2, t.points.size)
        assertEquals(48.1234567, t.points[0].lat, 0.0)
        assertEquals(11.7654321, t.points[0].lon, 0.0)
        assertEquals(523.4f, t.points[0].ele!!, 0.001f)
        assertEquals(1_794_909_600_000L, t.points[0].timeMs)
        assertNull(t.points[1].ele)
        assertNull(t.points[1].timeMs)
        assertEquals(0, file.rejectedPoints)
    }

    @Test
    fun `two tracks stay two tracks`() {
        val file = read(gpx(trk(pt("1", "2"), name = "A") + trk(pt("3", "4"), pt("3.1", "4.1"), name = "B")))
        assertEquals(listOf("A", "B"), file.tracks.map { it.name })
        assertEquals(listOf(1, 2), file.tracks.map { it.points.size })
    }

    @Test
    fun `segments of one track are joined`() {
        val file = read(gpx("<trk><name>S</name><trkseg>${pt("1", "2")}</trkseg><trkseg>${pt("3", "4")}${pt("5", "6")}</trkseg></trk>"))
        assertEquals(1, file.tracks.size)
        assertEquals(3, file.tracks[0].points.size)
    }

    @Test
    fun `a route is read as a track`() {
        val file = read(gpx("<rte><name>Route</name><rtept lat=\"10\" lon=\"20\"><ele>5</ele></rtept><rtept lat=\"10.1\" lon=\"20.1\"/></rte>"))
        assertEquals(1, file.tracks.size)
        assertEquals("Route", file.tracks[0].name)
        assertEquals(2, file.tracks[0].points.size)
        assertEquals(5f, file.tracks[0].points[0].ele!!, 0f)
    }

    @Test
    fun `waypoints are read`() {
        val file = read(gpx("<wpt lat=\"1.5\" lon=\"2.5\"><name>Huette</name><ele>1200</ele></wpt><wpt lat=\"3\" lon=\"4\"/>"))
        assertEquals(0, file.tracks.size)
        assertEquals(2, file.waypoints.size)
        assertEquals("Huette", file.waypoints[0].name)
        assertEquals(1200f, file.waypoints[0].ele!!, 0f)
        assertNull(file.waypoints[1].name)
    }

    @Test
    fun `a file with only waypoints is a valid file`() {
        assertEquals(1, read(gpx("<wpt lat=\"1\" lon=\"2\"/>")).waypoints.size)
    }

    @Test
    fun `a track without a name has no name`() {
        assertNull(read(gpx(trk(pt("1", "2"), name = null))).tracks[0].name)
    }

    @Test
    fun `a name in the metadata or in an extension is not the name of the track`() {
        val body = "<metadata><name>Datei</name></metadata><trk><extensions><name>Fremd</name></extensions><trkseg>${pt("1", "2")}</trkseg></trk>"
        assertNull(read(gpx(body)).tracks[0].name)
    }

    @Test
    fun `extensions and unknown elements are ignored`() {
        val ext = "<extensions><gpxtpx:TrackPointExtension xmlns:gpxtpx=\"urn:x\"><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>"
        val file = read(gpx(trk(pt("1", "2", "<ele>7</ele>$ext<sat>9</sat>"))))
        assertEquals(1, file.tracks[0].points.size)
        assertEquals(7f, file.tracks[0].points[0].ele!!, 0f)
    }

    @Test
    fun `coordinates out of range are rejected and counted`() {
        val file = read(gpx(trk(pt("91", "10"), pt("-91", "10"), pt("10", "181"), pt("10", "-181"), pt("10", "10"), pt("90", "180"), pt("-90", "-180"))))
        assertEquals(3, file.tracks[0].points.size)
        assertEquals(4, file.rejectedPoints)
    }

    @Test
    fun `coordinates that are not numbers are rejected`() {
        val file = read(gpx(trk(pt("NaN", "10"), pt("10", "Infinity"), pt("abc", "10"), pt("", "10"), pt("10", "10"))))
        assertEquals(1, file.tracks[0].points.size)
        assertEquals(4, file.rejectedPoints)
    }

    @Test
    fun `a point without a latitude or longitude is rejected`() {
        val file = read(gpx("<trk><trkseg><trkpt lon=\"10\"/><trkpt lat=\"10\"/><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk>"))
        assertEquals(1, file.tracks[0].points.size)
        assertEquals(2, file.rejectedPoints)
    }

    @Test
    fun `a track whose points are all rejected is not returned`() {
        val file = read(gpx(trk(pt("99", "10"))))
        assertEquals(0, file.tracks.size)
        assertEquals(1, file.rejectedPoints)
    }

    @Test
    fun `a time with an offset is converted to utc`() {
        val file = read(gpx(trk(pt("1", "2", "<time>2026-11-17T11:00:00+01:00</time>"), pt("1", "2.1", "<time>2026-11-17T10:00:00.250Z</time>"))))
        assertEquals(1_794_909_600_000L, file.tracks[0].points[0].timeMs)
        assertEquals(1_794_909_600_250L, file.tracks[0].points[1].timeMs)
    }

    @Test
    fun `a time without a zone or that is garbage is unknown but the point stays`() {
        val file = read(gpx(trk(pt("1", "2", "<time>2026-11-17T10:00:00</time>"), pt("1", "2.1", "<time>gestern</time>"), pt("1", "2.2", "<time></time>"))))
        assertEquals(3, file.tracks[0].points.size)
        assertTrue(file.tracks[0].points.all { it.timeMs == null })
    }

    @Test
    fun `an elevation that is not a number is unknown`() {
        val file = read(gpx(trk(pt("1", "2", "<ele>NaN</ele>"), pt("1", "2.1", "<ele>hoch</ele>"), pt("1", "2.2", "<ele>-12.5</ele>"))))
        val p = file.tracks[0].points
        assertNull(p[0].ele)
        assertNull(p[1].ele)
        assertEquals(-12.5f, p[2].ele!!, 0f)
    }

    @Test
    fun `numbers use a dot whatever the device language is`() {
        assertEquals("de", Locale.getDefault().language)
        assertEquals(48.5, read(gpx(trk(pt("48.5", "11.5")))).tracks[0].points[0].lat, 0.0)
    }

    @Test
    fun `a byte order mark at the start is fine`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + gpx(trk(pt("1", "2"))).toByteArray(Charsets.UTF_8)
        assertEquals(1, read(ByteArrayInputStream(bytes)).tracks.size)
    }

    @Test
    fun `a name with umlauts and entities is decoded`() {
        assertEquals("Tour Über & <aussen>", read(gpx(trk(pt("1", "2"), name = "Tour Über &amp; &lt;aussen&gt;"))).tracks[0].name)
    }

    @Test
    fun `a name in a cdata section is read`() {
        assertEquals("a<b", read(gpx(trk(pt("1", "2"), name = "<![CDATA[a<b]]>"))).tracks[0].name)
    }

    @Test
    fun `text that is not xml is not a gpx file`() {
        assertEquals(Kind.NOT_GPX, kindOf("das ist keine Datei"))
    }

    @Test
    fun `an empty file is not a gpx file`() {
        assertEquals(Kind.NOT_GPX, kindOf(""))
    }

    @Test
    fun `xml with another root is not a gpx file`() {
        assertEquals(Kind.NOT_GPX, kindOf("<?xml version=\"1.0\"?><kml><Document/></kml>"))
    }

    @Test
    fun `xml that breaks in the middle is not a gpx file`() {
        assertEquals(Kind.NOT_GPX, kindOf(gpx(trk(pt("1", "2"))).removeSuffix("</gpx>").replace("</trkseg>", "")))
    }

    @Test
    fun `binary data is not a gpx file`() {
        try {
            read(ByteArrayInputStream(ByteArray(2000) { (it * 7).toByte() }))
            fail("expected a GpxFileException")
        } catch (e: GpxFileException) {
            assertEquals(Kind.NOT_GPX, e.kind)
        }
    }

    @Test(timeout = 10_000)
    fun `an entity bomb is refused because of its doctype`() {
        val laughs = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY lol \"lol\">")
            for (i in 1..9) append("<!ENTITY lol$i \"" + "&lol${if (i == 1) "" else (i - 1).toString()};".repeat(10) + "\">")
            append("]>")
        }
        assertEquals(Kind.NOT_GPX, kindOf(gpx(trk(pt("1", "2"), name = "&lol9;"), header = laughs)))
    }

    @Test(timeout = 10_000)
    fun `an external entity is refused because of its doctype and never read`() {
        val secret = File.createTempFile("gpx-xxe", ".txt").apply { writeText("GEHEIMER-INHALT"); deleteOnExit() }
        val header = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]>"
        assertEquals(Kind.NOT_GPX, kindOf(gpx(trk(pt("1", "2"), name = "&xxe;"), header = header)))
    }

    @Test
    fun `any doctype makes the file no gpx file`() {
        assertEquals(Kind.NOT_GPX, kindOf(gpx(trk(pt("1", "2")), header = "<?xml version=\"1.0\"?><!DOCTYPE gpx>")))
    }

    @Test
    fun `a file that ends inside an element is not complete`() {
        assertEquals(Kind.NOT_GPX, kindOf(gpx(trk(pt("1", "2"))).removeSuffix("</gpx>")))
    }

    @Test
    fun `an element nested too deep is not a gpx file`() {
        val deep = "<a>".repeat(30) + "</a>".repeat(30)
        assertEquals(Kind.NOT_GPX, kindOf(gpx(deep)))
    }

    @Test
    fun `the depth limit is the one of the limits`() {
        val body = trk(pt("1", "2"))
        assertEquals(1, read(gpx(body), GpxLimits(maxDepth = 4)).tracks.size)
        assertEquals(Kind.NOT_GPX, kindOf(gpx(body), GpxLimits(maxDepth = 3)))
    }

    @Test
    fun `too many points`() {
        val body = trk(pt("1", "2"), pt("1", "3"), pt("1", "4"), pt("1", "5"))
        assertEquals(4, read(gpx(body), GpxLimits(maxPoints = 4)).tracks[0].points.size)
        assertEquals(Kind.TOO_MANY_POINTS, kindOf(gpx(body), GpxLimits(maxPoints = 3)))
    }

    @Test
    fun `rejected points do not count against the point limit`() {
        val body = trk(pt("99", "2"), pt("99", "3"), pt("1", "4"))
        assertEquals(1, read(gpx(body), GpxLimits(maxPoints = 1)).tracks[0].points.size)
    }

    @Test
    fun `too many tracks`() {
        val body = trk(pt("1", "2")) + trk(pt("1", "3")) + trk(pt("1", "4"))
        assertEquals(3, read(gpx(body), GpxLimits(maxTracks = 3)).tracks.size)
        assertEquals(Kind.TOO_MANY_POINTS, kindOf(gpx(body), GpxLimits(maxTracks = 2)))
    }

    @Test
    fun `too many waypoints`() {
        val body = "<wpt lat=\"1\" lon=\"2\"/><wpt lat=\"1\" lon=\"3\"/>"
        assertEquals(2, read(gpx(body), GpxLimits(maxWaypoints = 2)).waypoints.size)
        assertEquals(Kind.TOO_MANY_POINTS, kindOf(gpx(body), GpxLimits(maxWaypoints = 1)))
    }

    @Test
    fun `a file over the size limit is too large and is not read to the end`() {
        val big = gpx(trk(*Array(2000) { pt("1", "2.$it") }))
        assertEquals(Kind.TOO_LARGE, kindOf(big, GpxLimits(maxBytes = 5_000)))
        var read = 0L
        val counting = object : InputStream() {
            private val src = ByteArrayInputStream(big.toByteArray())
            override fun read(): Int = src.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len).also { if (it > 0) read += it }
        }
        try {
            read(counting, GpxLimits(maxBytes = 5_000))
        } catch (e: GpxFileException) {
            // erwartet
        }
        assertTrue("read $read bytes", read < 20_000)
    }

    @Test
    fun `the default limits are the documented ones`() {
        val l = GpxLimits()
        assertEquals(64L * 1024 * 1024, l.maxBytes)
        assertEquals(2_000_000, l.maxPoints)
        assertEquals(500, l.maxTracks)
        assertEquals(100_000, l.maxWaypoints)
        assertEquals(16, l.maxDepth)
    }

    @Test
    fun `a long track is read completely`() {
        val pts = Array(30_000) { pt("48.${it % 1000}", "11.${it / 10}", "<ele>${it % 50}</ele>") }
        assertEquals(30_000, read(gpx(trk(*pts))).tracks[0].points.size)
    }

    @Test
    fun `what the writer writes the reader reads back`() {
        val points = listOf(
            TrackPoint(1_794_909_600_123L, 48.1234567, 11.7654321, 523.4f, null, null),
            TrackPoint(1_794_996_005_000L, 48.1240001, 11.7660002, null, null, null),
            TrackPoint(null, -33.8688, 151.2093, -3.2f, null, null),
        )
        val out = StringWriter()
        writeGpx(out, "Runde <1> & \"Co\"", points)
        val file = read(out.toString())
        assertEquals("Runde <1> & \"Co\"", file.tracks[0].name)
        val back = file.tracks[0].points
        assertEquals(3, back.size)
        for (i in points.indices) {
            assertEquals(points[i].lat, back[i].lat, 1e-7)
            assertEquals(points[i].lon, back[i].lon, 1e-7)
            assertEquals(points[i].timeMs, back[i].timeMs)
            if (points[i].ele == null) assertNull(back[i].ele) else assertEquals(points[i].ele!!, back[i].ele!!, 0.05f)
        }
        assertNotNull(back[0].timeMs)
    }

    @Test
    fun `a route point outside a route is ignored`() {
        val file = read(gpx("<trk><trkseg>${pt("1", "2")}</trkseg><rtept lat=\"5\" lon=\"6\"/></trk><extensions><rtept lat=\"7\" lon=\"8\"/></extensions>"))
        assertEquals(1, file.tracks.size)
        assertEquals(1, file.tracks[0].points.size)
    }

    @Test
    fun `a track point outside a segment is ignored`() {
        val file = read(gpx("<trk><trkpt lat=\"5\" lon=\"6\"/><trkseg>${pt("1", "2")}</trkseg></trk>"))
        assertEquals(1, file.tracks[0].points.size)
    }

    @Test
    fun `elements with a namespace prefix are read`() {
        val xml = "<?xml version=\"1.0\"?><g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/1\"><g:trk><g:name>P</g:name><g:trkseg>" +
            "<g:trkpt lat=\"1\" lon=\"2\"><g:ele>3</g:ele></g:trkpt></g:trkseg></g:trk></g:gpx>"
        val file = read(xml)
        assertEquals("P", file.tracks[0].name)
        assertEquals(3f, file.tracks[0].points[0].ele!!, 0f)
    }
}
