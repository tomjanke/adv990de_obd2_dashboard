package de.adv990de.obd2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Der Dateiname des GPX-Exports: `track_<Name>_<UTC-Zeit>.gpx`, der Name nie ein Pfad. Nur erfundene Namen. */
class TrackExportTest {

    private val now = 1_800_000_000_123L // 2027-01-15T08:00:00.123Z

    @Test
    fun `the name is track_ plus the cleaned name plus the UTC time to the millisecond plus gpx`() {
        assertEquals("track_Morgenrunde_20270115T080000123Z.gpx", trackFileName("Morgenrunde", now))
    }

    @Test
    fun `the default name of a recording - date, space and colon - becomes a plain word chain`() {
        assertEquals("track_2026_11_17_10_00_20270115T080000123Z.gpx", trackFileName("2026-11-17 10:00", now))
    }

    @Test
    fun `a name can never leave the export folder - slashes, backslashes and dots are replaced`() {
        for (evil in listOf("../../etc/passwd", "..\\..\\x", "/absolute", "a/b", "..", ".", "...gpx")) {
            val name = trackFileName(evil, now)
            assertFalse(name, name.contains('/') || name.contains('\\') || name.contains(".."))
            assertTrue(name, name.startsWith("track_") && name.endsWith("_20270115T080000123Z.gpx"))
        }
    }

    @Test
    fun `a name with nothing usable gets a fixed word instead of an empty gap`() {
        assertEquals("track_unnamed_20270115T080000123Z.gpx", trackFileName("", now))
        assertEquals("track_unnamed_20270115T080000123Z.gpx", trackFileName(" ./: ", now))
    }

    @Test
    fun `letters of every language stay, runs of other characters become one underscore`() {
        assertEquals("track_Ötztal_Süd_20270115T080000123Z.gpx", trackFileName("Ötztal  /  Süd!", now))
        assertEquals("track_山道_20270115T080000123Z.gpx", trackFileName("山道", now))
    }

    @Test
    fun `a long name is cut at forty characters without a trailing underscore`() {
        val long = "a".repeat(39) + " " + "b".repeat(30)
        assertEquals("track_" + "a".repeat(39) + "_20270115T080000123Z.gpx", trackFileName(long, now))
        assertEquals("track_" + "x".repeat(40) + "_20270115T080000123Z.gpx", trackFileName("x".repeat(100), now))
    }

    @Test
    fun `a name cut inside a surrogate pair keeps whole characters`() {
        val name = trackFileName("𝒜".repeat(60), now) // each is one code point (two chars)
        val middle = name.removePrefix("track_").removeSuffix("_20270115T080000123Z.gpx")
        assertEquals(40, middle.codePointCount(0, middle.length))
    }

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `an old track export is removed by the cleanup of old exports, a recent one stays`() {
        val dir = tempFolder.newFolder("exports")
        val nowReal = System.currentTimeMillis()
        val old = File(dir, trackFileName("alt", 0L)).also { it.writeText("x"); it.setLastModified(nowReal - EXPORT_MAX_AGE_MS - 60_000) }
        val recent = File(dir, trackFileName("neu", nowReal)).also { it.writeText("x"); it.setLastModified(nowReal - 60_000) }
        val foreign = File(dir, "notes.gpx").also { it.writeText("x"); it.setLastModified(nowReal - EXPORT_MAX_AGE_MS - 60_000) }

        deleteStaleExports(dir, nowReal)

        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue("only our own export names are touched", foreign.exists())
    }
}
