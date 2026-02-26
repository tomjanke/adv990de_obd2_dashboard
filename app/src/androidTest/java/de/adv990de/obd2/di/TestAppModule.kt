package de.adv990de.obd2.di

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.data.dataStore
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.AppLogDao
import io.mockk.mockk
import javax.inject.Singleton

/**
 * Replaces [AppModule] for all androidTest instrumented tests.
 *
 * Provides only the bindings that the full Hilt component graph requires for the
 * ViewModels registered across the app. [ObdForegroundService] itself only injects
 * [de.adv990de.obd2.data.ObdRepository], which is supplied via [@BindValue] in each test
 * class — Hilt never constructs ObdRepository and therefore never needs its own deps
 * (ConnectionFactory, PidParser, DtcParser).
 *
 * The remaining bindings (AppDatabase, AppLogDao, BluetoothAdapter, BluetoothDeviceScanner,
 * DataStore) are needed because other @HiltViewModels in the app contribute to the
 * SingletonC / ViewModelC component that Hilt validates at compile-time.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    /** In-memory database — no file I/O, no migrations needed in tests. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides
    @Singleton
    fun provideAppLogDao(db: AppDatabase): AppLogDao = db.appLogDao()

    /**
     * Returns a mock BluetoothAdapter. The real adapter requires hardware and the
     * BLUETOOTH_CONNECT permission; neither is available in CI / emulator service tests.
     */
    @Provides
    @Singleton
    fun provideBluetoothAdapter(): BluetoothAdapter = mockk(relaxed = true)

    /**
     * Returns a mock BluetoothDeviceScanner. SettingsViewModel injects this directly;
     * scanning is not exercised in service tests.
     */
    @Provides
    @Singleton
    fun provideBluetoothDeviceScanner(): BluetoothDeviceScanner = mockk(relaxed = true)

    /**
     * Provides the real DataStore backed by the test application context. AppPreferences
     * uses DataStore for all preference reads/writes; the in-process store is safe for tests.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.dataStore
}
