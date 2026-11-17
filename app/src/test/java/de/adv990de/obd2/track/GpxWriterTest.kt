package de.adv990de.obd2.track

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringWriter
import java.util.Locale

class GpxWriterTest {

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

    private fun write(name: String, points: List<TrackPoint>): String = StringWriter().also { writeGpx(it, name, points) }.toString()

    private fun p(lat: Double, lon: Double, ele: Float? = null, timeMs: Long? = null) = TrackPoint(timeMs, lat, lon, ele, null, null)

    @Test
    fun `the file is gpx 1_1 with the app as creator`() {
        val xml = write("T", listOf(p(1.0, 2.0)))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<gpx version=\"1.1\" creator=\"OBD Dashboard\" xmlns=\"http://www.topografix.com/GPX/1/1\">"))
        assertTrue(xml.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun `numbers have a dot even on a german device`() {
        assertEquals("de", Locale.getDefault().language)
        val xml = write("T", listOf(p(48.1234567, 11.7654321, ele = 523.4f)))
        assertTrue(xml, xml.contains("lat=\"48.1234567\" lon=\"11.7654321\""))
        assertTrue(xml, xml.contains("<ele>523.4</ele>"))
        assertFalse(xml.contains(","))
    }

    @Test
    fun `negative coordinates and trailing zeros`() {
        val xml = write("T", listOf(p(-33.5, -70.0)))
        assertTrue(xml, xml.contains("lat=\"-33.5000000\" lon=\"-70.0000000\""))
    }

    @Test
    fun `the time is written in utc with milliseconds when there are some`() {
        val xml = write("T", listOf(p(1.0, 2.0, timeMs = 1_794_909_600_000L), p(1.0, 2.1, timeMs = 1_794_909_600_123L)))
        assertTrue(xml, xml.contains("<time>2026-11-17T10:00:00Z</time>"))
        assertTrue(xml, xml.contains("<time>2026-11-17T10:00:00.123Z</time>"))
    }

    @Test
    fun `a point without elevation or time has neither element`() {
        val xml = write("T", listOf(p(1.0, 2.0)))
        assertFalse(xml.contains("<ele>"))
        assertFalse(xml.contains("<time>"))
    }

    @Test
    fun `an elevation that is not finite is left out`() {
        val xml = write("T", listOf(p(1.0, 2.0, ele = Float.NaN), p(1.0, 2.1, ele = Float.POSITIVE_INFINITY)))
        assertFalse(xml.contains("<ele>"))
    }

    @Test
    fun `a time outside the years 0 to 9999 is left out`() {
        val xml = write("T", listOf(p(1.0, 2.0, timeMs = -1L), p(1.0, 2.1, timeMs = 253_402_300_800_000L)))
        assertFalse(xml.contains("<time>"))
    }

    @Test
    fun `the name is escaped`() {
        val xml = write("a<b>&\"c'", listOf(p(1.0, 2.0)))
        assertTrue(xml, xml.contains("<name>a&lt;b&gt;&amp;&quot;c&apos;</name>"))
    }

    @Test
    fun `characters that xml forbids are dropped from the name`() {
        val xml = write("a\u0000b\u0001c\ud800d￾e", listOf(p(1.0, 2.0)))
        assertTrue(xml, xml.contains("<name>abcde</name>"))
    }

    @Test
    fun `umlauts and emoji in the name survive`() {
        val xml = write("München 🏍", listOf(p(1.0, 2.0)))
        assertTrue(xml, xml.contains("<name>München 🏍</name>"))
    }

    @Test
    fun `a track without points is still a valid document`() {
        val parser = KXmlParser()
        parser.setInput(write("Leer", emptyList()).reader())
        var tags = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) if (parser.eventType == XmlPullParser.START_TAG) tags++
        assertTrue(tags >= 3)
    }

    @Test
    fun `the output is well formed xml`() {
        val xml = write("Runde <1> & Co", (0 until 50).map { p(48.0 + it * 1e-4, 11.0 + it * 1e-4, ele = 500f + it, timeMs = 1_794_909_600_000L + it * 1000) })
        val parser = KXmlParser()
        parser.setInput(xml.reader())
        var pts = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) if (parser.eventType == XmlPullParser.START_TAG && parser.name == "trkpt") pts++
        assertEquals(50, pts)
    }
}
