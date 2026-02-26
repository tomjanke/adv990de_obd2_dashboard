package de.adv990de.obd2.ui.vehicleinfo

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
class VehicleInfoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(repo: ObdRepository = mockk()) = VehicleInfoViewModel(repo)

    @Test
    fun `load sets vin on success`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readVin() } returns "1HGBH41JXMN109186"
        }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle()

        assertEquals("1HGBH41JXMN109186", vm.vin.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets vin to empty string when readVin returns null`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readVin() } returns null
        }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle()

        assertEquals("", vm.vin.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets error and clears vin when readVin throws`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readVin() } throws RuntimeException("not connected")
        }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle()

        assertEquals("not connected", vm.error.value)
        assertEquals("", vm.vin.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `isLoading is true during load and false after`() = runTest {
        val deferred = CompletableDeferred<String?>()
        val repo = mockk<ObdRepository> {
            coEvery { readVin() } coAnswers { deferred.await() }
        }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle() // runs until coroutine suspends on deferred.await()

        assertTrue(vm.isLoading.value)

        deferred.complete("VIN")
        advanceUntilIdle()

        assertFalse(vm.isLoading.value)
        assertEquals("VIN", vm.vin.value)
    }

    @Test
    fun `error is cleared on subsequent successful load`() = runTest {
        val repo = mockk<ObdRepository> {
            coEvery { readVin() } throws RuntimeException("first failure") andThen "RECOVERED"
        }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle()
        assertEquals("first failure", vm.error.value)

        vm.load()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals("RECOVERED", vm.vin.value)
        assertFalse(vm.isLoading.value)
    }
}
