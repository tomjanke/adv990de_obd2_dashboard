package de.adv990de.obd2.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.BuildConfig
import de.adv990de.obd2.weather.OpenMeteoApi
import de.adv990de.obd2.weather.WeatherApi
import de.adv990de.obd2.weather.weatherUserAgent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WeatherModule {

    /** `@Provides` statt `@Binds`: [OpenMeteoApi] hat keinen `@Inject`-Konstruktor — seine Parameter sind ein
     *  Kotlin-Default und ein Test-Seam, die Dagger nicht kennt (s. dessen KDoc). */
    @Provides
    @Singleton
    fun provideWeatherApi(): WeatherApi = OpenMeteoApi(userAgent = weatherUserAgent(BuildConfig.VERSION_NAME))
}
