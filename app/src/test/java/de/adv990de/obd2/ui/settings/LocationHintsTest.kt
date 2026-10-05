package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann die Einstellungen eine fehlende Standortberechtigung sichtbar machen (B10, B21). */
class LocationHintsTest {

    // ── GPS-Geschwindigkeit ──────────────────────────────────────────────────

    @Test
    fun `GPS speed switched on but the permission is gone - the revoked case - shows the hint`() {
        assertTrue(gpsSpeedPermissionHint(useGpsSpeed = true, fineGranted = false, lastRequestDenied = false))
    }

    @Test
    fun `a request that was answered without precise location shows the hint although the switch stays off`() {
        // Android 12+: "Ungefaehr" im System-Dialog erteilt nur COARSE — der Schalter bleibt still aus.
        assertTrue(gpsSpeedPermissionHint(useGpsSpeed = false, fineGranted = false, lastRequestDenied = true))
    }

    @Test
    fun `with precise location there is nothing to explain`() {
        assertFalse(gpsSpeedPermissionHint(useGpsSpeed = true, fineGranted = true, lastRequestDenied = false))
        assertFalse(gpsSpeedPermissionHint(useGpsSpeed = false, fineGranted = true, lastRequestDenied = true))
    }

    @Test
    fun `a switch that was never turned on and never denied shows no hint`() {
        assertFalse(gpsSpeedPermissionHint(useGpsSpeed = false, fineGranted = false, lastRequestDenied = false))
    }

    // ── Aussentemperatur ─────────────────────────────────────────────────────

    @Test
    fun `hide-nav on without any location permission explains the missing outside temperature`() {
        assertTrue(weatherPermissionHint(hideNavOnDrive = true, fineGranted = false, coarseGranted = false))
    }

    @Test
    fun `approximate location is enough for the weather - no hint`() {
        assertFalse(weatherPermissionHint(hideNavOnDrive = true, fineGranted = false, coarseGranted = true))
        assertFalse(weatherPermissionHint(hideNavOnDrive = true, fineGranted = true, coarseGranted = false))
    }

    @Test
    fun `without hide-nav the weather is not shown anyway - no hint`() {
        assertFalse(weatherPermissionHint(hideNavOnDrive = false, fineGranted = false, coarseGranted = false))
    }
}
