package de.adv990de.obd2.location

import android.content.Context
import android.os.Build
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionsTest {

    // Bewusst kein relaxed Mock: jeder Zugriff auf den Context wuerde den Test brechen.
    // Im JVM-Unit-Test ist Build.VERSION.SDK_INT == 0, also unterhalb API 29 (Q).
    private val context = mockk<Context>()

    @Test
    fun `hasBackgroundLocation is true below API 29 without querying the context`() {
        assertTrue(LocationPermissions.hasBackgroundLocation(context))
        verify { context wasNot Called }
    }

    @Test
    fun `needsBackgroundLocationRequest is false below API 29 regardless of grant state`() {
        assertFalse(
            LocationPermissions.needsBackgroundLocationRequest(
                sdkInt = Build.VERSION_CODES.P, backgroundLocationGranted = false
            )
        )
    }

    @Test
    fun `needsBackgroundLocationRequest is true at API 29 or above when not yet granted`() {
        assertTrue(
            LocationPermissions.needsBackgroundLocationRequest(
                sdkInt = Build.VERSION_CODES.Q, backgroundLocationGranted = false
            )
        )
    }

    @Test
    fun `needsBackgroundLocationRequest is false at API 29 or above when already granted`() {
        assertFalse(
            LocationPermissions.needsBackgroundLocationRequest(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, backgroundLocationGranted = true
            )
        )
    }
}
