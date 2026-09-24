package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Klasse selbst behauptet in ihrer KDoc, im JVM-Test liesse sich nichts ausser
 * [AndroidLocationSpeedProvider.MIN_INTERVAL_MS] pruefen — das stimmt so nicht mehr: `Context`,
 * `LocationManager` und `Location` lassen sich wie ueberall sonst im Projekt (s.
 * `LocationPermissionsTest`, `BluetoothDeviceScannerTest`) per MockK durchsetzen, ohne dass ihre
 * echten (Stub-)Implementierungen je liefen. Was unten fehlt, bleibt bewusst aussen vor:
 * `onStatusChanged` ist ein reiner Pflicht-Override ohne jede Logik (s. Kommentar an der
 * Deklaration), dafuer gibt es nichts zu pruefen.
 */
@SuppressLint("UseCheckPermission") // s. LocationPermissionsTest: MockK-Matcher, kein echter Aufruf
class AndroidLocationSpeedProviderTest {

    // Bewusst kein relaxed Mock: jeder ungeplante Zugriff auf den Context soll den Test brechen.
    private val context = mockk<Context>()

    private fun locationWithSpeed(speed: Float?): Location = mockk {
        every { hasSpeed() } returns (speed != null)
        if (speed != null) every { this@mockk.speed } returns speed
    }

    /** Ergebnis einer erfolgreichen Registrierung: der (relaxed) `LocationManager`-Mock, der dabei
     *  abgefangene [LocationListener] und die Abmelde-Funktion, die [startUpdates] zurueckgegeben
     *  hat — damit ein Test Fixe simulieren und/oder das Abmelden verifizieren kann. */
    private class Registration(
        val manager: LocationManager,
        val listener: LocationListener,
        val stop: () -> Unit,
    )

    private fun registerAndCaptureListener(onSpeed: (Float?) -> Unit = {}): Registration {
        val manager = mockk<LocationManager>(relaxed = true)
        every { context.getSystemService(LocationManager::class.java) } returns manager
        val listenerSlot = slot<LocationListener>()
        every {
            manager.requestLocationUpdates(
                any<String>(), any<Long>(), any<Float>(), capture(listenerSlot), any<Looper>(),
            )
        } just Runs

        val stop = AndroidLocationSpeedProvider(context).startUpdates(onSpeed)

        assertNotNull("Registrierung haette eine Abmelde-Funktion liefern muessen", stop)
        return Registration(manager, listenerSlot.captured, stop!!)
    }

    // --- Regressionsschutz fuer das Anfrageintervall (bestehend) ---

    @Test
    fun `GPS updates are requested faster than the OBD poll cycle`() {
        assertEquals(200L, AndroidLocationSpeedProvider.MIN_INTERVAL_MS)
    }

    // --- hasPermission() ---

    @Test
    fun `hasPermission returns true when ACCESS_FINE_LOCATION is granted`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(AndroidLocationSpeedProvider(context).hasPermission())
    }

    @Test
    fun `hasPermission returns false when ACCESS_FINE_LOCATION is denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(AndroidLocationSpeedProvider(context).hasPermission())
    }

    // --- startUpdates(): kein LocationManager erreichbar ---

    @Test
    fun `startUpdates returns null when the system has no LocationManager`() {
        every { context.getSystemService(LocationManager::class.java) } returns null

        val stop = AndroidLocationSpeedProvider(context).startUpdates { }

        assertNull(stop)
    }

    // --- startUpdates(): Berechtigung zwischen Pruefung und Registrierung entzogen ---

    @Test
    fun `startUpdates returns null when requestLocationUpdates throws SecurityException`() {
        val manager = mockk<LocationManager>(relaxed = true)
        every { context.getSystemService(LocationManager::class.java) } returns manager
        every {
            manager.requestLocationUpdates(
                any<String>(), any<Long>(), any<Float>(), any<LocationListener>(), any<Looper>(),
            )
        } throws SecurityException("permission revoked between check and call")

        val stop = AndroidLocationSpeedProvider(context).startUpdates { }

        assertNull(stop)
    }

    // --- onLocationChanged(): hasSpeed() == true ---

    @Test
    fun `onLocationChanged reports the raw metres-per-second speed when the fix has one`() {
        val received = mutableListOf<Float?>()
        val registration = registerAndCaptureListener { received += it }

        registration.listener.onLocationChanged(locationWithSpeed(15f))

        // Die Umrechnung m/s -> km/h passiert bewusst erst in GpsSpeedSource (s. dessen KDoc) —
        // diese Klasse reicht den rohen Framework-Wert unveraendert durch.
        assertEquals(listOf(15f), received)
    }

    // --- onLocationChanged(): hasSpeed() == false ---

    @Test
    fun `onLocationChanged reports null when the fix carries no speed`() {
        val received = mutableListOf<Float?>()
        val registration = registerAndCaptureListener { received += it }

        registration.listener.onLocationChanged(locationWithSpeed(null))

        assertEquals(listOf<Float?>(null), received)
    }

    // --- onProviderDisabled() ---

    @Test
    fun `onProviderDisabled reports null`() {
        val received = mutableListOf<Float?>()
        val registration = registerAndCaptureListener { received += it }

        registration.listener.onProviderDisabled(LocationManager.GPS_PROVIDER)

        assertEquals(listOf<Float?>(null), received)
    }

    // --- Abmelde-Funktion ---

    @Test
    fun `the returned stop function unregisters exactly the listener that was registered`() {
        val registration = registerAndCaptureListener()

        registration.stop()

        verify { registration.manager.removeUpdates(registration.listener) }
    }
}
