package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
    private val context = mockk<Context>()

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

    // --- hasCoarseLocation: exaktes Gegenstueck zu hasFineLocation fuer ACCESS_COARSE_LOCATION
    // (die Wetterabfrage kommt mit dem ungefaehren Standort aus, s. AndroidCurrentLocationProvider).

    @Test
    fun `hasCoarseLocation returns true when granted`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissions.hasCoarseLocation(context))

        verify { context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any()) }
    }

    @Test
    fun `hasCoarseLocation returns false when denied`() {
        every {
            context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(LocationPermissions.hasCoarseLocation(context))

        verify { context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any()) }
    }

    // --- hasAnyLocation: genau ODER ungefaehr genuegt (der Dienst-Typ `location` braucht keine bestimmte der beiden). Der Mock ist nicht relaxed:
    // beide Berechtigungen werden je Test festgelegt, damit auch die Kurzschluss-Reihenfolge nicht am Mock scheitert.

    private fun grants(fine: Boolean, coarse: Boolean) {
        every { context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) } returns
            if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any()) } returns
            if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    @Test
    fun `hasAnyLocation is true with only fine location`() {
        grants(fine = true, coarse = false)
        assertTrue(LocationPermissions.hasAnyLocation(context))
    }

    @Test
    fun `hasAnyLocation is true with only coarse location`() {
        grants(fine = false, coarse = true)
        assertTrue(LocationPermissions.hasAnyLocation(context))
    }

    @Test
    fun `hasAnyLocation is true with both`() {
        grants(fine = true, coarse = true)
        assertTrue(LocationPermissions.hasAnyLocation(context))
    }

    @Test
    fun `hasAnyLocation is false with neither`() {
        grants(fine = false, coarse = false)
        assertFalse(LocationPermissions.hasAnyLocation(context))
    }
}
