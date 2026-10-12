package de.adv990de.obd2

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage for the two `ObdForegroundService` branches `connectedDebugAndroidTest` can't
 * reach on this project's test device (Pixel 8 Pro, always API 34+): the pre-API-34
 * `startForeground` fallback, and `onDestroy()`'s `scope.cancel()`. The API-34+ path and the
 * `resolveForegroundServiceType` type-flag computation already have coverage in
 * `ObdForegroundServiceDeviceTest` (androidTest) and `ObdForegroundServiceTypeTest` (JVM) respectively.
 *
 * `resolveForegroundStartMode` is the pure, sdkInt-parameterized extraction of the decision
 * `onStartCommand` makes between the typed 3-arg `startForeground(id, notification, type)`
 * (API 34+) and the legacy 2-arg overload below it — same convention as
 * `resolveForegroundServiceType`/`BluetoothPermissions`/`LocationPermissions` (sdkInt defaults to
 * the real `Build.VERSION.SDK_INT`, tests override it explicitly).
 *
 * What this deliberately does NOT cover: that `onStartCommand` actually calls the `startForeground`
 * overload each mode implies. Both overloads are real `Service`/framework methods — invoking them
 * needs either a live platform (instrumentation, stuck at API 34+ on this device) or Robolectric
 * shadows (not a dependency of this project). Verifying only the decision, not the framework call
 * built on top of it, mirrors the trade-off `SettingsScreenTest` (JVM) documents for its own
 * not-actually-wired-up branches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdForegroundServiceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    // --- resolveForegroundStartMode ---

    @Test
    fun `below API 34 resolves to the legacy two-arg overload`() {
        assertEquals(
            ForegroundStartMode.LEGACY,
            ObdForegroundService.resolveForegroundStartMode(sdkInt = Build.VERSION_CODES.TIRAMISU)
        )
    }

    @Test
    fun `at API 34 resolves to the typed three-arg overload`() {
        assertEquals(
            ForegroundStartMode.TYPED,
            ObdForegroundService.resolveForegroundStartMode(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    fun `above API 34 still resolves to the typed three-arg overload`() {
        assertEquals(
            ForegroundStartMode.TYPED,
            ObdForegroundService.resolveForegroundStartMode(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1)
        )
    }

    // --- onDestroy / scope.cancel() ---

    @Test
    fun `onDestroy cancels the service's coroutine scope`() {
        val service = ObdForegroundService()
        val job = service.scope.launch { awaitCancellation() }
        testDispatcher.scheduler.runCurrent()
        assertTrue("probe coroutine should be running before onDestroy", job.isActive)

        service.onDestroy()

        assertTrue(job.isCancelled)
        assertFalse(job.isActive)
    }
}
