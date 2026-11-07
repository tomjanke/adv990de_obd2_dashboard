package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.FileTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = tmp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun PackageReader.read(name: String): String? = openEntry(name)?.use { String(it.readBytes(), Charsets.UTF_8) }

    @Test
    fun knownEntriesAreReadInAnyOrder() {
        val file = zip("logbook.csv" to text("L"), "trips.csv" to text("T"))
        PackageReader.open(file).use {
            assertEquals("T", it.read("trips.csv"))
            assertEquals("L", it.read("logbook.csv"))
            assertTrue(it.has("trips.csv"))
        }
    }

    @Test
    fun aMissingEntryIsNull() {
        PackageReader.open(zip("trips.csv" to text("T"))).use {
            assertNull(it.openEntry("logbook.csv"))
            assertFalse(it.has("logbook.csv"))
        }
    }

    @Test
    fun pathsAndUnknownNamesAreIgnored() {
        val file = zip(
            "../trips.csv" to text("x"), "/logbook.csv" to text("x"), "a/b.csv" to text("x"),
            "a\\applog.csv" to text("x"), "evil.bin" to text("x"), "TRIPS.CSV" to text("x"),
            "trips.csv/" to ByteArray(0), "settings.json/x" to text("x"),
        )
        PackageReader.open(file).use {
            for (n in listOf("../trips.csv", "/logbook.csv", "a/b.csv", "a\\applog.csv", "evil.bin", "TRIPS.CSV", "trips.csv", "settings.json", "settings.json/x")) {
                assertFalse(n, it.has(n))
                assertNull(n, it.openEntry(n))
            }
        }
    }

    @Test
    fun aDirectoryNamedLikeAKnownFileIsNotAFile() {
        val file = tmp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            z.putNextEntry(ZipEntry("manifest.json/")); z.closeEntry()
        }
        PackageReader.open(file).use { assertFalse(it.has("manifest.json")) }
    }

    @Test
    fun aDuplicateKnownNameTakesTheFirst() {
        val bytes = zip("trips.csv" to text("FIRST"), "trips.cXv" to text("OTHER")).readBytes()
        val patched = String(bytes, Charsets.ISO_8859_1).replace("trips.cXv", "trips.csv").toByteArray(Charsets.ISO_8859_1)
        val file = tmp.newFile().also { it.writeBytes(patched) }
        PackageReader.open(file).use { assertEquals("FIRST", it.read("trips.csv")) }
    }

    @Test
    fun moreEntriesThanAllowedIsNotAPackage() {
        val names = (1..5).map { "f$it.bin" to text("x") }.toTypedArray()
        val file = zip(*names)
        try {
            PackageReader.open(file, maxEntries = 4).close()
            fail()
        } catch (_: NotAPackageException) {
        }
    }

    @Test
    fun exactlyTheAllowedNumberOfEntriesIsFine() {
        val names = (1..4).map { "f$it.bin" to text("x") }.toTypedArray()
        PackageReader.open(zip(*names), maxEntries = 4).close()
    }

    @Test
    fun aBombStopsAtTheLimit() {
        val file = zip("trips.csv" to ByteArray(10 * 1024 * 1024))
        assertTrue("the bomb must compress", file.length() < 100 * 1024)
        PackageReader.open(file, limitOf = { 1024L * 1024 }).use { reader ->
            var delivered = 0L
            try {
                reader.openEntry("trips.csv")!!.use { delivered = it.drain() }
                fail("expected FileTooLargeException")
            } catch (e: DrainedTooMuch) {
                assertTrue(e.cause is FileTooLargeException)
                assertTrue("delivered ${e.delivered}", e.delivered <= 1024L * 1024)
            }
        }
    }

    @Test
    fun anEntryExactlyAtTheLimitIsFine() {
        val file = zip("trips.csv" to ByteArray(100))
        PackageReader.open(file, limitOf = { 100L }).use { assertEquals(100, it.openEntry("trips.csv")!!.use { s -> s.readBytes().size }) }
    }

    @Test
    fun theTotalLimitCoversAllEntriesTogether() {
        val file = zip("trips.csv" to ByteArray(60), "logbook.csv" to ByteArray(60))
        PackageReader.open(file, maxTotalBytes = 100, limitOf = { 1000L }).use { reader ->
            assertEquals(60, reader.openEntry("trips.csv")!!.use { it.readBytes().size })
            try {
                reader.openEntry("logbook.csv")!!.use { it.readBytes() }
                fail()
            } catch (_: FileTooLargeException) {
            }
        }
    }

    @Test
    fun openingTheSameEntryTwiceCountsTwice() {
        val file = zip("trips.csv" to ByteArray(60))
        PackageReader.open(file, maxTotalBytes = 100, limitOf = { 1000L }).use { reader ->
            reader.openEntry("trips.csv")!!.use { it.readBytes() }
            try {
                reader.openEntry("trips.csv")!!.use { it.readBytes() }
                fail()
            } catch (_: FileTooLargeException) {
            }
        }
    }

    @Test
    fun theManifestIsNullWhenMissingBrokenOrForeign() {
        PackageReader.open(zip("trips.csv" to text("T"))).use { assertNull(it.manifest()) }
        PackageReader.open(zip("manifest.json" to text("not json"))).use { assertNull(it.manifest()) }
        PackageReader.open(zip("manifest.json" to text("""{"format":1,"app_id":"x","parts":{}}"""))).use { assertNull(it.manifest()) }
    }

    @Test
    fun aValidManifestIsRead() {
        val manifest = PackageManifest(1, PackageFormat.APP_ID, "1.0", 5, mapOf(DataPart.TRIPS to 2))
        PackageReader.open(zip("manifest.json" to text(manifest.toJson()))).use { assertEquals(manifest, it.manifest()) }
    }

    @Test
    fun aManifestOverItsLimitIsNull() {
        val big = ByteArray(300 * 1024) { 'a'.code.toByte() }
        PackageReader.open(zip("manifest.json" to big)).use { assertNull(it.manifest()) }
    }

    @Test
    fun aTextFileIsNotAPackage() {
        val file = tmp.newFile().also { it.writeText("timestamp,pid,value\n") }
        try {
            PackageReader.open(file).close()
            fail()
        } catch (_: NotAPackageException) {
        }
    }

    @Test
    fun aTruncatedZipIsNotAPackage() {
        val bytes = zip("trips.csv" to ByteArray(5000) { it.toByte() }).readBytes()
        val file = tmp.newFile().also { it.writeBytes(bytes.copyOf(bytes.size / 2)) }
        try {
            PackageReader.open(file).close()
            fail()
        } catch (_: NotAPackageException) {
        }
    }

    @Test
    fun aMissingFileIsNotAPackage() {
        try {
            PackageReader.open(File(tmp.root, "nope.zip")).close()
            fail()
        } catch (_: NotAPackageException) {
        }
        assertNotNull(1)
    }

    private class DrainedTooMuch(val delivered: Long, cause: Throwable) : RuntimeException(cause)

    /** Reads to the end; reports how many bytes arrived before a failure. */
    private fun InputStream.drain(): Long {
        var total = 0L
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = read(buf)
                if (n < 0) return total
                total += n
            }
        } catch (e: java.io.IOException) {
            throw DrainedTooMuch(total, e)
        }
    }
}
