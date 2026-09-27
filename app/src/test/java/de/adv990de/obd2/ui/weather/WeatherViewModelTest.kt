package de.adv990de.obd2.ui.weather

import androidx.lifecycle.viewModelScope
import de.adv990de.obd2.weather.WeatherRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [WeatherViewModel.outsideTempC] is a cold, subscriber-driven flow
 * (`stateIn(WhileSubscribed(5_000))`): nothing is fetched until the ambient row actually collects
 * it, and polling stops 5 s after the last collector left.
 *
 * Leak prevention (s. task-4-report.md: an uncancelled infinite loop on `viewModelScope` once
 * OOM-crashed unrelated tests later in the same JVM): a plain unit test has no ViewModelStore that
 * would call `onCleared()`, so every test runs through [runVmTest], which cancels the
 * `viewModelScope` of every ViewModel built via [makeVm] in a `finally` INSIDE the `runTest` body.
 * Inside, not in `@After`: `runTest`'s own teardown drains the scheduler — shared with
 * `viewModelScope` through `Dispatchers.setMain` — via `advanceUntilIdleOr` before JUnit's `@After`
 * ever runs (verified in the kotlinx-coroutines-test 1.9.0 bytecode). An infinite polling loop still
 * alive at that point runs away in virtual time until `OutOfMemoryError`; reproduced 2026-09-27 by
 * running these tests against the old init-block implementation with an `@After`-only cleanup
 * (6.8 s per test, then OOM in every following test of the class). And `finally`, not a last line:
 * a failing assertion must not skip the cleanup and cascade into unrelated tests. The flow shape
 * changed (stateIn instead of an init-block loop), the need did not: while subscribed, the polling
 * loop still runs on `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val createdVms = mutableListOf<WeatherViewModel>()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(repo: WeatherRepository): WeatherViewModel =
        WeatherViewModel(repo).also { createdVms += it }

    /** [runTest] plus guaranteed cancellation of every [makeVm] ViewModel — s. class KDoc. */
    private fun runVmTest(testBody: suspend TestScope.() -> Unit) = runTest {
        try {
            testBody()
        } finally {
            createdVms.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test
    fun `fetches nothing while nobody collects`() = runVmTest {
        val repo = mockk<WeatherRepository> { coEvery { fetchCurrentTemperatureC() } returns 18.0f }
        makeVm(repo)

        advanceTimeBy(10.minutes)
        runCurrent()

        coVerify(exactly = 0) { repo.fetchCurrentTemperatureC() }
    }

    @Test
    fun `fetches once on subscription and again after 60 seconds`() = runVmTest {
        val repo = mockk<WeatherRepository> {
            coEvery { fetchCurrentTemperatureC() } returnsMany listOf(18.0f, 19.0f, 20.0f)
        }
        val vm = makeVm(repo)

        val job = launch { vm.outsideTempC.collect {} }
        runCurrent()
        assertEquals(18.0f, vm.outsideTempC.value)

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(19.0f, vm.outsideTempC.value)

        job.cancel()
    }

    @Test
    fun `stops polling once the last collector has been gone for 5 seconds`() = runVmTest {
        val repo = mockk<WeatherRepository> { coEvery { fetchCurrentTemperatureC() } returns 18.0f }
        val vm = makeVm(repo)

        val job = launch { vm.outsideTempC.collect {} }
        runCurrent()
        job.cancel() // the row left the screen

        advanceTimeBy(10.minutes)
        runCurrent()

        coVerify(exactly = 1) { repo.fetchCurrentTemperatureC() }
    }

    @Test
    fun `a collector returning within the 5 second grace period keeps the running poll`() = runVmTest {
        val repo = mockk<WeatherRepository> { coEvery { fetchCurrentTemperatureC() } returns 18.0f }
        val vm = makeVm(repo)

        val first = launch { vm.outsideTempC.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(3.seconds) // e.g. the AnimatedContent swap re-composing the row
        val second = launch { vm.outsideTempC.collect {} }
        runCurrent()

        // No extra fetch on re-subscription: the original 60-second cadence simply continues.
        coVerify(exactly = 1) { repo.fetchCurrentTemperatureC() }
        advanceTimeBy(57.seconds)
        runCurrent()
        coVerify(exactly = 2) { repo.fetchCurrentTemperatureC() }

        second.cancel()
    }

    @Test
    fun `resubscribing after the grace period fetches again right away`() = runVmTest {
        val repo = mockk<WeatherRepository> {
            coEvery { fetchCurrentTemperatureC() } returnsMany listOf(18.0f, 19.0f)
        }
        val vm = makeVm(repo)

        val first = launch { vm.outsideTempC.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(30.seconds) // e.g. stopped at a red light: nav bar back, row gone
        val second = launch { vm.outsideTempC.collect {} }
        runCurrent()

        assertEquals(19.0f, vm.outsideTempC.value)
        coVerify(exactly = 2) { repo.fetchCurrentTemperatureC() }

        second.cancel()
    }
}
