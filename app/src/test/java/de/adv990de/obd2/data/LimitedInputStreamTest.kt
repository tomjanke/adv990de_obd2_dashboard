package de.adv990de.obd2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** [LimitedInputStream]: liest hoechstens die Grenze, danach [FileTooLargeException] — bei jedem Lesepfad. */
class LimitedInputStreamTest {

    private fun stream(size: Int, limit: Long) = LimitedInputStream(ByteArrayInputStream(ByteArray(size) { 'a'.code.toByte() }), limit)

    @Test
    fun `a stream of exactly the limit is read to the end`() {
        val bytes = stream(100, 100).readBytes()

        assertEquals(100, bytes.size)
    }

    @Test
    fun `one byte more than the limit fails - with the bulk read`() {
        assertThrows(FileTooLargeException::class.java) { stream(101, 100).readBytes() }
    }

    @Test
    fun `one byte more than the limit fails - with single byte reads`() {
        val input = stream(101, 100)
        repeat(100) { input.read() }

        assertThrows(FileTooLargeException::class.java) { input.read() }
    }

    @Test
    fun `at most the limit plus the last chunk is read, not the whole file`() {
        val source = ByteArrayInputStream(ByteArray(1_000_000))
        val input = LimitedInputStream(source, 10_000)

        assertThrows(FileTooLargeException::class.java) { input.readBytes() }

        assertEquals("the source was not read to its end", true, source.available() > 900_000)
    }

    @Test
    fun `a skipped byte counts like a read one`() {
        val input = stream(200, 100)

        input.skip(90)

        assertEquals(10, input.readNBytes(10).size)
        assertThrows(FileTooLargeException::class.java) { input.read() }
    }

    @Test
    fun `the end of the stream is not counted and the exception is an IOException`() {
        val input = stream(0, 0)

        assertEquals(-1, input.read())
        assertEquals(-1, input.read(ByteArray(8), 0, 8))
        val thrown: Throwable = FileTooLargeException("x")
        assertTrue(thrown is IOException)
    }

    @Test
    fun `mark and reset are not supported - a reset would let a file be read twice for free`() {
        val input = stream(10, 5)

        assertFalse(input.markSupported())
        assertThrows(IOException::class.java) { input.reset() }
    }
}
