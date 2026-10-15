package de.adv990de.obd2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.fuel.FuelRepository

/**
 * Das `DashboardViewModel` kennt nur die Schnittstelle [FuelDistanceSink]; Hilt liefert das [FuelRepository] (ein Singleton, dasselbe, das die
 * Tankanzeige in der Navigation liest). Eigenes Modul, weil `AppModule` in den Instrumentation-Tests durch `TestAppModule` ersetzt wird —
 * diese Bindung gilt dort unveraendert.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FuelModule {
    @Binds
    abstract fun bindFuelDistanceSink(impl: FuelRepository): FuelDistanceSink
}
