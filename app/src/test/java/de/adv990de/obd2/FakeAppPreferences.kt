package de.adv990de.obd2

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * Ein [AppPreferences] fuer JVM-Tests: jeder Lese-Flow liefert genau einen Wert, standardmaessig den, den die echte Klasse bei
 * leerem Speicher liefert (`FakeAppPreferencesTest` haelt das gegen die echte Klasse fest — Standardwerte stehen so einmal
 * da, nicht in sieben `makePrefs`). Ein Test nennt nur, was fuer ihn zaehlt: `fakeAppPreferences(useGpsSpeed = true)`.
 *
 * Streng (kein `relaxed`): ein Schreibzugriff (`setX`) ist nicht gestubbt und scheitert, bis der Test ihn mit `coEvery { … } just Runs`
 * freigibt — er will sehen, ob die Produktion schreibt. Die Namen der Parameter sind die der Properties.
 */
internal fun fakeAppPreferences(
    lastDevice: ScannedDevice? = null,
    showAllBtDevices: Boolean = false,
    logOnlyDtcSessions: Boolean = false,
    hideNavOnDrive: Boolean = false,
    crashReportsEnabled: Boolean = true,
    useGpsSpeed: Boolean = false,
    backgroundLocationPrompted: Boolean = false,
    weatherLocationPrompted: Boolean = false,
    useImperial: Boolean = false,
    theme: String = AppPreferences.THEME_SYSTEM,
    rpmBlinkThreshold: Int = 0,
    rpmDynamicThreshold: Boolean = false,
    slotPids: List<String> = AppPreferences.DEFAULT_SLOT_PIDS,
    wheelCircumferenceMm: Float = AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
    primaryRatio: Float = AppPreferences.DEFAULT_PRIMARY_RATIO,
    secondaryRatio: Float = AppPreferences.DEFAULT_SECONDARY_RATIO,
    gearRatios: List<Float> = AppPreferences.DEFAULT_GEAR_RATIOS,
    obdWatchdogTimeoutSeconds: Int = AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS,
    reconnectBaseDelaySeconds: Int = AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS,
    fuelRangeFullKm: Int = 0,
    fuelKmSinceFill: Float = 0f,
    alarmSettings: AlarmSettings = AlarmSettings(),
): AppPreferences = mockk {
    every { this@mockk.lastDevice } returns flowOf(lastDevice)
    every { this@mockk.showAllBtDevices } returns flowOf(showAllBtDevices)
    every { this@mockk.logOnlyDtcSessions } returns flowOf(logOnlyDtcSessions)
    every { this@mockk.hideNavOnDrive } returns flowOf(hideNavOnDrive)
    every { this@mockk.crashReportsEnabled } returns flowOf(crashReportsEnabled)
    every { this@mockk.useGpsSpeed } returns flowOf(useGpsSpeed)
    every { this@mockk.backgroundLocationPrompted } returns flowOf(backgroundLocationPrompted)
    every { this@mockk.weatherLocationPrompted } returns flowOf(weatherLocationPrompted)
    every { this@mockk.useImperial } returns flowOf(useImperial)
    every { this@mockk.theme } returns flowOf(theme)
    every { this@mockk.rpmBlinkThreshold } returns flowOf(rpmBlinkThreshold)
    every { this@mockk.rpmDynamicThreshold } returns flowOf(rpmDynamicThreshold)
    every { this@mockk.slotPids } returns flowOf(slotPids)
    every { this@mockk.wheelCircumferenceMm } returns flowOf(wheelCircumferenceMm)
    every { this@mockk.primaryRatio } returns flowOf(primaryRatio)
    every { this@mockk.secondaryRatio } returns flowOf(secondaryRatio)
    every { this@mockk.gearRatios } returns flowOf(gearRatios)
    every { this@mockk.obdWatchdogTimeoutSeconds } returns flowOf(obdWatchdogTimeoutSeconds)
    every { this@mockk.reconnectBaseDelaySeconds } returns flowOf(reconnectBaseDelaySeconds)
    every { this@mockk.fuelRangeFullKm } returns flowOf(fuelRangeFullKm)
    every { this@mockk.fuelKmSinceFill } returns flowOf(fuelKmSinceFill)
    every { this@mockk.alarmSettings } returns flowOf(alarmSettings)
}
