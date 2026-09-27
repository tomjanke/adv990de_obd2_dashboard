package de.adv990de.obd2.weather

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.location.CurrentLocationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {

    /** Every message logged, in order — for asserting what was logged, not just how often. */
    private val loggedMessages = mutableListOf<String>()
    private val appLogRepository = mockk<AppLogRepository> {
        coEvery { log(LogLevel.WARN, capture(loggedMessages)) } just Runs
    }

    /** Mutable so a test can change the situation between polls (permission granted mid-session). */
    private var permission = true
    private var location: Pair<Double, Double>? = 52.52 to 13.41
    private var apiResult: Float? = 18.0f

    private val locationProvider = mockk<CurrentLocationProvider> {
        every { hasPermission() } answers { permission }
        every { lastKnownLocation() } answers { location }
    }
    private val api = mockk<WeatherApi> {
        coEvery { currentTemperatureC(any(), any()) } answers { apiResult }
    }
    private val repo = WeatherRepository(locationProvider, api, appLogRepository)

    // ── results (unchanged behavior) ──────────────────────────────────────────

    @Test
    fun `returns the API's temperature when location and API both succeed`() = runTest {
        assertEquals(18.0f, repo.fetchCurrentTemperatureC()!!, 0.01f)
        coVerify(exactly = 1) { api.currentTemperatureC(52.52, 13.41) }
    }

    @Test
    fun `returns null without a location fix`() = runTest {
        location = null
        assertNull(repo.fetchCurrentTemperatureC())
    }

    @Test
    fun `returns null without location permission`() = runTest {
        permission = false
        assertNull(repo.fetchCurrentTemperatureC())
    }

    @Test
    fun `returns null when the API call fails`() = runTest {
        apiResult = null
        assertNull(repo.fetchCurrentTemperatureC())
    }

    // ── logging: one distinct, actionable line per cause, only on a change ────

    @Test
    fun `a success logs nothing`() = runTest {
        repeat(3) { repo.fetchCurrentTemperatureC() }
        assertTrue(loggedMessages.isEmpty())
    }

    @Test
    fun `each failure cause gets its own message`() = runTest {
        permission = false
        repo.fetchCurrentTemperatureC()
        permission = true
        location = null
        repo.fetchCurrentTemperatureC()
        location = 52.52 to 13.41
        apiResult = null
        repo.fetchCurrentTemperatureC()

        assertEquals(3, loggedMessages.size)
        assertEquals("three different causes, three different texts", 3, loggedMessages.toSet().size)
        assertTrue(loggedMessages[0], loggedMessages[0].contains("Standortberechtigung"))
        assertTrue(loggedMessages[1], loggedMessages[1].contains("kein Standort"))
        assertTrue(loggedMessages[2], loggedMessages[2].contains("Open-Meteo"))
    }

    @Test
    fun `the same failure cause repeated across polls is logged only once`() = runTest {
        apiResult = null // e.g. no network for the whole ride: a poll every 60 s
        repeat(5) { repo.fetchCurrentTemperatureC() }
        coVerify(exactly = 1) { appLogRepository.log(LogLevel.WARN, any()) }

        permission = false
        repeat(5) { repo.fetchCurrentTemperatureC() }
        assertEquals(2, loggedMessages.size)
    }

    @Test
    fun `a changed cause logs again - permission granted mid-session, then the API fails`() = runTest {
        permission = false
        repeat(3) { repo.fetchCurrentTemperatureC() }
        permission = true // user granted it in the system dialog
        apiResult = null
        repeat(3) { repo.fetchCurrentTemperatureC() }

        assertEquals(2, loggedMessages.size)
        assertTrue(loggedMessages[0].contains("Standortberechtigung"))
        assertTrue(loggedMessages[1].contains("Open-Meteo"))
    }

    @Test
    fun `the same cause recurring after a successful poll is a new episode and logs again`() = runTest {
        apiResult = null
        repo.fetchCurrentTemperatureC()
        apiResult = 17.0f
        repo.fetchCurrentTemperatureC() // recovered
        apiResult = null
        repo.fetchCurrentTemperatureC()

        assertEquals(2, loggedMessages.size)
        assertEquals(loggedMessages[0], loggedMessages[1])
    }
}
