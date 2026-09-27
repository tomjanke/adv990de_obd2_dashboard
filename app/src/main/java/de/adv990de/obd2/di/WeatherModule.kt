package de.adv990de.obd2.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.weather.OpenMeteoApi
import de.adv990de.obd2.weather.WeatherApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WeatherModule {

    /** `@Provides` statt `@Binds`: [OpenMeteoApi] hat keinen `@Inject`-Konstruktor — sein einziger
     *  Parameter ist ein Test-Seam mit Kotlin-Default, den Dagger nicht kennt (s. dessen KDoc). */
    @Provides
    @Singleton
    fun provideWeatherApi(): WeatherApi = OpenMeteoApi()
}
