package de.adv990de.obd2.ui

import android.content.Context
import de.adv990de.obd2.data.ObdRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * exitApp() is a plain suspend fun (no internal viewModelScope.launch), unlike
 * SettingsViewModel.disconnect() which it otherwise mirrors exactly: BottomNav needs to await it
 * before calling Activity.finishAffinity(), so the caller must own the coroutine.
 */
class ExitAppViewModelTest {

    private fun makeVm(
        obdRepo: ObdRepository = mockk { coEvery { disconnect() } just Runs },
        appContext: Context = mockk(relaxed = true),
    ) = ExitAppViewModel(obdRepo, appContext)

    @Test
    fun `exitApp disconnects the ObdRepository`() = runTest {
        val obdRepo = mockk<ObdRepository> { coEvery { disconnect() } just Runs }
        val vm = makeVm(obdRepo = obdRepo)

        vm.exitApp()

        coVerify { obdRepo.disconnect() }
    }

    @Test
    fun `exitApp stops the foreground service`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val vm = makeVm(appContext = ctx)

        vm.exitApp()

        verify { ctx.stopService(any()) }
    }
}
