package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.db.entity.AppLogEntity
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLogsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `logs emits list from repository`() = runTest {
        val entry = AppLogEntity(id = 1, timestamp = 1000L, level = "INFO", message = "hello")
        val repo = mockk<AppLogRepository> {
            every { logs } returns flowOf(listOf(entry))
            coEvery { pruneOlderThan(any()) } just Runs
        }

        val vm = AppLogsViewModel(repo)

        // Subscribe so that WhileSubscribed starts the upstream collection.
        val collected = mutableListOf<List<AppLogEntity>>()
        val job = launch { vm.logs.collect { collected.add(it) } }

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(entry), vm.logs.value)
        job.cancel()
    }

    @Test
    fun `init prunes old logs on startup`() = runTest {
        val repo = mockk<AppLogRepository> {
            every { logs } returns flowOf(emptyList())
            coEvery { pruneOlderThan(any()) } just Runs
        }
        val vm = AppLogsViewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repo.pruneOlderThan(any()) }
    }

    @Test
    fun `clearAll delegates to repository`() = runTest {
        val repo = mockk<AppLogRepository> {
            every { logs } returns flowOf(emptyList())
            coEvery { pruneOlderThan(any()) } just Runs
            coEvery { clearAll() } just Runs
        }

        val vm = AppLogsViewModel(repo)
        vm.clearAll()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repo.clearAll() }
    }
}
