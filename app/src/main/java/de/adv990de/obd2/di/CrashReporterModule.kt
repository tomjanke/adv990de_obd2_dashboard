package de.adv990de.obd2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.crash.SentryCrashReporter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CrashReporterModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: SentryCrashReporter): CrashReporter
}
