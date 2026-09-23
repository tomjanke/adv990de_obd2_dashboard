package de.adv990de.obd2.ui.permission

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {

    // Build.VERSION_CODES.S = 31, TIRAMISU = 33 (hartcodiert, damit der Test ohne
    // Robolectric/android.jar-Stub auskommt).
    private val legacySet = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    private val modernSet = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun `below API 31 returns the legacy bluetooth and location set`() {
        assertArrayEquals(legacySet, requiredPermissions(sdkInt = 30))
    }

    @Test
    fun `below API 31 does not include POST_NOTIFICATIONS`() {
        assertFalse(requiredPermissions(sdkInt = 30).contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `at API 31 returns BLUETOOTH_SCAN and BLUETOOTH_CONNECT instead of legacy set`() {
        assertArrayEquals(modernSet, requiredPermissions(sdkInt = 31))
    }

    @Test
    fun `above API 31 returns BLUETOOTH_SCAN and BLUETOOTH_CONNECT`() {
        assertArrayEquals(modernSet, requiredPermissions(sdkInt = 32))
    }

    @Test
    fun `below API 33 does not request POST_NOTIFICATIONS`() {
        val result = requiredPermissions(sdkInt = 32)
        assertFalse(result.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertArrayEquals(modernSet, result)
    }

    @Test
    fun `at API 33 adds POST_NOTIFICATIONS on top of the modern set`() {
        val result = requiredPermissions(sdkInt = 33)
        assertTrue(result.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertArrayEquals(modernSet + Manifest.permission.POST_NOTIFICATIONS, result)
    }

    @Test
    fun `above API 33 keeps requesting POST_NOTIFICATIONS`() {
        val result = requiredPermissions(sdkInt = 34)
        assertTrue(result.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertArrayEquals(modernSet + Manifest.permission.POST_NOTIFICATIONS, result)
    }
}
