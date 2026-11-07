package de.adv990de.obd2.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class PackageWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entry(name: String, text: String) = PackageEntry(name) { it.write(text) }

    private fun writeTo(file: File, createdMs: Long, entries: List<PackageEntry>) = runTest {
        FileOutputStream(file).use { writePackage(it, createdMs, entries) }
    }

    @Test
    fun theOrderOfTheEntriesStays() {
        val file = tmp.newFile("a.zip")
        writeTo(file, 1_700_000_000_000, listOf(entry("manifest.json", "{}"), entry("trips.csv", "a"), entry("logbook.csv", "b")))
        ZipFile(file).use { zip -> assertEquals(listOf("manifest.json", "trips.csv", "logbook.csv"), zip.entries().asSequence().map { it.name }.toList()) }
    }

    @Test
    fun roundTripWithTheReaderKeepsUtf8() {
        val file = tmp.newFile("b.zip")
        val text = "Grüße äöü 😀 ñ"
        writeTo(file, 1_700_000_000_000, listOf(entry("logbook.csv", text)))
        PackageReader.open(file).use { reader ->
            assertEquals(text, reader.openEntry("logbook.csv")!!.use { String(it.readBytes(), Charsets.UTF_8) })
        }
    }

    @Test
    fun everyEntryCarriesTheCreationTime() {
        val file = tmp.newFile("c.zip")
        val created = 1_700_000_000_000
        writeTo(file, created, listOf(entry("trips.csv", "a"), entry("logbook.csv", "b")))
        ZipFile(file).use { zip ->
            for (e in zip.entries()) assertTrue("${e.name} ${e.time}", kotlin.math.abs(e.time - created) <= 2_000)
        }
    }

    @Test
    fun anErrorInASecondWriteGoesOutUnchanged() = runTest {
        val boom = IllegalStateException("boom")
        try {
            writePackage(ByteArrayOutputStream(), 0, listOf(entry("trips.csv", "a"), PackageEntry("logbook.csv") { throw boom }))
            fail()
        } catch (e: IllegalStateException) {
            assertEquals(boom, e)
        }
    }

    @Test
    fun theOutputStaysOpen() = runTest {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        writePackage(out, 0, listOf(entry("trips.csv", "a")))
        assertTrue(!closed)
        out.write(1) // still usable
    }
}
