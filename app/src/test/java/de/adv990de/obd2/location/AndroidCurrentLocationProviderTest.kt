package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
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
@Suppress("DEPRECATION") // requestSingleUpdate: Produktion nutzt bewusst dieselbe API (kein Ersatz mit minSdk 26)
class AndroidCurrentLocationProviderTest {

    private val context = mockk<Context>()

    // Die monotone Uhr, an der das Alter eines gecachten Fixes gemessen wird (SEC-5). Im JVM-Test liefert die Stub-Uhr 0.
    private val nowNanos = 1_000_000_000_000L

    @Before
    fun fixClock() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtimeNanos() } returns nowNanos
    }

    @After
    fun releaseClock() {
        unmockkStatic(SystemClock::class)
    }

    private fun grant(fine: Boolean, coarse: Boolean) {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any())
        } returns if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    /** [ageMs]: wie alt der Fix ist. Ein frisch angeforderter ("aktiver") Fix hat das Alter 0, ein gecachter oft mehr. */
    private fun location(lat: Double, lon: Double, ageMs: Long = 0L) = mockk<Location> {
        every { latitude } returns lat
        every { longitude } returns lon
        every { elapsedRealtimeNanos } returns nowNanos - ageMs * 1_000_000L
    }

    private val minute = 60_000L

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
    fun `lastKnownLocation returns null when neither permission is granted`() = runTest {
        grant(fine = false, coarse = false)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
        verify(exactly = 0) { context.getSystemService(LocationManager::class.java) }
    }

    // --- lastKnownLocation(): kein LocationManager erreichbar ---

    @Test
    fun `lastKnownLocation returns null when the system has no LocationManager`() = runTest {
        grant(fine = true, coarse = true)
        every { context.getSystemService(LocationManager::class.java) } returns null

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): gecachter GPS-Fix vorhanden ---

    @Test
    fun `lastKnownLocation returns the raw cached GPS latitude and longitude pair`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(52.52, 13.405)
            // Beide Caches werden gelesen (der juengere gewinnt, s. unten); hier hat nur GPS einen Fix.
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
        }
        installManager(manager)

        assertEquals(52.52 to 13.405, AndroidCurrentLocationProvider(context).lastKnownLocation())
        // Der frische Cache hat geliefert: eine aktive Anfrage wird nicht gestartet.
        verify(exactly = 0) { manager.requestSingleUpdate(any<String>(), any(), any()) }
    }

    // --- lastKnownLocation(): Rueckfall auf den gecachten Netzwerk-Standort ---

    @Test
    fun `lastKnownLocation falls back to the cached network location when the GPS cache is empty`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(48.14, 11.58)
        }
        installManager(manager)

        assertEquals(48.14 to 11.58, AndroidCurrentLocationProvider(context).lastKnownLocation())
        verify(exactly = 0) { manager.requestSingleUpdate(any<String>(), any(), any()) }
    }

    @Test
    fun `lastKnownLocation falls back to the cached network location when GPS cache lookup throws with coarse-only permission`() = runTest {
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

    // --- lastKnownLocation(): Alter des Caches (SEC-5) ---

    @Test
    fun `lastKnownLocation prefers the younger of two fresh cached fixes - the network one here`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(1.0, 1.0, ageMs = 8 * minute)
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(2.0, 2.0, ageMs = 2 * minute)
        }
        installManager(manager)

        assertEquals(2.0 to 2.0, AndroidCurrentLocationProvider(context).lastKnownLocation())
        verify(exactly = 0) { manager.requestSingleUpdate(any<String>(), any(), any()) }
    }

    @Test
    fun `a stale cached GPS fix does not beat a fresh cached network fix`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(1.0, 1.0, ageMs = 3 * 60 * minute)
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(2.0, 2.0, ageMs = 30_000L)
        }
        installManager(manager)

        assertEquals(2.0 to 2.0, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    @Test
    fun `a stale cache is not used - a fresh fix is requested instead`() = runTest {
        grant(fine = true, coarse = true)
        val listener = slot<LocationListener>()
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(1.0, 1.0, ageMs = 3 * 60 * minute)
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns location(2.0, 2.0, ageMs = 5 * 60 * minute)
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, capture(listener), any()) } answers {
                listener.captured.onLocationChanged(location(52.52, 13.405))
            }
        }
        installManager(manager)

        assertEquals(52.52 to 13.405, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    @Test
    fun `when the cache is stale and no fresh fix arrives there is no location - never the stale one`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(1.0, 1.0, ageMs = 3 * 60 * minute)
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            every { requestSingleUpdate(any<String>(), any(), any()) } just Runs
            every { removeUpdates(any<LocationListener>()) } just Runs
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    @Test
    fun `a cached fix from the future - a monotonic clock that restarted - counts as stale`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns location(1.0, 1.0, ageMs = -10 * minute)
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            every { requestSingleUpdate(any<String>(), any(), any()) } just Runs
            every { removeUpdates(any<LocationListener>()) } just Runs
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): beide Caches leer -> aktive Anfrage ---

    @Test
    fun `lastKnownLocation actively requests a fresh GPS fix when both caches are empty`() = runTest {
        grant(fine = true, coarse = true)
        val listener = slot<LocationListener>()
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, capture(listener), any()) } answers {
                listener.captured.onLocationChanged(location(52.52, 13.405))
            }
        }
        installManager(manager)

        assertEquals(52.52 to 13.405, AndroidCurrentLocationProvider(context).lastKnownLocation())
        // Aktiver GPS-Fix hat geliefert: der Netzwerk-Provider wird gar nicht erst aktiv angefragt.
        verify(exactly = 0) { manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, any(), any()) }
    }

    @Test
    fun `lastKnownLocation falls back to an active network fix when the active GPS request never answers`() = runTest {
        grant(fine = true, coarse = true)
        val networkListener = slot<LocationListener>()
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            // GPS antwortet nie (Timeout) - kein Aufruf des Listeners.
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, any(), any()) } just Runs
            every { requestSingleUpdate(LocationManager.NETWORK_PROVIDER, capture(networkListener), any()) } answers {
                networkListener.captured.onLocationChanged(location(48.14, 11.58))
            }
            every { removeUpdates(any<LocationListener>()) } just Runs
        }
        installManager(manager)

        assertEquals(48.14 to 11.58, AndroidCurrentLocationProvider(context).lastKnownLocation())
        // Der ausstehende GPS-Listener wird beim Timeout abgemeldet, kein Leck.
        verify { manager.removeUpdates(any<LocationListener>()) }
    }

    @Test
    fun `lastKnownLocation returns null when no cache nor any active request ever answers`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            every { requestSingleUpdate(any<String>(), any(), any()) } just Runs
            every { removeUpdates(any<LocationListener>()) } just Runs
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    @Test
    fun `lastKnownLocation falls back to an active network fix when the active GPS request throws with coarse-only permission`() = runTest {
        grant(fine = false, coarse = true)
        val networkListener = slot<LocationListener>()
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
                SecurityException("\"gps\" location provider requires ACCESS_FINE_LOCATION permission.")
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, any(), any()) } throws
                SecurityException("\"gps\" location provider requires ACCESS_FINE_LOCATION permission.")
            every { requestSingleUpdate(LocationManager.NETWORK_PROVIDER, capture(networkListener), any()) } answers {
                networkListener.captured.onLocationChanged(location(48.14, 11.58))
            }
        }
        installManager(manager)

        assertEquals(48.14 to 11.58, AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): SecurityException zwischen Pruefung und Aufruf, kein Fix moeglich ---

    @Test
    fun `lastKnownLocation returns null when both providers throw SecurityException everywhere`() = runTest {
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
                SecurityException("permission revoked between check and call")
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } throws
                SecurityException("permission revoked between check and call")
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, any(), any()) } throws
                SecurityException("permission revoked between check and call")
            every { requestSingleUpdate(LocationManager.NETWORK_PROVIDER, any(), any()) } throws
                SecurityException("permission revoked between check and call")
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }

    // --- lastKnownLocation(): IllegalArgumentException aus requestSingleUpdate (Backlog-Sweep 2026-09-28) ---

    @Test
    fun `lastKnownLocation returns null instead of crashing when requestSingleUpdate throws IllegalArgumentException`() = runTest {
        // Ein Geraet ohne registrierten NETWORK_PROVIDER (z.B. ohne Google Play Services) laesst
        // requestSingleUpdate sofort mit IllegalArgumentException werfen, statt nie zu antworten.
        // Vor dem Fix war nur SecurityException gefangen - diese Exception lief ungefangen bis in
        // WeatherRepository.fetchCurrentTemperatureC()s "wirft nie"-Vertrag und von dort in
        // WeatherViewModels ungeschuetztes Poll-Flow, und haette dort bei jedem 60s-Poll gecrasht.
        grant(fine = true, coarse = true)
        val manager = mockk<LocationManager> {
            every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns null
            every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns null
            // GPS antwortet nie (Timeout), damit der Netzwerk-Zweig ueberhaupt versucht wird.
            every { requestSingleUpdate(LocationManager.GPS_PROVIDER, any(), any()) } just Runs
            every { requestSingleUpdate(LocationManager.NETWORK_PROVIDER, any(), any()) } throws
                IllegalArgumentException("provider doesn't exist: network")
            every { removeUpdates(any<LocationListener>()) } just Runs
        }
        installManager(manager)

        assertNull(AndroidCurrentLocationProvider(context).lastKnownLocation())
    }
}
