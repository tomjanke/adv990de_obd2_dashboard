package de.adv990de.obd2.ui.vehicleinfo

import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleInfoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun repo(
        connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        block: ObdRepository.() -> Unit = {},
    ): ObdRepository = mockk {
        every { isConnectedFlow } returns connected
        block()
    }

    private fun makeVm(repo: ObdRepository = repo()) = VehicleInfoViewModel(repo)

    @Test
    fun `load sets vin on success`() = runTest {
        val vm = makeVm(repo { coEvery { readVin() } returns "1HGBH41JXMN109186" })

        vm.load()
        advanceUntilIdle()

        assertEquals("1HGBH41JXMN109186", vm.vin.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets vin to empty string when readVin returns null`() = runTest {
        val vm = makeVm(repo { coEvery { readVin() } returns null })

        vm.load()
        advanceUntilIdle()

        assertEquals("", vm.vin.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets error and clears vin when readVin throws`() = runTest {
        val vm = makeVm(repo { coEvery { readVin() } throws RuntimeException("not connected") })

        vm.load()
        advanceUntilIdle()

        assertEquals(UiText.DynamicString("not connected"), vm.error.value)
        assertEquals("", vm.vin.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `a known adapter failure is shown as its localized text`() = runTest {
        val vm = makeVm(repo { coEvery { readVin() } throws ObdException.ConnectionLost() })

        vm.load()
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.obd_err_connection_lost), vm.error.value)
    }

    @Test
    fun `a failure without a message still sets an error`() = runTest {
        val vm = makeVm(repo { coEvery { readVin() } throws RuntimeException() })

        vm.load()
        advanceUntilIdle()

        assertNotNull(vm.error.value)
    }

    @Test
    fun `isLoading is true during load and false after`() = runTest {
        val deferred = CompletableDeferred<String?>()
        val vm = makeVm(repo { coEvery { readVin() } coAnswers { deferred.await() } })

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
        val vm = makeVm(repo { coEvery { readVin() } throws RuntimeException("first failure") andThen "RECOVERED" })

        vm.load()
        advanceUntilIdle()
        assertEquals(UiText.DynamicString("first failure"), vm.error.value)

        vm.load()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals("RECOVERED", vm.vin.value)
        assertFalse(vm.isLoading.value)
    }

    // ── B19: without a connection nothing is read and the screen says "not connected" ──

    @Test
    fun `isConnected mirrors the repository connection state`() = runTest {
        val connected = MutableStateFlow(false)
        val vm = makeVm(repo(connected))

        assertFalse(vm.isConnected.value)
        connected.value = true
        assertTrue(vm.isConnected.value)
    }

    @Test
    fun `load without a connection reads nothing and leaves no vin, no error and no spinner`() = runTest {
        val repo = repo(MutableStateFlow(false)) { coEvery { readVin() } returns "1HGBH41JXMN109186" }
        val vm = makeVm(repo)

        vm.load()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.readVin() }
        assertEquals("", vm.vin.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load without a connection clears vin and error of an earlier read`() = runTest {
        val connected = MutableStateFlow(true)
        val repo = repo(connected) { coEvery { readVin() } returns "1HGBH41JXMN109186" }
        val vm = makeVm(repo)
        vm.load()
        advanceUntilIdle()
        assertEquals("1HGBH41JXMN109186", vm.vin.value)

        connected.value = false
        vm.load()
        advanceUntilIdle()

        assertEquals("", vm.vin.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `load after the connection came back reads again`() = runTest {
        val connected = MutableStateFlow(false)
        val repo = repo(connected) { coEvery { readVin() } returns "1HGBH41JXMN109186" }
        val vm = makeVm(repo)
        vm.load()
        advanceUntilIdle()

        connected.value = true
        vm.load()
        advanceUntilIdle()

        assertEquals("1HGBH41JXMN109186", vm.vin.value)
    }

    @Test
    fun `a read that finishes after the connection dropped does not resurrect its vin`() = runTest {
        val connected = MutableStateFlow(true)
        val deferred = CompletableDeferred<String?>()
        val repo = repo(connected) { coEvery { readVin() } coAnswers { deferred.await() } }
        val vm = makeVm(repo)
        vm.load()
        advanceUntilIdle()                    // first read is in flight

        connected.value = false
        vm.load()                             // the screen reloads on the connection change
        advanceUntilIdle()
        deferred.complete("1HGBH41JXMN109186")   // the stale read finishes now
        advanceUntilIdle()

        assertEquals("veraltetes Ergebnis darf nicht erscheinen", "", vm.vin.value)
        assertFalse(vm.isLoading.value)
    }
}
