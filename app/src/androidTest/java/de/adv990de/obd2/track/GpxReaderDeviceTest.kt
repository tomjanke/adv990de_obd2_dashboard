package de.adv990de.obd2.track

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.track.GpxFileException.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringWriter
import java.util.Locale

/** Derselbe Leser mit dem Parser von Android (`Xml.newPullParser()`), nicht mit dem kXML der JVM-Tests. Nur erfundene Koordinaten. */
@RunWith(AndroidJUnit4::class)
class GpxReaderDeviceTest {

    private var saved: Locale? = null

    @Before
    fun german() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restore() {
        saved?.let { Locale.setDefault(it) }
    }

    private fun gpx(body: String, header: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>") =
        "$header<gpx version=\"1.1\" creator=\"x\" xmlns=\"http://www.topografix.com/GPX/1/1\">$body</gpx>"

    private fun read(text: String, limits: GpxLimits = GpxLimits()) = readGpx(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), limits)

    private fun kindOf(text: String, limits: GpxLimits = GpxLimits()): Kind {
        try {
            read(text, limits)
        } catch (e: GpxFileException) {
            return e.kind
        }
        fail("expected a GpxFileException")
        throw AssertionError()
    }

    private val oneTrack = "<trk><name>Runde</name><trkseg><trkpt lat=\"48.5\" lon=\"11.5\"><ele>500.5</ele><time>2026-11-17T10:00:00Z</time></trkpt></trkseg></trk>"

    @Test
    fun aTrackIsReadWithDotsOnAGermanDevice() {
        val p = read(gpx(oneTrack)).tracks[0].points[0]
        assertEquals(48.5, p.lat, 0.0)
        assertEquals(500.5f, p.ele!!, 0f)
        assertEquals(1_794_909_600_000L, p.timeMs)
    }

    @Test
    fun theWriterOutputIsReadBack() {
        val points = listOf(TrackPoint(1_794_909_600_123L, 48.1234567, 11.7654321, 523.4f, null, null), TrackPoint(null, -33.8688, 151.2093, null, null, null))
        val out = StringWriter()
        writeGpx(out, "Runde <1> & \"Co\" Über", points)
        val file = read(out.toString())
        assertEquals("Runde <1> & \"Co\" Über", file.tracks[0].name)
        assertEquals(2, file.tracks[0].points.size)
        assertEquals(1_794_909_600_123L, file.tracks[0].points[0].timeMs)
        assertEquals(48.1234567, file.tracks[0].points[0].lat, 1e-7)
    }

    @Test
    fun aByteOrderMarkIsFine() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + gpx(oneTrack).toByteArray(Charsets.UTF_8)
        assertEquals(1, readGpx(ByteArrayInputStream(bytes)).tracks.size)
    }

    @Test
    fun aRouteAndAWaypointAreRead() {
        val file = read(gpx("<rte><rtept lat=\"1\" lon=\"2\"/></rte><wpt lat=\"3\" lon=\"4\"><name>W</name></wpt>"))
        assertEquals(1, file.tracks.size)
        assertEquals("W", file.waypoints[0].name)
    }

    @Test
    fun anEmptyFileIsNotGpx() = assertEquals(Kind.NOT_GPX, kindOf(""))

    @Test
    fun textThatIsNotXmlIsNotGpx() = assertEquals(Kind.NOT_GPX, kindOf("kein XML"))

    @Test
    fun anotherRootIsNotGpx() = assertEquals(Kind.NOT_GPX, kindOf("<kml/>"))

    @Test
    fun aFileThatEndsInsideAnElementIsNotComplete() = assertEquals(Kind.NOT_GPX, kindOf(gpx(oneTrack).removeSuffix("</gpx>")))

    @Test
    fun aNameWithEntitiesIsDecoded() {
        val name = read(gpx("<trk><name>A &amp; B &lt;c&gt;</name><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk>")).tracks[0].name
        assertEquals("A & B <c>", name)
    }

    @Test
    fun anEntityBombIsRefusedAndNeverExpanded() {
        val laughs = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY lol \"lol\">")
            for (i in 1..9) append("<!ENTITY lol$i \"" + "&lol${if (i == 1) "" else (i - 1).toString()};".repeat(10) + "\">")
            append("]>")
        }
        assertEquals(Kind.NOT_GPX, kindOf(gpx("<trk><name>&lol9;</name><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk>", laughs)))
    }

    @Test
    fun anExternalEntityIsRefusedAndNeverRead() {
        val secret = File.createTempFile("gpx-xxe", ".txt").apply { writeText("GEHEIMER-INHALT"); deleteOnExit() }
        val header = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]>"
        assertEquals(Kind.NOT_GPX, kindOf(gpx("<trk><name>&xxe;</name><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk>", header)))
    }

    @Test
    fun theDepthLimitAndTheSizeLimitHold() {
        assertEquals(Kind.NOT_GPX, kindOf(gpx("<a>".repeat(30) + "</a>".repeat(30))))
        assertEquals(Kind.TOO_LARGE, kindOf(gpx(oneTrack.repeat(200)), GpxLimits(maxBytes = 2_000)))
        assertEquals(Kind.TOO_MANY_POINTS, kindOf(gpx(oneTrack.repeat(3)), GpxLimits(maxPoints = 2)))
    }

    @Test
    fun elementsWithANamespacePrefixAreRead() {
        val xml = "<?xml version=\"1.0\"?><g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/1\"><g:trk><g:name>P</g:name><g:trkseg>" +
            "<g:trkpt lat=\"1\" lon=\"2\"/></g:trkseg></g:trk></g:gpx>"
        assertEquals("P", read(xml).tracks[0].name)
    }

    @Test
    fun aRoutePointOutsideARouteIsIgnored() {
        val file = read(gpx("<trk><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg><rtept lat=\"5\" lon=\"6\"/></trk>"))
        assertEquals(1, file.tracks[0].points.size)
    }
}
