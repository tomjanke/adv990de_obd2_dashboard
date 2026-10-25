package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.ImportReason
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import java.time.LocalDate

/** [LogbookImporter]: die Datei lesen und daraus den Plan machen (nichts wird geschrieben), dann den Plan in einer Transaktion anwenden. */
class LogbookImporterTest {

    private val today = LocalDate.of(2026, 10, 25)
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val repository = mockk<VehicleLogRepository>()
    private val importer = LogbookImporter(context, repository)
    private val source = mockk<Uri>()

    private val header = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n"

    init {
        every { context.contentResolver } returns resolver
        coEvery { repository.all() } returns emptyList()
        coEvery { repository.importEntries(any()) } returns Unit
    }

    private fun file(text: String) { every { resolver.openInputStream(source) } returns ByteArrayInputStream(text.toByteArray()) }

    private fun fuel(km: Int) = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, 2), odometerKm = km, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = null, brand = null,
    )

    // --- read ---

    @Test
    fun `read turns the file into a plan - new entries, rejected rows, nothing is written`() = runTest {
        file(header + "2026-10-02,FUEL,40362,14370,1,27.43,,\n" + "kaputt,FUEL,1,1,1,,,\n")

        val plan = importer.read(source, today)

        assertEquals(listOf(fuel(40_362)), plan.toInsert)
        assertEquals(1, plan.rejected.size)
        assertEquals("Kopfzeile = Zeile 1, die kaputte Zeile ist die dritte", 3, plan.rejected.single().line)
        assertTrue(plan.rejected.single().reasons.single() is ImportReason.BadValue)
        coVerify(exactly = 0) { repository.importEntries(any()) }
    }

    @Test
    fun `entries that are already in the logbook count as duplicates, not as new`() = runTest {
        coEvery { repository.all() } returns listOf(fuel(40_362).copy(id = 3))
        file(header + "2026-10-02,FUEL,40362,14370,1,27.43,,\n" + "2026-10-02,FUEL,40500,14370,1,27.43,,\n")

        val plan = importer.read(source, today)

        assertEquals(1, plan.duplicates)
        assertEquals(listOf(40_500), plan.toInsert.map { it.odometerKm })
    }

    @Test
    fun `a file with a byte order mark and Windows line ends is read`() = runTest {
        file("﻿" + header.replace("\n", "\r\n") + "2026-10-02,FUEL,40362,14370,1,27.43,,\r\n")

        assertEquals(1, importer.read(source, today).toInsert.size)
    }

    @Test
    fun `a file without a required column is not a logbook - the format error reaches the caller`() {
        file("date,type\n2026-10-02,FUEL\n")

        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { importer.read(source, today) } }
    }

    @Test
    fun `a source that cannot be opened is an IOException - and not the too-large kind`() {
        every { resolver.openInputStream(source) } returns null

        val failure = runCatching { kotlinx.coroutines.runBlocking { importer.read(source, today) } }.exceptionOrNull()

        assertTrue("$failure", failure is IOException && failure !is FileTooLargeException)
    }

    /** Ein Strom, der nie endet und zaehlt, wie viel gelesen wurde. */
    private class EndlessStream(val byte: Int = ' '.code) : InputStream() {
        var bytesRead = 0L
        override fun read(): Int { bytesRead++; return byte }
        override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, byte.toByte()); bytesRead += len; return len }
    }

    @Test
    fun `the size limit is 5 MiB - the number the spec and the dialog texts name`() {
        assertEquals(5 * 1024 * 1024, MAX_IMPORT_BYTES)
    }

    @Test
    fun `a file larger than 5 MiB is refused - and not read to the end`() {
        val endless = EndlessStream()
        every { resolver.openInputStream(source) } returns endless

        val failure = runCatching { kotlinx.coroutines.runBlocking { importer.read(source, today) } }.exceptionOrNull()

        assertTrue("$failure", failure is FileTooLargeException)
        assertTrue("hoechstens die Grenze plus ein Byte, gelesen: ${endless.bytesRead}", endless.bytesRead <= MAX_IMPORT_BYTES + 1 + 8192)
    }

    @Test
    fun `exactly 5 MiB is still read - it fails as a format problem, not as too large`() {
        every { resolver.openInputStream(source) } returns ByteArrayInputStream(ByteArray(MAX_IMPORT_BYTES) { ' '.code.toByte() })

        val failure = runCatching { kotlinx.coroutines.runBlocking { importer.read(source, today) } }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun `a cancelled read stops and writes nothing`() = runTest {
        lateinit var job: Job
        // Der Strom bricht den Auftrag beim ersten Lesen ab; die naechste Runde der Leseschleife muss das merken.
        every { resolver.openInputStream(source) } returns object : InputStream() {
            override fun read(): Int = ' '.code
            override fun read(b: ByteArray, off: Int, len: Int): Int { job.cancel(); java.util.Arrays.fill(b, off, off + len, ' '.code.toByte()); return len }
        }

        job = launch { importer.read(source, today) }
        job.join()

        assertTrue(job.isCancelled)
        coVerify(exactly = 0) { repository.importEntries(any()) }
    }

    // --- apply ---

    @Test
    fun `apply writes the new entries in one call and reports how many`() = runTest {
        val plan = ImportPlan(toInsert = listOf(fuel(1), fuel(2)), duplicates = 5, rejected = emptyList(), warnings = 0)

        val written = importer.apply(plan)

        assertEquals(2, written)
        coVerify(exactly = 1) { repository.importEntries(plan.toInsert) }
    }

    @Test
    fun `an empty plan writes nothing`() = runTest {
        assertEquals(0, importer.apply(ImportPlan(emptyList(), duplicates = 3, rejected = emptyList(), warnings = 0)))

        coVerify(exactly = 0) { repository.importEntries(any()) }
    }

    @Test
    fun `a failing write reaches the caller - nothing was imported`() = runTest {
        coEvery { repository.importEntries(any()) } throws IllegalStateException("disk full")

        val failure = runCatching { importer.apply(ImportPlan(listOf(fuel(1)), 0, emptyList(), 0)) }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
    }
}
