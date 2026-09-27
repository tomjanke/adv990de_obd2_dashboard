package de.adv990de.obd2.ui.weather

import androidx.lifecycle.viewModelScope
import de.adv990de.obd2.weather.WeatherRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `fetches once immediately and again after 60 seconds`() = runTest {
        val repo = mockk<WeatherRepository> {
            coEvery { fetchCurrentTemperatureC() } returnsMany listOf(18.0f, 19.0f, 20.0f)
        }
        val vm = WeatherViewModel(repo)

        val job = launch { vm.outsideTempC.collect {} }
        testDispatcher.scheduler.runCurrent()
        assertEquals(18.0f, vm.outsideTempC.value)

        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(19.0f, vm.outsideTempC.value)

        job.cancel()
        // WeatherViewModel's init block launches an infinite `while (isActive) { ...; delay(...) }`
        // loop on viewModelScope. A plain unit test has no real ViewModelStore to call onCleared()
        // for us, so without this the loop's Job never completes: it sits parked on its delay(),
        // still registered with testDispatcher's scheduler, and leaks for the rest of the JVM test
        // process — observed as OutOfMemoryError in unrelated tests that ran afterwards.
        vm.viewModelScope.cancel()
    }
}
