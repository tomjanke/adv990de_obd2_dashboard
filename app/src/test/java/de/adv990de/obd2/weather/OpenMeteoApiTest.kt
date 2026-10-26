package de.adv990de.obd2.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class OpenMeteoApiTest {

    /** [requestedUrls] records every URL the API opens, so tests can check what leaves the device. */
    private val requestedUrls = mutableListOf<URL>()
    /** Every connection the API opened — to check each one got disconnected. */
    private val openedConnections = mutableListOf<FakeHttpURLConnection>()

    private fun fakeApi(
        responseBody: String,
        responseCode: Int = 200,
        responseCodeFailure: IOException? = null,
    ): OpenMeteoApi =
        OpenMeteoApi(openConnection = { url ->
            requestedUrls += url
            object : FakeHttpURLConnection() {
                override fun getInputStream() = responseBody.byteInputStream()
                override fun getResponseCode(): Int {
                    responseCodeFailure?.let { throw it }
                    return responseCode
                }
            }.also { openedConnections += it }
        })

    private val okBody = """{"current":{"temperature_2m":13.5,"time":"2026-09-27T12:00"}}"""

    @Test
    fun `parses a well-formed current-temperature response`() = runTest {
        val api = fakeApi(okBody)
        assertEquals(13.5f, api.currentTemperatureC(52.52, 13.41)!!, 0.01f)
    }

    @Test
    fun `a non-200 response yields null`() = runTest {
        val api = fakeApi("""{"error":true,"reason":"bad request"}""", responseCode = 400)
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `malformed JSON yields null instead of throwing`() = runTest {
        val api = fakeApi("""not json at all""")
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `missing temperature_2m field yields null`() = runTest {
        val api = fakeApi("""{"current":{"time":"2026-09-27T12:00"}}""")
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `a network failure yields null instead of throwing`() = runTest {
        val api = fakeApi(okBody, responseCodeFailure = IOException("unexpected end of stream"))
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `a cancellation is passed on - it is not an unusable answer - and the connection is still closed`() = runTest {
        val api = OpenMeteoApi(openConnection = {
            object : FakeHttpURLConnection() {
                override fun getResponseCode(): Int = throw CancellationException("gone")
            }.also { openedConnections += it }
        })

        val thrown = runCatching { api.currentTemperatureC(52.52, 13.41) }.exceptionOrNull()

        assertTrue("CancellationException erwartet, war: $thrown", thrown is CancellationException)
        assertTrue(openedConnections.single().disconnected)
    }

    // ── Non-finite values: must never reach roundToInt() in the UI ──────────────────────────

    @Test
    fun `a NaN temperature is treated as an unusable response`() = runTest {
        // org.json parses the string "NaN" to Double.NaN via getDouble().
        val api = fakeApi("""{"current":{"temperature_2m":"NaN"}}""")
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `a value beyond Float range is treated as an unusable response`() = runTest {
        // A finite double, but Infinity once narrowed to Float.
        val api = fakeApi("""{"current":{"temperature_2m":1e39}}""")
        assertNull(api.currentTemperatureC(52.52, 13.41))
    }

    // ── The connection is released on every path ────────────────────────────────────────────

    @Test
    fun `the connection is disconnected after a successful response`() = runTest {
        fakeApi(okBody).currentTemperatureC(52.52, 13.41)
        assertTrue(openedConnections.single().disconnected)
    }

    @Test
    fun `the connection is disconnected after a non-200 response`() = runTest {
        // The error body is never read here — without disconnect() the socket would linger.
        fakeApi("""{"error":true}""", responseCode = 503).currentTemperatureC(52.52, 13.41)
        assertTrue(openedConnections.single().disconnected)
    }

    @Test
    fun `the connection is disconnected after malformed JSON or a network failure`() = runTest {
        fakeApi("""not json at all""").currentTemperatureC(52.52, 13.41)
        fakeApi(okBody, responseCodeFailure = IOException("reset")).currentTemperatureC(52.52, 13.41)

        assertEquals(2, openedConnections.size)
        assertTrue(openedConnections.all { it.disconnected })
    }

    // ── Coordinate rounding (privacy): ~1.1 km is plenty for weather ────────────────────────

    @Test
    fun `coordinates are rounded to two decimals before they leave the device`() = runTest {
        fakeApi(okBody).currentTemperatureC(52.520008, 13.404954)

        assertEquals(
            "latitude=52.52&longitude=13.40&current=temperature_2m",
            requestedUrls.single().query,
        )
    }

    @Test
    fun `negative coordinates keep their sign when rounded`() = runTest {
        fakeApi(okBody).currentTemperatureC(-33.868820, -151.209295)

        assertEquals(
            "latitude=-33.87&longitude=-151.21&current=temperature_2m",
            requestedUrls.single().query,
        )
    }

    @Test
    fun `rounding uses a dot even when the device locale uses a decimal comma`() = runTest {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY) // String.format without Locale.ROOT would give "52,52"
        try {
            fakeApi(okBody).currentTemperatureC(52.520008, 13.404954)
        } finally {
            Locale.setDefault(previous)
        }

        assertEquals(
            "latitude=52.52&longitude=13.40&current=temperature_2m",
            requestedUrls.single().query,
        )
    }

    // ── Request headers (SEC-9) ─────────────────────────────────────────────────────────────

    @Test
    fun `the request identifies the app with a User-Agent and asks for JSON`() = runTest {
        val api = OpenMeteoApi(userAgent = "OBD2Monitor/9.9.9", openConnection = { url ->
            requestedUrls += url
            object : FakeHttpURLConnection() {
                override fun getInputStream() = okBody.byteInputStream()
                override fun getResponseCode() = 200
            }.also { openedConnections += it }
        })
        api.currentTemperatureC(52.52, 13.41)

        val connection = openedConnections.single()
        assertEquals("OBD2Monitor/9.9.9", connection.getRequestProperty("User-Agent"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
    }

    @Test
    fun `without an explicit User-Agent the app still names itself - never the JVM default`() = runTest {
        fakeApi(okBody).currentTemperatureC(52.52, 13.41)
        val ua = openedConnections.single().getRequestProperty("User-Agent")
        assertEquals(DEFAULT_USER_AGENT, ua)
        assertTrue(ua.startsWith("OBD2Monitor"))
    }

    @Test
    fun `the User-Agent carries the app and its version only - no device or location data`() {
        assertEquals("OBD2Monitor/1.24.10", weatherUserAgent("1.24.10"))
        assertEquals("OBD2Monitor/1.24.10-debug", weatherUserAgent("1.24.10-debug"))
        // Ein leerer oder fehlender Versionsname ergibt nie "OBD2Monitor/".
        assertEquals(DEFAULT_USER_AGENT, weatherUserAgent(""))
        assertEquals(DEFAULT_USER_AGENT, weatherUserAgent("  "))
    }

    // ── Response size is bounded (SEC-9) ────────────────────────────────────────────────────

    @Test
    fun `a response exactly at the size limit is still parsed`() = runTest {
        val padded = okBody + " ".repeat(MAX_RESPONSE_BYTES - okBody.toByteArray().size)
        assertEquals(MAX_RESPONSE_BYTES, padded.toByteArray().size)

        assertEquals(13.5f, fakeApi(padded).currentTemperatureC(52.52, 13.41)!!, 0.01f)
    }

    @Test
    fun `a response one byte over the size limit yields null`() = runTest {
        val padded = okBody + " ".repeat(MAX_RESPONSE_BYTES + 1 - okBody.toByteArray().size)
        assertEquals(MAX_RESPONSE_BYTES + 1, padded.toByteArray().size)

        assertNull(fakeApi(padded).currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `an endless response is cut off at the limit instead of being read to the end`() = runTest {
        var bytesRead = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { bytesRead++; return ' '.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, ' '.code.toByte()); bytesRead += len; return len }
        }
        val api = OpenMeteoApi(openConnection = { url ->
            requestedUrls += url
            object : FakeHttpURLConnection() {
                override fun getInputStream() = endless
                override fun getResponseCode() = 200
            }.also { openedConnections += it }
        })

        assertNull(api.currentTemperatureC(52.52, 13.41))
        assertTrue("gelesen: $bytesRead", bytesRead <= MAX_RESPONSE_BYTES + 1L)
        assertTrue(openedConnections.single().disconnected)
    }

    @Test
    fun `a declared Content-Length beyond the limit is refused before any byte is read`() = runTest {
        var streamOpened = false
        val api = OpenMeteoApi(openConnection = { url ->
            requestedUrls += url
            object : FakeHttpURLConnection() {
                override fun getContentLengthLong() = 50_000_000L
                override fun getInputStream(): java.io.InputStream { streamOpened = true; return okBody.byteInputStream() }
                override fun getResponseCode() = 200
            }.also { openedConnections += it }
        })

        assertNull(api.currentTemperatureC(52.52, 13.41))
        assertEquals(false, streamOpened)
        assertTrue(openedConnections.single().disconnected)
    }

    @Test
    fun `multi-byte characters count as bytes, not characters`() = runTest {
        // Ein Umlaut sind zwei Bytes: die Grenze gilt fuer die Bytes auf der Leitung.
        val body = """{"current":{"temperature_2m":13.5,"note":"${"ä".repeat(MAX_RESPONSE_BYTES / 2)}"}}"""
        assertTrue(body.length < MAX_RESPONSE_BYTES && body.toByteArray().size > MAX_RESPONSE_BYTES)

        assertNull(fakeApi(body).currentTemperatureC(52.52, 13.41))
    }

    @Test
    fun `readBounded returns the bytes up to the limit and null beyond it`() {
        assertEquals("abc", String(readBounded("abc".byteInputStream(), 3)!!))
        assertEquals("", String(readBounded("".byteInputStream(), 3)!!))
        assertNull(readBounded("abcd".byteInputStream(), 3))
    }
}

/** Minimal fake so tests don't need a real socket — every method not overridden throws, so a
 *  test that touches an unstubbed path fails loudly instead of silently returning a default.
 *  [disconnected] records whether the API released the connection. */
private abstract class FakeHttpURLConnection : HttpURLConnection(null) {
    var disconnected = false
        private set

    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
}
