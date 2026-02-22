package de.adv990de.obd2.ui

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveStateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(isMoving: Boolean, hideNavOnDrive: Boolean): DriveStateViewModel {
        val obdRepo = mockk<ObdRepository> {
            every { isMovingFlow } returns MutableStateFlow(isMoving)
        }
        val prefs = mockk<AppPreferences> {
            every { this@mockk.hideNavOnDrive } returns flowOf(hideNavOnDrive)
        }
        return DriveStateViewModel(obdRepo, prefs)
    }

    @Test
    fun `isNavHidden is false when preference is off regardless of speed`() = runTest {
        val vm = makeVm(isMoving = true, hideNavOnDrive = false)
        advanceUntilIdle()
        assertFalse(vm.isNavHidden.value)
    }

    @Test
    fun `isNavHidden is true when preference is on and vehicle is moving`() = runTest {
        val vm = makeVm(isMoving = true, hideNavOnDrive = true)
        advanceUntilIdle()
        assertTrue(vm.isNavHidden.value)
    }

    @Test
    fun `isNavHidden is false when preference is on but vehicle is stationary`() = runTest {
        val vm = makeVm(isMoving = false, hideNavOnDrive = true)
        advanceUntilIdle()
        assertFalse(vm.isNavHidden.value)
    }
}
