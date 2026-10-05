package de.adv990de.obd2.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.data.dataStore
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.appDatabaseBuilder
import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.DtcHistoryDao
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.PidParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        // Name, Migrationen und (nicht vorhandener) Callback stehen in appDatabaseBuilder — dem Builder, den auch die
        // Tests benutzen. Foreign Keys setzt Rooms generierter onOpen selbst (AppDatabaseBootstrapTest, B36).
        appDatabaseBuilder(ctx, "obd2_db").build()

    @Provides @Singleton
    fun provideBluetoothAdapter(@ApplicationContext ctx: Context): BluetoothAdapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Provides @Singleton
    fun provideConnectionFactory(@ApplicationContext ctx: Context) = ConnectionFactory(ctx)

    @Provides @Singleton
    fun provideBluetoothDeviceScanner(
        @ApplicationContext ctx: Context,
        adapter: BluetoothAdapter
    ) = BluetoothDeviceScanner(ctx, adapter)

    @Provides @Singleton fun providePidParser() = PidParser()
    @Provides @Singleton fun provideDtcParser() = DtcParser()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.dataStore

    @Provides @Singleton
    fun provideAppLogDao(db: AppDatabase): AppLogDao = db.appLogDao()

    @Provides @Singleton
    fun provideDtcHistoryDao(db: AppDatabase): DtcHistoryDao = db.dtcHistoryDao()

    /** Never cancelled; a failing child does not take the others down (SupervisorJob). The work
     *  inside is suspend calls into Room, which switch threads themselves, so Default is enough. */
    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
