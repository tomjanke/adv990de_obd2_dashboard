package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mit MockK lassen sich `Context`, `LocationManager` und `Location` wie im gesamten uebrigen Projekt
 * (s. `LocationPermissionsTest`, `BluetoothDeviceScannerTest`) durchsetzen.
 *
 * Seit der Entkopplung von der GPS-Geschwindigkeit genuegt der Wetterabfrage FINE ODER COARSE
 * (Android 12+ laesst den Nutzer "ungefaehr" waehlen). Deshalb stubbt [grant] beide Berechtigungen
 * immer explizit — unabhaengig davon, in welcher Reihenfolge `hasPermission()` sie abfragt.
 *
 * Die `SecurityException`-Tests decken zwei reale Faelle ab: eine zwischen Pruefung und Aufruf
 * entzogene Berechtigung, und — der wichtigere — API 26-30 mit nur COARSE: dort verlangt das System
 * fuer den GPS-Provider FINE und wirft, der Netzwerk-Provider muss dann trotzdem noch versucht werden.
 */
@SuppressLint("UseCheckPermission") // s. LocationPermissionsTest: MockK-Matcher, kein echter Aufruf
class AndroidCurrentLocationProviderTest {

    private val context = mockk<Context>()

    private fun grant(fine: Boolean, coarse: Boolean) {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any())
        } returns if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private fun location(lat: Double, lon: Double) = mockk<Location> {
        every { latitude } returns lat
        every { longitude } returns lon
    }

    private fun installManager(manager: LocationManager) {
        every { context.getSystemService(LocationManager::class.java) } returns manager
    }

    // --- hasPermission(): FINE oder COARSE genuegt ---

    @Test
    fun `hasPermission returns true when ACCESS_FINE_LOCATION is granted`() {
        grant(fine = true, coarse = true)

        assertTrue(AndroidCurrentLocationProvider(context).hasPermission())
    }

    @Test
    fun `hasPermission returns true when only ACCESS_COARSE_LOCATION is granted`() {
        // Android 12+: der Nutzer hat im System-Dialog "Ungefaehr" statt "Genau" gewaehlt.
        grant(fine = false, coarse = true)

        assertTrue(AndroidCurrentLocationProvider(context).hasPermission())
    }

    @Test
    fun `hasPermission returns false when neither location permission is granted`() {
        grant(fine = false, coarse = false)

        assertFalse(AndroidCurrentLocationProvider(context).hasPermission())
    }

    // --- lastKnownLocation(): permission absent ---

    @Test
    fun `lastKnownLocation returns null when neither permission is granted`() {
        grant(fine = false, coarse = false)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
        verify(exactly = 0) { context.getSystemService(LocationManager::class.java) }
    }

    // --- lastKnownLocation(): kein LocationManager erreichbar ---

    @Test
    fun `lastKnownLocation returns null when the system has no LocationManager`() {
        grant(fine = true, coarse = true)
        every { context.getSystemService(LocationManager::class.java) } returns null

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): GPS-Fix vorhanden ---

    @Test
    fun `lastKnownLocation returns the raw GPS latitude and longitude pair`() {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(52.52, 13.405)
        }
        installManager(manager)

        assertEquals(52.52 to 13.405, AndroidCurrentLocationProvider(context).lastKnownLocation())
        // GPS hat geliefert: der Netzwerk-Provider wird gar nicht erst gefragt.
        verify(exactly = 0) { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
    }

    // --- lastKnownLocation(): Rueckfall auf den Netzwerk-Provider ---

    @Test
    fun `lastKnownLocation falls back to the network provider when GPS has no fix`() {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(48.14, 11.58)
        }
        installManager(manager)

        assertEquals(48.14 to 11.58, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    @Test
    fun `lastKnownLocation falls back to the network provider when GPS throws with coarse-only permission`() {
        // API 26-30: mit nur COARSE lehnt das System den GPS-Provider per SecurityException ab —
        // das darf die Abfrage nicht beenden, der Netzwerk-Provider ist mit COARSE erlaubt.
        grant(fine = false, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
                SecurityException("\"gps\" location provider requires ACCESS_FINE_LOCATION permission.")
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(48.14, 11.58)
        }
        installManager(manager)

        assertEquals(48.14 to 11.58, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): keinen Fix bekommen ---

    @Test
    fun `lastKnownLocation returns null when neither provider has a location`() {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): SecurityException zwischen Pruefung und Aufruf ---

    @Test
    fun `lastKnownLocation returns null when both providers throw SecurityException`() {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
                SecurityException("permission revoked between check and call")
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } throws
                SecurityException("permission revoked between check and call")
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }
}
