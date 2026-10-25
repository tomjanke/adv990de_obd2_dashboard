package de.adv990de.obd2.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.data.LogbookOdometer
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.fuel.FuelRepository
import javax.inject.Singleton

/**
 * Das `DashboardViewModel` kennt nur die Schnittstelle [FuelDistanceSink]; Hilt liefert einen Sink, der jede Strecke an **beide** Zaehler weitergibt: das [FuelRepository] (die Tankanzeige) und
 * das [LogbookOdometer] (der geschaetzte Kilometerstand des Logbuchs) — beides Singletons, dieselben, die die Oberflaeche liest. Eigenes Modul, weil `AppModule` in den Instrumentation-Tests
 * durch `TestAppModule` ersetzt wird — diese Bindung gilt dort unveraendert.
 */
@Module
@InstallIn(SingletonComponent::class)
object FuelModule {
    @Provides
    @Singleton
    fun provideFuelDistanceSink(fuel: FuelRepository, odometer: LogbookOdometer): FuelDistanceSink = FuelDistanceSink.fanOut(fuel, odometer)
}
