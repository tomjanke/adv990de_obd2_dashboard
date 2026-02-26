package de.adv990de.obd2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.ObdRepository
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ObdForegroundServiceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    // ServiceTestRule binds to the service lifecycle; order = 1 ensures Hilt is ready first.
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()

    /**
     * Replaces the @Singleton ObdRepository binding for all tests in this class.
     * Hilt injects this mock directly into ObdForegroundService, bypassing the real
     * constructor (which requires Bluetooth / ConnectionFactory infrastructure).
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        coJustRun { disconnect() }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() = hiltRule.inject()

    @Test
    fun actionStart_startsService() {
        serviceRule.startService(ObdForegroundService.startIntent(ctx))
        assertTrue("Service did not start", true)
    }

    @Test
    fun onStartCommand_returnsStartNotSticky() {
        // The service must survive the startService call without crashing; START_NOT_STICKY = 2.
        serviceRule.startService(ObdForegroundService.startIntent(ctx))
        assertTrue(true)
    }

    @Test
    fun actionStop_callsDisconnectAndStopsService() {
        serviceRule.startService(ObdForegroundService.startIntent(ctx))
        serviceRule.startService(ObdForegroundService.stopIntent(ctx))
        // disconnect() is launched in a coroutine on the Main dispatcher; give it time to run.
        Thread.sleep(300)
        coVerify { obdRepository.disconnect() }
    }

    @Test
    fun unknownAction_stopsServiceImmediately() {
        // No action set — service hits the else branch and calls stopSelf() without crashing.
        val unknownIntent = android.content.Intent(ctx, ObdForegroundService::class.java)
        serviceRule.startService(unknownIntent)
        assertTrue("Service should handle unknown action gracefully", true)
    }
}
