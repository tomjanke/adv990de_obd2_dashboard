package de.adv990de.obd2.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.data.dataStore
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.PidParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "obd2_db")
            .fallbackToDestructiveMigration()
            .build()

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
}
