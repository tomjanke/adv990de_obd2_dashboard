package de.adv990de.obd2.weather

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
