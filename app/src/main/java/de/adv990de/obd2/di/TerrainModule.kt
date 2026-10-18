package de.adv990de.obd2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.terrain.AndroidPressureProvider
import de.adv990de.obd2.terrain.PressureProvider
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.terrain.TerrainSource
import javax.inject.Singleton

/**
 * Das `DashboardViewModel` kennt nur [TerrainInputs]; Hilt liefert die [TerrainSource]. Eigenes Modul, weil `AppModule` in den Instrumentation-Tests durch
 * `TestAppModule` ersetzt wird — diese Bindungen gelten dort unveraendert (wie `FuelModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TerrainModule {

    @Binds
    @Singleton
    abstract fun bindPressureProvider(impl: AndroidPressureProvider): PressureProvider

    @Binds
    abstract fun bindTerrainInputs(impl: TerrainSource): TerrainInputs
}
