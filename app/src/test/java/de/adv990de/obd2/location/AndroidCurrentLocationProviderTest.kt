package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mit MockK lassen sich `Context`, `LocationManager` und `Location` wie im gesamten uebrigen Projekt
 * (s. `LocationPermissionsTest`, `BluetoothDeviceScannerTest`) durchsetzen. Was unten nicht getestet
 * wird: das Fangen der `SecurityException` in Fehlerszenarien, die praktisch nicht vorkommen (s.
 * `AndroidLocationSpeedProviderTest` Kommentar zu `onStatusChanged`) — dasselbe Muster, dieselbe
 * Abwaegung.
 */
@SuppressLint("UseCheckPermission") // s. LocationPermissionsTest: MockK-Matcher, kein echter Aufruf
class AndroidCurrentLocationProviderTest {

    private val context = mockk<Context>()

    // --- hasPermission() ---

    @Test
    fun `hasPermission returns true when ACCESS_FINE_LOCATION is granted`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(AndroidCurrentLocationProvider(context).hasPermission())
    }

    @Test
    fun `hasPermission returns false when ACCESS_FINE_LOCATION is denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(AndroidCurrentLocationProvider(context).hasPermission())
    }

    // --- lastKnownLocation(): permission absent ---

    @Test
    fun `lastKnownLocation returns null when permission is denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): kein LocationManager erreichbar ---

    @Test
    fun `lastKnownLocation returns null when the system has no LocationManager`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(LocationManager::class.java) } returns null

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): gelueftet ---

    @Test
    fun `lastKnownLocation returns the raw latitude and longitude pair`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        val location = mockk<Location> {
            every { latitude } returns 52.52
            every { longitude } returns 13.405
        }
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location
        }
        every { context.getSystemService(LocationManager::class.java) } returns manager

        assertEquals(52.52 to 13.405, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): keinen Fix bekommen ---

    @Test
    fun `lastKnownLocation returns null when the manager returns no location`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
        }
        every { context.getSystemService(LocationManager::class.java) } returns manager

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): SecurityException zwischen Pruefung und Aufruf ---

    @Test
    fun `lastKnownLocation returns null when getLastKnownLocation throws SecurityException`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
                SecurityException("permission revoked between check and call")
        }
        every { context.getSystemService(LocationManager::class.java) } returns manager

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }
}
