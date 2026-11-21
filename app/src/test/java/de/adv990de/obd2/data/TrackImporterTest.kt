package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.adv990de.obd2.track.GpxFileException
import de.adv990de.obd2.track.GpxLimits
import de.adv990de.obd2.track.GpxTrack
import de.adv990de.obd2.track.TrackFingerprint
import de.adv990de.obd2.track.TrackImportItem
import de.adv990de.obd2.track.TrackImportPlan
import de.adv990de.obd2.track.TrackPoint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.IOException

/** [TrackImporter]: Datei lesen, Doppelte suchen, nur das Neue schreiben. Alle Koordinaten sind erfunden. */
class TrackImporterTest {

    private val context = mockk<Context>()
    private val repository = mockk<TrackRepository>()
    private val importer = TrackImporter(context, repository).apply {
        newParser = { KXmlParser() }
        fallbackName = { "Fallback $it" }
    }

    private fun gpx(vararg tracks: Pair<String?, Int>): String = buildString {
        append("<?xml version=\"1.0\"?><gpx version=\"1.1\">")
        for ((name, n) in tracks) {
            append("<trk>")
            if (name != null) append("<name>$name</name>")
            append("<trkseg>")
            for (i in 0 until n) append("<trkpt lat=\"${10.0 + i * 0.001}\" lon=\"20.0\"></trkpt>")
            append("</trkseg></trk>")
        }
        append("</gpx>")
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `the plan asks the repository for the name and point count of every track`() = runTest {
        val keys = slot<Collection<Pair<String, Int>>>()
        coEvery { repository.fingerprints(capture(keys)) } returns emptyList()

        importer.plan(stream(gpx("A" to 3, null to 5)))

        assertEquals(listOf("A" to 3, "Fallback 2" to 5), keys.captured.toList())
    }

    @Test
    fun `a track the repository already knows is a duplicate`() = runTest {
        coEvery { repository.fingerprints(any()) } returns listOf(TrackFingerprint("A", 3, 10.0, 20.0, 10.002, 20.0))

        val plan = importer.plan(stream(gpx("A" to 3, "B" to 4)))

        assertEquals(listOf(true, false), plan.items.map { it.duplicate })
        assertEquals(listOf("B"), plan.toInsert.map { it.name })
    }

    @Test
    fun `a file without a track with points does not touch the repository`() = runTest {
        val plan = importer.plan(stream("<gpx version=\"1.1\"><trk><name>x</name></trk></gpx>"))

        assertTrue(plan.items.isEmpty())
        coVerify(exactly = 0) { repository.fingerprints(any()) }
    }

    @Test
    fun `a file that is no gpx is refused`() = runTest {
        val e = runCatching { importer.plan(stream("<kml></kml>")) }.exceptionOrNull() as GpxFileException

        assertEquals(GpxFileException.Kind.NOT_GPX, e.kind)
    }

    @Test
    fun `a file above the limit is refused as too large`() = runTest {
        importer.limits = GpxLimits(maxBytes = 50)

        val e = runCatching { importer.plan(stream(gpx("A" to 20))) }.exceptionOrNull() as GpxFileException

        assertEquals(GpxFileException.Kind.TOO_LARGE, e.kind)
    }

    @Test
    fun `read opens the chosen source`() = runTest {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns stream(gpx("A" to 3))
        coEvery { repository.fingerprints(any()) } returns emptyList()

        val plan = importer.read(uri)

        assertEquals(listOf("A"), plan.items.map { it.name })
    }

    @Test
    fun `a source that cannot be opened is an IOException`() = runTest {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { importer.read(uri) } }
    }

    private fun item(name: String, duplicate: Boolean, n: Int = 3) =
        TrackImportItem(name, List(n) { TrackPoint(null, 10.0 + it * 0.001, 20.0, null, null, null) }, 100, null, null, duplicate)

    @Test
    fun `apply writes only the new tracks with their final names`() = runTest {
        val written = slot<List<GpxTrack>>()
        coEvery { repository.importTracks(capture(written), any(), any()) } returns listOf(7L, 8L)
        val plan = TrackImportPlan(listOf(item("Old", true), item("New 1", false), item("New 2", false, 4)), 0, 0)

        val count = importer.apply(plan)

        assertEquals(2, count)
        assertEquals(listOf("New 1", "New 2"), written.captured.map { it.name })
        assertEquals(listOf(3, 4), written.captured.map { it.points.size })
    }

    @Test
    fun `apply with nothing new writes nothing`() = runTest {
        val plan = TrackImportPlan(listOf(item("Old", true)), 0, 0)

        assertEquals(0, importer.apply(plan))

        coVerify(exactly = 0) { repository.importTracks(any(), any(), any()) }
    }
}
