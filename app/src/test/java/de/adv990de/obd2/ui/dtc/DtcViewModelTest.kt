package de.adv990de.obd2.ui.dtc

import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DtcViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `refresh sets dtcs on success`() = runTest {
        val dtcs = listOf(DtcEntry("P0301", "Misfire cylinder 1", false))
        val repo = mockk<ObdRepository> {
            coEvery { readDtcs() } returns dtcs
        }
        val vm = DtcViewModel(repo)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(dtcs, vm.uiState.value.dtcs)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `refresh sets error on exception`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readDtcs() } throws Exception("DTC read failed")
        }
        val vm = DtcViewModel(repo)

        vm.refresh()
        advanceUntilIdle()

        assertEquals("DTC read failed", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `isLoading transitions correctly`() = runTest {
        val deferred = CompletableDeferred<List<DtcEntry>>()
        val repo = mockk<ObdRepository> {
            coEvery { readDtcs() } coAnswers { deferred.await() }
        }
        val vm = DtcViewModel(repo)

        vm.refresh()
        advanceUntilIdle() // runs until coroutine suspends on deferred.await()

        assertTrue(vm.uiState.value.isLoading)

        deferred.complete(emptyList())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `CancellationException is rethrown`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readDtcs() } throws CancellationException("cancelled")
        }
        val vm = DtcViewModel(repo)

        vm.refresh()
        advanceUntilIdle()

        // CancellationException was rethrown, so error was never set
        assertNull(vm.uiState.value.error)
    }
}
