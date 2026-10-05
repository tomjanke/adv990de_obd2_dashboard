package de.adv990de.obd2.ui.permission

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {

    // Build.VERSION_CODES.S = 31, TIRAMISU = 33 (hartcodiert, damit der Test ohne
    // Robolectric/android.jar-Stub auskommt).
    // Seit 1.24.9 ohne BLE: Die App verbindet sich nur mit gekoppelten Classic-Adaptern. Dafuer genuegt BLUETOOTH
    // (bis API 30) bzw. BLUETOOTH_CONNECT (ab API 31) — BLUETOOTH_SCAN, BLUETOOTH_ADMIN und (bis API 30) der Standort
    // gehoerten nur zum BLE-Scan, den es nicht mehr gibt.
    private val legacySet = arrayOf(Manifest.permission.BLUETOOTH)

    private val modernSet = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun `below API 31 returns only the legacy bluetooth permission`() {
        assertArrayEquals(legacySet, requiredPermissions(sdkInt = 30))
    }

    @Test
    fun `below API 31 does not include POST_NOTIFICATIONS`() {
        assertFalse(requiredPermissions(sdkInt = 30).contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `at API 31 returns BLUETOOTH_CONNECT instead of the legacy set`() {
        assertArrayEquals(modernSet, requiredPermissions(sdkInt = 31))
    }

    @Test
    fun `above API 31 returns BLUETOOTH_CONNECT`() {
        assertArrayEquals(modernSet, requiredPermissions(sdkInt = 32))
    }

    @Test
    fun `nothing the removed BLE scan needed is required any more, at any API level`() {
        // BLUETOOTH_SCAN und der Pflicht-Standort bis API 30 gehoerten nur zum BLE-Scan. Ein Nutzer, der Standort
        // ablehnt, war auf Android <= 11 grundlos ausgesperrt.
        val bleOnly = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        for (sdk in listOf(26, 28, 30, 31, 32, 33, 34, 36)) {
            val required = requiredPermissions(sdkInt = sdk).toList()
            for (permission in bleOnly) assertFalse("API $sdk fordert $permission", permission in required)
        }
    }

    @Test
    fun `POST_NOTIFICATIONS is never a required permission`() {
        // Ohne sie laeuft die App und der Vordergrunddienst; nur die Benachrichtigung bleibt unsichtbar.
        // Als Pflicht sperrte sie die ganze App aus, sobald der Nutzer sie zweimal ablehnte.
        for (sdk in listOf(26, 30, 31, 32, 33, 34, 36)) {
            assertFalse("API $sdk", requiredPermissions(sdkInt = sdk).contains(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    @Test
    fun `below API 33 there is no optional permission`() {
        assertArrayEquals(emptyArray<String>(), optionalPermissions(sdkInt = 32))
    }

    @Test
    fun `from API 33 POST_NOTIFICATIONS is requested as optional permission`() {
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), optionalPermissions(sdkInt = 33))
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), optionalPermissions(sdkInt = 36))
    }

    @Test
    fun `the system dialog asks for required and optional permissions together`() {
        assertArrayEquals(
            modernSet + Manifest.permission.POST_NOTIFICATIONS,
            requestedPermissions(sdkInt = 34),
        )
        assertArrayEquals(modernSet, requestedPermissions(sdkInt = 32))
    }

    @Test
    fun `the gate opens when only the optional permission is missing`() {
        val grantedExceptNotifications = { p: String -> p != Manifest.permission.POST_NOTIFICATIONS }
        assertTrue(allRequiredGranted(sdkInt = 34, isGranted = grantedExceptNotifications))
    }

    @Test
    fun `the gate stays closed when a required bluetooth permission is missing`() {
        val connectMissing = { p: String -> p != Manifest.permission.BLUETOOTH_CONNECT }
        assertFalse(allRequiredGranted(sdkInt = 34, isGranted = connectMissing))
        val nothing = { _: String -> false }
        assertFalse(allRequiredGranted(sdkInt = 30, isGranted = nothing))
    }

    @Test
    fun `an optional permission is only asked for while it is missing`() {
        val allGranted = { _: String -> true }
        assertFalse(anyRequestedMissing(sdkInt = 34, isGranted = allGranted))
        val notificationsMissing = { p: String -> p != Manifest.permission.POST_NOTIFICATIONS }
        assertTrue(anyRequestedMissing(sdkInt = 34, isGranted = notificationsMissing))
    }
}
