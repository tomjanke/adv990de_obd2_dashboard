package de.adv990de.obd2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.location.AndroidLocationSpeedProvider
import de.adv990de.obd2.location.LocationSpeedProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {

    @Binds
    @Singleton
    abstract fun bindLocationSpeedProvider(impl: AndroidLocationSpeedProvider): LocationSpeedProvider
}
