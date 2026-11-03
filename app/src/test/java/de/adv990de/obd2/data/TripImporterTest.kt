package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.adv990de.obd2.tripimport.ParsedTrip
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.SessionSpan
import de.adv990de.obd2.tripimport.TripFileException
import de.adv990de.obd2.tripimport.TripImportPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** [TripImporter]: die Datei im Strom lesen und daraus den Plan machen (nichts wird geschrieben), dann den Plan in einer Transaktion anwenden. */
class TripImporterTest {

    private val now = System.currentTimeMillis()
    private val start = now - 10L * 24 * 3_600_000
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val repository = mockk<SessionRepository>()
    private val importer = TripImporter(context, repository)
    private val source = mockk<Uri>()

    private val header = "timestamp,pid,value,name,source,session\n"

    init {
        every { context.contentResolver } returns resolver
        coEvery { repository.sessionSpans() } returns emptyList()
        coEvery { repository.importTrips(any()) } returns Unit
    }

    private fun file(text: String) { every { resolver.openInputStream(source) } returns ByteArrayInputStream(text.toByteArray()) }

    private fun line(offsetMs: Long, session: String, value: String = "1612.0") = "${start + offsetMs},010C,$value,Engine RPM,ECU,$session\n"

    // --- read ---

    @Test
    fun `read turns the file into a plan - new trips and rejected rows, nothing is written`() = runTest {
        file(header + line(0, "7") + line(1_000, "7") + "kaputt,010C,1.0,n,ECU,7\n" + line(500_000, "9"))

        val plan = importer.read(source, now)

        assertEquals(listOf("7", "9"), plan.trips.map { it.sourceId })
        assertEquals(3, plan.readingCount)
        assertEquals(listOf(RejectedRow(4, "timestamp")), plan.rejected)
        coVerify(exactly = 0) { repository.importTrips(any()) }
    }

    @Test
    fun `trips that touch a trip already in the app count as duplicates, not as new`() = runTest {
        coEvery { repository.sessionSpans() } returns listOf(SessionSpan(start - 5_000, start + 5_000))
        file(header + line(0, "7") + line(1_000, "7") + line(500_000, "9"))

        val plan = importer.read(source, now)

        assertEquals(listOf("9"), plan.trips.map { it.sourceId })
        assertEquals(1, plan.duplicateTrips)
    }

    @Test
    fun `a trip older than the retention is expired`() = runTest {
        file(header + "${now - 100L * 24 * 3_600_000},010C,1.0,n,ECU,1\n")

        val plan = importer.read(source, now)

        assertTrue(plan.trips.isEmpty())
        assertEquals(1, plan.expiredTrips)
    }

    @Test
    fun `a source that cannot be opened is an IOException`() = runTest {
        every { resolver.openInputStream(source) } returns null

        assertThrows(IOException::class.java) { runTestBlocking { importer.read(source, now) } }
    }

    @Test
    fun `an exception of the resolver is passed on unchanged`() = runTest {
        every { resolver.openInputStream(source) } throws SecurityException("no permission")

        assertThrows(SecurityException::class.java) { runTestBlocking { importer.read(source, now) } }
    }

    @Test
    fun `a file that is not a trip export is a TripFileException`() = runTest {
        file("date,type,odometer_km\n2026-10-02,FUEL,40362\n")

        val error = assertThrows(TripFileException::class.java) { runTestBlocking { importer.read(source, now) } }

        assertEquals(TripFileException.Kind.NOT_AN_EXPORT, error.kind)
    }

    @Test
    fun `a file larger than the limit is refused while it is read, not after`() = runTest {
        importer.maxBytes = 200
        val read = intArrayOf(0)
        every { resolver.openInputStream(source) } returns object : InputStream() {
            override fun read(): Int = if (read[0] < 10_000_000) { read[0]++; 'x'.code } else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, 10_000_000 - read[0])
                if (n <= 0) return -1
                read[0] += n
                b.fill('x'.code.toByte(), off, off + n)
                return n
            }
        }

        assertThrows(FileTooLargeException::class.java) { runTestBlocking { importer.read(source, now) } }

        assertTrue("only a few kilobytes were read: ${read[0]}", read[0] < 100_000)
    }

    @Test
    fun `the stream is closed after reading, also after a failure`() = runTest {
        var closed = false
        every { resolver.openInputStream(source) } returns object : InputStream() {
            private val inner = ByteArrayInputStream("not,a,trip\n1,2,3\n".toByteArray())
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() { closed = true }
        }

        assertThrows(TripFileException::class.java) { runTestBlocking { importer.read(source, now) } }

        assertTrue(closed)
    }

    @Test
    fun `a failure while reading the spans of the app is passed on`() = runTest {
        file(header + line(0, "7"))
        coEvery { repository.sessionSpans() } throws IOException("db")

        assertThrows(IOException::class.java) { runTestBlocking { importer.read(source, now) } }
    }

    @Test
    fun `read can be cancelled`() = runTest {
        file(header + line(0, "7"))
        coEvery { repository.sessionSpans() } coAnswers { throw CancellationException("cancelled") }

        val job: Job = launch { importer.read(source, now) }
        job.join()

        assertTrue(job.isCancelled)
    }

    // --- apply ---

    @Test
    fun `apply writes the new trips of the plan and returns their number`() = runTest {
        file(header + line(0, "7") + line(500_000, "9"))
        val plan = importer.read(source, now)
        val written = slot<List<ParsedTrip>>()
        coEvery { repository.importTrips(capture(written)) } returns Unit

        val count = importer.apply(plan)

        assertEquals(2, count)
        assertEquals(plan.trips, written.captured)
    }

    @Test
    fun `apply with nothing new does not touch the database`() = runTest {
        val count = importer.apply(TripImportPlan(emptyList(), duplicateTrips = 3, expiredTrips = 1, rejected = emptyList(), rejectedCount = 0))

        assertEquals(0, count)
        coVerify(exactly = 0) { repository.importTrips(any()) }
    }

    @Test
    fun `a failure while writing is passed on, nothing is reported as written`() = runTest {
        file(header + line(0, "7"))
        val plan = importer.read(source, now)
        coEvery { repository.importTrips(any()) } throws IOException("disk full")

        assertThrows(IOException::class.java) { runTestBlocking { importer.apply(plan) } }
    }

    /** `assertThrows` nimmt keinen suspendierenden Block: der Aufruf laeuft in einem eigenen `runBlocking`. */
    private fun <T> runTestBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
