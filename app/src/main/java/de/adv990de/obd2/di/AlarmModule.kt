package de.adv990de.obd2.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.alarm.AndroidAlarmNotifier
import de.adv990de.obd2.data.AppLogRepository

/**
 * Der `AlarmController` kennt nur [AlarmNotifier] und [AlarmLog]; Hilt liefert die Android-Umsetzung und das App-Log. Eigenes Modul, weil `AppModule` in den Instrumentation-Tests
 * durch `TestAppModule` ersetzt wird — diese Bindungen gelten dort unverändert, und ein Test mit einer Attrappe tauscht sie mit `@UninstallModules(AlarmModule::class)` aus
 * (wie bei `FuelModule` und `TerrainModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmModule {

    @Binds
    abstract fun bindAlarmNotifier(impl: AndroidAlarmNotifier): AlarmNotifier

    companion object {
        /** Das App-Log schreibt nie eine Ausnahme heraus, der Controller muss also nichts abfangen. */
        @Provides
        fun provideAlarmLog(appLogRepository: AppLogRepository): AlarmLog = AlarmLog { level, message -> appLogRepository.log(level, message) }
    }
}
