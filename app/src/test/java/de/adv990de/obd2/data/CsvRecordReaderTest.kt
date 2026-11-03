package de.adv990de.obd2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/** [CsvRecordReader]: dieselbe Zustandsmaschine wie bisher `parseCsv`, aber Datensatz fuer Datensatz aus einem Reader (der Fahrten-Import liest sehr grosse Dateien). */
class CsvRecordReaderTest {

    /** Ein Reader, der hoechstens ein Zeichen je Aufruf liefert: Vorausschau ueber eine Puffergrenze hinweg (`""`, `\r\n`) darf nichts verschlucken. */
    private class OneCharReader(private val text: String) : Reader() {
        private var pos = 0
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (pos >= text.length) return -1
            if (len == 0) return 0
            cbuf[off] = text[pos++]
            return 1
        }
        override fun close() = Unit
    }

    private fun readAll(reader: Reader): List<CsvRecord> {
        val csv = CsvRecordReader(reader)
        return generateSequence { csv.next() }.toList()
    }

    private fun fieldsOf(text: String) = readAll(StringReader(text)).map { it.fields }

    private fun readLimited(text: String, maxFieldChars: Int, maxFields: Int): List<CsvRecord> {
        val csv = CsvRecordReader(StringReader(text), maxFieldChars, maxFields)
        return generateSequence { csv.next() }.toList()
    }

    @Test
    fun `plain records and the line each one starts in`() {
        val records = readAll(StringReader("a,b\nc,d\ne,f\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f")), records.map { it.fields })
        assertEquals(listOf(1, 2, 3), records.map { it.line })
    }

    @Test
    fun `quoted fields carry commas, doubled quotes and line breaks`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x,y", "say \"hi\"", "z")), fieldsOf("a,b,c\n\"x,y\",\"say \"\"hi\"\"\",z\n"))
        val records = readAll(StringReader("a,b\n\"one\ntwo\",x\nlast,y\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("one\ntwo", "x"), listOf("last", "y")), records.map { it.fields })
        assertEquals("the line break inside the field counts: the next record starts in line 4", listOf(1, 2, 4), records.map { it.line })
    }

    @Test
    fun `a quote in the middle of an unquoted field is an ordinary character`() {
        assertEquals(listOf(listOf("5\" bolt", "x")), fieldsOf("5\" bolt,x\n"))
    }

    @Test
    fun `line endings LF, CRLF and CR, and a missing one at the end`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), fieldsOf("a,b\r\nc,d"))
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), fieldsOf("a\rb\rc"))
        val records = readAll(StringReader("a\r\nb\r\n\r\nc\n"))
        assertEquals(listOf(listOf("a"), listOf("b"), listOf(""), listOf("c")), records.map { it.fields })
        assertEquals(listOf(1, 2, 3, 4), records.map { it.line })
    }

    @Test
    fun `a byte order mark at the start is skipped`() {
        assertEquals(listOf(listOf("a", "b")), fieldsOf("﻿a,b\n"))
        assertEquals("only at the very start", listOf(listOf("a"), listOf("﻿b")), fieldsOf("a\n﻿b\n"))
    }

    @Test
    fun `empty fields and blank lines`() {
        assertEquals(listOf(listOf("a", "", "c")), fieldsOf("a,,c\n"))
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), fieldsOf("a\n\nb\n"))
        assertEquals(listOf(listOf("a", "")), fieldsOf("a,\n"))
    }

    @Test
    fun `an empty input has no record`() {
        assertNull(CsvRecordReader(StringReader("")).next())
        assertNull("a byte order mark alone is not a record", CsvRecordReader(StringReader("﻿")).next())
    }

    @Test
    fun `next keeps answering null after the end`() {
        val csv = CsvRecordReader(StringReader("a\n"))
        assertEquals(listOf("a"), csv.next()!!.fields)
        assertNull(csv.next())
        assertNull(csv.next())
    }

    @Test
    fun `a quote that is never closed is an error naming the line it starts in`() {
        val error = assertThrows(IllegalArgumentException::class.java) { fieldsOf("a,b\n\"never closed,x\n") }
        assertEquals("A quoted field starting in line 2 is never closed", error.message)
    }

    @Test
    fun `an error comes at the end of the input, after the records before it were handed out`() {
        val csv = CsvRecordReader(StringReader("a\nb\n\"open"))
        assertEquals(listOf("a"), csv.next()!!.fields)
        assertEquals(listOf("b"), csv.next()!!.fields)
        assertThrows(IllegalArgumentException::class.java) { csv.next() }
    }

    @Test
    fun `a reader that hands out one character at a time gives the same records`() {
        val text = "﻿id,note\r\n1,\"say \"\"hi\"\", then\r\nleave\"\r\n2,x\n\n3,\"\"\"\"\r"
        assertEquals(readAll(StringReader(text)), readAll(OneCharReader(text)))
        assertEquals(
            listOf(listOf("id", "note"), listOf("1", "say \"hi\", then\r\nleave"), listOf("2", "x"), listOf(""), listOf("3", "\"")),
            fieldsOf(text),
        )
    }

    @Test
    fun `a long input is read record by record without end`() {
        val rows = 50_000
        val text = buildString { repeat(rows) { append(it).append(",a,\"b,c\"\n") } }
        val csv = CsvRecordReader(StringReader(text))
        var count = 0
        var last: CsvRecord? = null
        while (true) { last = csv.next() ?: break; count++ }
        assertEquals(rows, count)
        assertEquals(listOf((rows - 1).toString(), "a", "b,c"), last!!.fields)
        assertEquals(rows, last.line)
    }

    @Test
    fun `a field longer than the limit is an error - a file without line breaks must not become one huge field`() {
        assertEquals(listOf(listOf("abcde", "xy")), readLimited("abcde,xy\n", maxFieldChars = 5, maxFields = 100).map { it.fields })
        assertThrows(IllegalArgumentException::class.java) { readLimited("abcdef,xy\n", maxFieldChars = 5, maxFields = 100) }
        assertThrows("also inside quotes, line breaks included", IllegalArgumentException::class.java) { readLimited("\"abc\ndef\",xy\n", maxFieldChars = 5, maxFields = 100) }
        assertEquals("a doubled quote counts once", listOf(listOf("a\"b\"c")), readLimited("\"a\"\"b\"\"c\"\n", maxFieldChars = 5, maxFields = 100).map { it.fields })
    }

    @Test
    fun `a record with more fields than the limit is an error, exactly the limit is fine`() {
        assertEquals(listOf(listOf("a", "b", "c")), readLimited("a,b,c\n", maxFieldChars = 100, maxFields = 3).map { it.fields })
        assertEquals(listOf(listOf("a", "", "")), readLimited("a,,\n", maxFieldChars = 100, maxFields = 3).map { it.fields })
        val error = assertThrows(IllegalArgumentException::class.java) { readLimited("a\nb,c,d,e\n", maxFieldChars = 100, maxFields = 3) }
        assertEquals("The record starting in line 2 has more than 3 fields", error.message)
    }

    @Test
    fun `the limits of the defaults leave room for every file the app writes`() {
        assertEquals(64 * 1024, DEFAULT_MAX_FIELD_CHARS)
        assertEquals(256, DEFAULT_MAX_FIELDS)
        val wide = (1..DEFAULT_MAX_FIELDS).joinToString(",") { "f$it" } + "\n"
        assertEquals(DEFAULT_MAX_FIELDS, parseCsv(wide).single().fields.size)
        assertThrows(IllegalArgumentException::class.java) { parseCsv((1..DEFAULT_MAX_FIELDS + 1).joinToString(",") { "f$it" }) }
        assertEquals(DEFAULT_MAX_FIELD_CHARS, parseCsv("x".repeat(DEFAULT_MAX_FIELD_CHARS)).single().fields.single().length)
        assertThrows(IllegalArgumentException::class.java) { parseCsv("x".repeat(DEFAULT_MAX_FIELD_CHARS + 1)) }
    }

    @Test
    fun `parseCsv reads a whole text with the same reader`() {
        assertEquals(listOf(CsvRecord(1, listOf("a", "b")), CsvRecord(2, listOf("c", "d"))), parseCsv("a,b\nc,d\n"))
        assertEquals(emptyList<CsvRecord>(), parseCsv(""))
    }
}
