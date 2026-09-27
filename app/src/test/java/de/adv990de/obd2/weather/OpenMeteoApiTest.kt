package de.adv990de.obd2.weather

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class OpenMeteoApiTest {

    /** [requestedUrls] records every URL the API opens, so tests can check what leaves the device. */
    private val requestedUrls = mutableListOf<URL>()

    private fun fakeApi(responseBody: String, responseCode: Int = 200): OpenMeteoApi =
        OpenMeteoApi(openConnection = { url ->
            requestedUrls += url
            object : FakeHttpURLConnection() {
                override fun getInputStream() = responseBody.byteInputStream()
                override fun getResponseCode() = responseCode
            }
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
 *  test that touches an unstubbed path fails loudly instead of silently returning a default. */
private abstract class FakeHttpURLConnection : HttpURLConnection(null) {
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
}
