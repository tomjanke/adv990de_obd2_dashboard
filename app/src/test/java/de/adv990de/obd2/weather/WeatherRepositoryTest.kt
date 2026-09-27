package de.adv990de.obd2.weather

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.location.CurrentLocationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherRepositoryTest {

    @Test
    fun `returns the API's temperature when location and API both succeed`() = runTest {
        val locationProvider = mockk<CurrentLocationProvider> {
            every { hasPermission() } returns true
            every { lastKnownLocation() } returns (52.52 to 13.41)
        }
        val api = mockk<WeatherApi> {
            coEvery { currentTemperatureC(52.52, 13.41) } returns 18.0f
        }
        val repo = WeatherRepository(locationProvider, api, mockk(relaxed = true))

        assertEquals(18.0f, repo.fetchCurrentTemperatureC()!!, 0.01f)
    }

    @Test
    fun `returns null without a location fix`() = runTest {
        val locationProvider = mockk<CurrentLocationProvider> {
            every { hasPermission() } returns true
            every { lastKnownLocation() } returns null
        }
        val api = mockk<WeatherApi>()
        val appLogRepository = mockk<AppLogRepository>(relaxed = true)
        val repo = WeatherRepository(locationProvider, api, appLogRepository)

        assertNull(repo.fetchCurrentTemperatureC())
    }

    @Test
    fun `returns null without location permission`() = runTest {
        val locationProvider = mockk<CurrentLocationProvider> {
            every { hasPermission() } returns false
        }
        val api = mockk<WeatherApi>()
        val repo = WeatherRepository(locationProvider, api, mockk(relaxed = true))

        assertNull(repo.fetchCurrentTemperatureC())
    }

    @Test
    fun `returns null and logs when the API call fails`() = runTest {
        val locationProvider = mockk<CurrentLocationProvider> {
            every { hasPermission() } returns true
            every { lastKnownLocation() } returns (52.52 to 13.41)
        }
        val api = mockk<WeatherApi> {
            coEvery { currentTemperatureC(any(), any()) } returns null
        }
        val appLogRepository = mockk<AppLogRepository>(relaxed = true)
        val repo = WeatherRepository(locationProvider, api, appLogRepository)

        assertNull(repo.fetchCurrentTemperatureC())
    }
}
