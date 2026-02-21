package de.adv990de.obd2.ui.freezeframe

import de.adv990de.obd2.data.ObdRepository
import io.mockk.coEvery
import io.mockk.mockk
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
class FreezeFrameViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `load sets data on success`() = runTest {
        val expected = mapOf("RPM" to "1500", "SPEED" to "60")
        val repo = mockk<ObdRepository> {
            coEvery { readFreezeFrame() } returns expected
        }
        val vm = FreezeFrameViewModel(repo)

        vm.load()
        advanceUntilIdle()

        assertEquals(expected, vm.data.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets error on failure`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readFreezeFrame() } throws Exception("freeze frame error")
        }
        val vm = FreezeFrameViewModel(repo)

        vm.load()
        advanceUntilIdle()

        assertEquals("freeze frame error", vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `isLoading is true during load and false after`() = runTest {
        val deferred = CompletableDeferred<Map<String, String>>()
        val repo = mockk<ObdRepository> {
            coEvery { readFreezeFrame() } coAnswers { deferred.await() }
        }
        val vm = FreezeFrameViewModel(repo)

        vm.load()
        advanceUntilIdle() // runs until coroutine suspends on deferred.await()

        assertTrue(vm.isLoading.value)

        deferred.complete(emptyMap())
        advanceUntilIdle()

        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `retry clears previous error before reloading`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readFreezeFrame() } throws Exception("first failure") andThen emptyMap()
        }
        val vm = FreezeFrameViewModel(repo)

        vm.load()
        advanceUntilIdle()
        assertEquals("first failure", vm.error.value)

        vm.load()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals(emptyMap<String, String>(), vm.data.value)
    }
}
