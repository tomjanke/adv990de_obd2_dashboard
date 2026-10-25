package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Fahrzeugspezifische Getriebedaten fuer die virtuelle Ganganzeige, aus den Einstellungen zusammengesetzt. `Eagerly`: wird
 * synchron ueber `.value` im `DashboardViewModel` gelesen, nicht ueber einen Compose-Collector
 * gehalten — ohne `Eagerly` bliebe der Wert fuer immer auf dem Default stehen (derselbe
 * Fehler, der die GPS-Geschwindigkeit bis 1.10.1 nie im Dashboard ankommen liess, siehe
 * `gpsSpeedFlow`).
 */
internal fun gearRatiosFlow(appPreferences: AppPreferences, scope: CoroutineScope): StateFlow<GearRatios> = combine(
    appPreferences.wheelCircumferenceMm,
    appPreferences.primaryRatio,
    appPreferences.secondaryRatio,
    appPreferences.gearRatios,
) { wheelCircumferenceMm, primaryRatio, secondaryRatio, ratios ->
    GearRatios(wheelCircumferenceMm, primaryRatio, secondaryRatio, ratios)
}.stateIn(
    scope,
    SharingStarted.Eagerly,
    GearRatios(
        AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
        AppPreferences.DEFAULT_PRIMARY_RATIO,
        AppPreferences.DEFAULT_SECONDARY_RATIO,
        AppPreferences.DEFAULT_GEAR_RATIOS,
    )
)
