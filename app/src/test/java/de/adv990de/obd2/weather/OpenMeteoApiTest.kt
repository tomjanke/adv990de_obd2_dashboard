package de.adv990de.obd2.weather

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection

class OpenMeteoApiTest {

    private fun fakeApi(responseBody: String, responseCode: Int = 200): OpenMeteoApi =
        OpenMeteoApi(openConnection = { _ ->
            object : FakeHttpURLConnection() {
                override fun getInputStream() = responseBody.byteInputStream()
                override fun getResponseCode() = responseCode
            }
        })

    @Test
    fun `parses a well-formed current-temperature response`() = runTest {
        val api = fakeApi("""{"current":{"temperature_2m":13.5,"time":"2026-09-27T12:00"}}""")
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
}

/** Minimal fake so tests don't need a real socket — every method not overridden throws, so a
 *  test that touches an unstubbed path fails loudly instead of silently returning a default. */
private abstract class FakeHttpURLConnection : HttpURLConnection(null) {
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
}
