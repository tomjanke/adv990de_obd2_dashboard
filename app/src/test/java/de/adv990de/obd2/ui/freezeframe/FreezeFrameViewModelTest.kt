package de.adv990de.obd2.ui.freezeframe

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
class FreezeFrameViewModelTest {

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

    @Test
    fun `load sets data on success`() = runTest {
        val expected = mapOf("RPM" to "1500", "SPEED" to "60")
        val vm = FreezeFrameViewModel(repo { coEvery { readFreezeFrame() } returns expected })

        vm.load()
        advanceUntilIdle()

        assertEquals(expected, vm.data.value)
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load sets error on failure`() = runTest {
        val vm = FreezeFrameViewModel(repo { coEvery { readFreezeFrame() } throws Exception("freeze frame error") })

        vm.load()
        advanceUntilIdle()

        assertEquals(UiText.DynamicString("freeze frame error"), vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `a known adapter failure is shown as its localized text`() = runTest {
        val vm = FreezeFrameViewModel(repo { coEvery { readFreezeFrame() } throws ObdException.Timeout("0202") })

        vm.load()
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.obd_err_timeout), vm.error.value)
    }

    @Test
    fun `a failure without a message still sets an error`() = runTest {
        val vm = FreezeFrameViewModel(repo { coEvery { readFreezeFrame() } throws RuntimeException() })

        vm.load()
        advanceUntilIdle()

        assertNotNull(vm.error.value)
    }

    @Test
    fun `isLoading is true during load and false after`() = runTest {
        val deferred = CompletableDeferred<Map<String, String>>()
        val vm = FreezeFrameViewModel(repo { coEvery { readFreezeFrame() } coAnswers { deferred.await() } })

        vm.load()
        advanceUntilIdle() // runs until coroutine suspends on deferred.await()

        assertTrue(vm.isLoading.value)

        deferred.complete(emptyMap())
        advanceUntilIdle()

        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `retry clears previous error before reloading`() = runTest {
        val vm = FreezeFrameViewModel(
            repo { coEvery { readFreezeFrame() } throws Exception("first failure") andThen emptyMap() }
        )

        vm.load()
        advanceUntilIdle()
        assertEquals(UiText.DynamicString("first failure"), vm.error.value)

        vm.load()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals(emptyMap<String, String>(), vm.data.value)
    }

    // ── B19: without a connection nothing is read and the screen says "not connected" ──

    @Test
    fun `isConnected mirrors the repository connection state`() = runTest {
        val connected = MutableStateFlow(false)
        val vm = FreezeFrameViewModel(repo(connected))

        assertFalse(vm.isConnected.value)
        connected.value = true
        assertTrue(vm.isConnected.value)
    }

    @Test
    fun `load without a connection reads nothing and leaves no data, no error and no spinner`() = runTest {
        val repo = repo(MutableStateFlow(false)) { coEvery { readFreezeFrame() } returns mapOf("010C" to "1500") }
        val vm = FreezeFrameViewModel(repo)

        vm.load()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.readFreezeFrame() }
        assertTrue(vm.data.value.isEmpty())
        assertNull(vm.error.value)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `load without a connection clears data and error of an earlier read`() = runTest {
        val connected = MutableStateFlow(true)
        val repo = repo(connected) { coEvery { readFreezeFrame() } returns mapOf("010C" to "1500") }
        val vm = FreezeFrameViewModel(repo)
        vm.load()
        advanceUntilIdle()
        assertEquals(1, vm.data.value.size)

        connected.value = false
        vm.load()
        advanceUntilIdle()

        assertTrue(vm.data.value.isEmpty())
        assertNull(vm.error.value)
    }

    @Test
    fun `load after the connection came back reads again`() = runTest {
        val connected = MutableStateFlow(false)
        val repo = repo(connected) { coEvery { readFreezeFrame() } returns mapOf("010C" to "1500") }
        val vm = FreezeFrameViewModel(repo)
        vm.load()
        advanceUntilIdle()

        connected.value = true
        vm.load()
        advanceUntilIdle()

        assertEquals(mapOf("010C" to "1500"), vm.data.value)
    }

    @Test
    fun `a read that finishes after the connection dropped does not resurrect its data`() = runTest {
        val connected = MutableStateFlow(true)
        val deferred = CompletableDeferred<Map<String, String>>()
        val repo = repo(connected) { coEvery { readFreezeFrame() } coAnswers { deferred.await() } }
        val vm = FreezeFrameViewModel(repo)
        vm.load()
        advanceUntilIdle()                    // first read is in flight

        connected.value = false
        vm.load()                             // the screen reloads on the connection change
        advanceUntilIdle()
        deferred.complete(mapOf("010C" to "1500"))   // the stale read finishes now
        advanceUntilIdle()

        assertTrue("veraltetes Ergebnis darf nicht erscheinen", vm.data.value.isEmpty())
        assertFalse(vm.isLoading.value)
    }
}
