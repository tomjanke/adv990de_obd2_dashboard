package de.adv990de.obd2.di

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.data.appPreferencesCorruptionHandler
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.DtcHistoryDao
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID
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

    /** Same provider as [AppModule.provideDtcHistoryDao] — this module replaces AppModule, so
     *  everything that injects the DAO (DtcHistoryRepository) needs it here too. */
    @Provides
    @Singleton
    fun provideDtcHistoryDao(db: AppDatabase): DtcHistoryDao = db.dtcHistoryDao()

    /** Same provider as [AppModule.provideApplicationScope] — this module replaces AppModule, so
     *  everything that injects the app-wide scope (e.g. AppLogsViewModel) needs it here too. */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * A DataStore of its own per test (a fresh [dagger.hilt.android.testing.HiltAndroidTest] component builds a fresh
     * one), backed by a unique temp file in the cache directory — NOT the app's real `app_prefs` store.
     *
     * Until 1.24.4 every instrumentation test shared the debug app's real DataStore (B37): results depended on
     * the order the tests ran in, and tests overwrote the developer's own settings on the device (an empty
     * `loggedPidIds` left behind meant a later real recording would have logged nothing). Each test now starts from
     * the defaults and leaves nothing behind; one unique file per store also avoids DataStore's "multiple
     * DataStores active for the same file" failure when several components live in one process. The corruption
     * handler is the production one, so it is exercised the same way.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> {
        val dir = File(ctx.cacheDir, "test_prefs")
        // Files of earlier tests are dead weight: their stores are gone with their components.
        dir.deleteRecursively()
        return PreferenceDataStoreFactory.create(
            corruptionHandler = appPreferencesCorruptionHandler(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(dir, "prefs_${UUID.randomUUID()}.preferences_pb") },
        )
    }
}
