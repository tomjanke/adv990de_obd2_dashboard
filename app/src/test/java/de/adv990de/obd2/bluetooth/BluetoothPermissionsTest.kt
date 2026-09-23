package de.adv990de.obd2.bluetooth

import android.Manifest
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
// Lint kann diesen Unterschied nicht erkennen (gleiches Prinzip wie das InlinedApi-Suppress
// in BluetoothPermissions.kt / PermissionGate.kt).
@SuppressLint("UseCheckPermission")
class BluetoothPermissionsTest {

    // Bewusst kein relaxed Mock: jeder Zugriff auf den Context wuerde den Test brechen.
    // Im JVM-Unit-Test ist Build.VERSION.SDK_INT == 0, also der Pfad unterhalb Android 12,
    // auf dem BLUETOOTH/BLUETOOTH_ADMIN Install-Time-Berechtigungen sind.
    private val context = mockk<Context>()

    // --- Test 1 ---

    @Test
    fun `hasConnect is true below API 31 without querying the context`() {
        assertTrue(BluetoothPermissions.hasConnect(context))
        verify { context wasNot Called }
    }

    // --- Test 2 ---

    @Test
    fun `hasScan is true below API 31 without querying the context`() {
        assertTrue(BluetoothPermissions.hasScan(context))
        verify { context wasNot Called }
    }

    // --- API 31+: hasConnect delegiert an ContextCompat.checkSelfPermission, die intern (fuer
    // BLUETOOTH_CONNECT, keine POST_NOTIFICATIONS-Sonderbehandlung) auf
    // context.checkPermission(...) durchgreift. Der explizite sdkInt-Parameter zwingt den
    // Guard in hasRuntimePermission auf den >= S-Zweig, unabhaengig vom echten
    // Build.VERSION.SDK_INT (== 0 im JVM-Test).

    @Test
    fun `hasConnect at API 31 queries the context and returns true when granted`() {
        every {
            context.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(BluetoothPermissions.hasConnect(context, sdkInt = Build.VERSION_CODES.S))

        verify { context.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, any(), any()) }
    }

    @Test
    fun `hasConnect at API 31 queries the context and returns false when denied`() {
        every {
            context.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(BluetoothPermissions.hasConnect(context, sdkInt = Build.VERSION_CODES.S))

        verify { context.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, any(), any()) }
    }

    // --- API 31+: hasScan, gleiches Muster wie hasConnect oben. ---

    @Test
    fun `hasScan at API 31 queries the context and returns true when granted`() {
        every {
            context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(BluetoothPermissions.hasScan(context, sdkInt = Build.VERSION_CODES.S))

        verify { context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, any(), any()) }
    }

    @Test
    fun `hasScan at API 31 queries the context and returns false when denied`() {
        every {
            context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(BluetoothPermissions.hasScan(context, sdkInt = Build.VERSION_CODES.S))

        verify { context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, any(), any()) }
    }
}
