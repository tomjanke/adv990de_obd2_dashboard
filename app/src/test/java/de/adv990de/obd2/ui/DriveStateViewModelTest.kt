package de.adv990de.obd2.ui

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.dashboard.OilWarmupState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
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
import org.junit.Assert.assertEquals
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

    @Test
    fun `toggleHideNavOnDrive calls setHideNavOnDrive with inverted value`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { hideNavOnDrive } returns flowOf(false)
            coEvery { setHideNavOnDrive(any()) } just Runs
        }
        val obdRepo = mockk<ObdRepository> {
            every { isMovingFlow } returns MutableStateFlow(false)
        }
        val vm = DriveStateViewModel(obdRepo, prefs)
        advanceUntilIdle()
        vm.toggleHideNavOnDrive()
        advanceUntilIdle()
        coVerify { prefs.setHideNavOnDrive(true) }
    }


    @Test
    fun `the oil timer comes from the shared warm-up state, starting with the full ten minutes`() = runTest {
        val warmup = OilWarmupState()
        val obdRepo = mockk<ObdRepository> { every { isMovingFlow } returns MutableStateFlow(true) }
        val prefs = mockk<AppPreferences> { every { hideNavOnDrive } returns flowOf(true) }
        val vm = DriveStateViewModel(obdRepo, prefs, warmup)
        advanceUntilIdle()
        assertEquals(600_000L, vm.oilGreenInMillis.value)

        warmup.set(432_000L)

        assertEquals(432_000L, vm.oilGreenInMillis.value)
    }
}
