package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import de.adv990de.obd2.terrain.GpsFix
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

    // --- startFixUpdates() und toGpsFix(): die Gelaende-Kachel (2026-10-18) ---

    private fun fixLocation(
        speedMps: Float? = null,
        altitudeM: Double? = null,
        mslAltitudeM: Double? = null,
        verticalAccuracyM: Float? = null,
        bearingDeg: Float? = null,
        bearingAccuracyDeg: Float? = null,
        elapsedNanos: Long = 5_000_000_000L,
    ): Location = mockk {
        every { elapsedRealtimeNanos } returns elapsedNanos
        every { hasSpeed() } returns (speedMps != null)
        if (speedMps != null) every { speed } returns speedMps
        every { hasAltitude() } returns (altitudeM != null)
        if (altitudeM != null) every { altitude } returns altitudeM
        every { hasMslAltitude() } returns (mslAltitudeM != null)
        if (mslAltitudeM != null) every { mslAltitudeMeters } returns mslAltitudeM
        every { hasVerticalAccuracy() } returns (verticalAccuracyM != null)
        if (verticalAccuracyM != null) every { verticalAccuracyMeters } returns verticalAccuracyM
        every { hasBearing() } returns (bearingDeg != null)
        if (bearingDeg != null) every { bearing } returns bearingDeg
        every { hasBearingAccuracy() } returns (bearingAccuracyDeg != null)
        if (bearingAccuracyDeg != null) every { bearingAccuracyDegrees } returns bearingAccuracyDeg
    }

    private fun registerFixAndCapture(
        intervalMs: Long = 1_000L,
        onFix: (GpsFix?) -> Unit = {},
    ): Registration {
        val manager = mockk<LocationManager>(relaxed = true)
        every { context.getSystemService(LocationManager::class.java) } returns manager
        val listenerSlot = slot<LocationListener>()
        every {
            manager.requestLocationUpdates(
                any<String>(), any<Long>(), any<Float>(), capture(listenerSlot), any<Looper>(),
            )
        } just Runs
        val stop = AndroidLocationSpeedProvider(context).startFixUpdates(intervalMs, onFix)
        assertNotNull("Registrierung haette eine Abmelde-Funktion liefern muessen", stop)
        return Registration(manager, listenerSlot.captured, stop!!)
    }

    @Test
    fun `the fix listener asks the GPS provider for the requested interval`() {
        val registration = registerFixAndCapture(intervalMs = 1_000L)

        verify {
            registration.manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, registration.listener, any<Looper>(),
            )
        }
    }

    @Test
    fun `an unsupported default implementation does not register anything`() {
        val speedOnly = object : LocationSpeedProvider {
            override fun hasPermission() = true
            override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? = null
        }

        assertNull(speedOnly.startFixUpdates(1_000L) { })
    }

    @Test
    fun `startFixUpdates returns null when the system has no LocationManager`() {
        every { context.getSystemService(LocationManager::class.java) } returns null

        assertNull(AndroidLocationSpeedProvider(context).startFixUpdates(1_000L) { })
    }

    @Test
    fun `startFixUpdates returns null when the permission is revoked between check and call`() {
        val manager = mockk<LocationManager>(relaxed = true)
        every { context.getSystemService(LocationManager::class.java) } returns manager
        every {
            manager.requestLocationUpdates(
                any<String>(), any<Long>(), any<Float>(), any<LocationListener>(), any<Looper>(),
            )
        } throws SecurityException("permission revoked")

        assertNull(AndroidLocationSpeedProvider(context).startFixUpdates(1_000L) { })
    }

    @Test
    fun `a fix is passed on with every field the fix carries`() {
        val received = mutableListOf<GpsFix?>()
        val registration = registerFixAndCapture { received += it }

        registration.listener.onLocationChanged(
            fixLocation(
                speedMps = 10f, altitudeM = 123.5, verticalAccuracyM = 4f,
                bearingDeg = 270f, bearingAccuracyDeg = 12f, elapsedNanos = 7_500_000_000L,
            ),
        )

        assertEquals(
            listOf<GpsFix?>(
                GpsFix(
                    elapsedMs = 7_500L, speedKmh = 36f, altitudeM = 123.5f, verticalAccuracyM = 4f,
                    bearingDeg = 270f, bearingAccuracyDeg = 12f,
                ),
            ),
            received,
        )
    }

    @Test
    fun `a field the fix does not carry is null, never zero`() {
        val received = mutableListOf<GpsFix?>()
        val registration = registerFixAndCapture { received += it }

        registration.listener.onLocationChanged(fixLocation())

        val fix = received.single()!!
        assertNull(fix.speedKmh)
        assertNull(fix.altitudeM)
        assertNull(fix.verticalAccuracyM)
        assertNull(fix.bearingDeg)
        assertNull(fix.bearingAccuracyDeg)
    }

    @Test
    fun `a disabled provider reports no fix`() {
        val received = mutableListOf<GpsFix?>()
        val registration = registerFixAndCapture { received += it }

        registration.listener.onProviderDisabled(LocationManager.GPS_PROVIDER)

        assertEquals(listOf<GpsFix?>(null), received)
    }

    @Test
    fun `the fix stop function unregisters exactly the registered listener`() {
        val registration = registerFixAndCapture()

        registration.stop()

        verify { registration.manager.removeUpdates(registration.listener) }
    }

    @Test
    fun `from Android 14 the altitude above sea level is preferred`() {
        val fix = fixLocation(altitudeM = 100.0, mslAltitudeM = 55.0).toGpsFix(sdkInt = 34)

        assertEquals(55f, fix.altitudeM!!, 0.001f)
    }

    @Test
    fun `before Android 14 the ellipsoid altitude is used even if a value would exist`() {
        val fix = fixLocation(altitudeM = 100.0, mslAltitudeM = 55.0).toGpsFix(sdkInt = 33)

        assertEquals(100f, fix.altitudeM!!, 0.001f)
    }

    @Test
    fun `without a sea level altitude the ellipsoid altitude is used on Android 14 too`() {
        val fix = fixLocation(altitudeM = 100.0).toGpsFix(sdkInt = 34)

        assertEquals(100f, fix.altitudeM!!, 0.001f)
    }

    @Test
    fun `the existing speed listener is unchanged by the shared registration`() {
        val received = mutableListOf<Float?>()
        val registration = registerAndCaptureListener { received += it }

        registration.listener.onLocationChanged(locationWithSpeed(15f))
        registration.listener.onProviderDisabled(LocationManager.GPS_PROVIDER)

        assertEquals(listOf<Float?>(15f, null), received)
        verify {
            registration.manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, AndroidLocationSpeedProvider.MIN_INTERVAL_MS, 0f, registration.listener, any<Looper>(),
            )
        }
    }
}
