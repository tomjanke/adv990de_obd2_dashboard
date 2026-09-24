package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// UseCheckPermission: Lint sieht `context.checkPermission(...)` in den `verify {}`-Bloecken
// unten und meint, der Rueckgabewert einer echten Berechtigungspruefung werde ignoriert. Es
// ist aber ein MockK-Call-Matcher, kein echter Aufruf, der ein Ergebnis verwerfen wuerde —
// gleiches Prinzip wie das Suppress in BluetoothPermissionsTest.kt.
@SuppressLint("UseCheckPermission")
class LocationPermissionsTest {

    // Bewusst kein relaxed Mock: jeder Zugriff auf den Context wuerde den Test brechen.
    // Im JVM-Unit-Test ist Build.VERSION.SDK_INT == 0, also unterhalb API 29 (Q).
    private val context = mockk<Context>()

    @Test
    fun `hasBackgroundLocation is true below API 29 without querying the context`() {
        assertTrue(LocationPermissions.hasBackgroundLocation(context))
        verify { context wasNot Called }
    }

    // --- hasFineLocation delegiert direkt an ContextCompat.checkSelfPermission, die intern
    // (fuer ACCESS_FINE_LOCATION, keine Sonderbehandlung) auf context.checkPermission(...)
    // durchgreift — unabhaengig vom SDK-Level, daher kein sdkInt-Parameter noetig.

    @Test
    fun `hasFineLocation returns true when granted`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissions.hasFineLocation(context))

        verify { context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) }
    }

    @Test
    fun `hasFineLocation returns false when denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(LocationPermissions.hasFineLocation(context))

        verify { context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) }
    }

    // --- API 29+: hasBackgroundLocation delegiert an ContextCompat.checkSelfPermission, die
    // intern (fuer ACCESS_BACKGROUND_LOCATION, keine Sonderbehandlung) auf
    // context.checkPermission(...) durchgreift. Der explizite sdkInt-Parameter zwingt den Guard
    // auf den >= Q-Zweig, unabhaengig vom echten Build.VERSION.SDK_INT (== 0 im JVM-Test).

    @Test
    fun `hasBackgroundLocation at API 29 queries the context and returns true when granted`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissions.hasBackgroundLocation(context, sdkInt = Build.VERSION_CODES.Q))

        verify {
            context.checkPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, any(), any())
        }
    }

    @Test
    fun `hasBackgroundLocation at API 29 queries the context and returns false when denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(LocationPermissions.hasBackgroundLocation(context, sdkInt = Build.VERSION_CODES.Q))

        verify {
            context.checkPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, any(), any())
        }
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
