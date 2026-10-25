package de.adv990de.obd2.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import javax.inject.Inject

/**
 * Der GPS-Zaehler des Kilometerstands im echten Hilt-Graphen mit der Test-Datenbank und dem Test-DataStore: der Sink, den das Dashboard bekommt, zaehlt fuer die Tankanzeige UND den
 * Kilometerstand; der Zaehler gehoert zum hoechsten Stand des Logbuchs, beginnt mit einem neuen hoeheren Stand neu und kommt im Speicher an.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LogbookOdometerDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var sink: FuelDistanceSink
    @Inject lateinit var odometer: LogbookOdometer
    @Inject lateinit var fuel: FuelRepository
    @Inject lateinit var repository: VehicleLogRepository
    @Inject lateinit var appPreferences: AppPreferences

    @Before fun setup() { hiltRule.inject() }

    private fun entry(km: Int) = LogEntry(type = LogType.CHAIN_SMALL, date = LocalDate.of(2026, 10, 1), odometerKm = km, amountMl = null, fullTank = null, costCents = null, note = null, brand = null)

    private suspend fun counterAt(anchorKm: Int): GpsOdometer = withTimeout(5_000) { odometer.counter.first { it?.anchorKm == anchorKm }!! }

    @Test
    fun withoutAnEntry_thereIsNoCounter_andKilometresCountForNothing() = runBlocking {
        sink.addDistance(3f)

        assertNull(withTimeout(5_000) { odometer.counter.first() })
        assertNull(appPreferences.gpsOdometer.first())
    }

    @Test
    fun theSinkCountsForTheTankGaugeAndForTheOdometerAlike() = runBlocking {
        appPreferences.setFuelRangeFullKm(300)
        repository.save(entry(40_000))
        counterAt(40_000)

        sink.addDistance(2f)

        assertEquals(2f, counterAt(40_000).gpsKm, 1e-4f)
        assertEquals(2f, withTimeout(5_000) { fuel.state.first { it?.kmSinceFill == 2f } }!!.kmSinceFill, 1e-4f)
    }

    @Test
    fun aNewHigherReadingStartsTheCounterOver_andTheNewAnchorIsStoredAtOnce() = runBlocking {
        repository.save(entry(40_000))
        counterAt(40_000)
        sink.addDistance(5f)
        assertEquals(5f, counterAt(40_000).gpsKm, 1e-4f)

        repository.save(entry(40_300))

        assertEquals(0f, counterAt(40_300).gpsKm, 1e-4f)
        assertEquals(GpsOdometer(40_300, 0f), withTimeout(5_000) { appPreferences.gpsOdometer.first { it?.anchorKm == 40_300 } })
    }

    @Test
    fun flushWritesTheCounterToTheStore() = runBlocking {
        repository.save(entry(40_000))
        counterAt(40_000)
        sink.addDistance(0.3f)

        sink.flush()

        val stored = withTimeout(5_000) { appPreferences.gpsOdometer.first { it?.gpsKm == 0.3f } }
        assertEquals(GpsOdometer(40_000, 0.3f), stored)
    }

    @Test
    fun deletingTheHighestEntryStartsOverAtTheNewHighest() = runBlocking {
        val ids = listOf(repository.save(entry(40_000)), repository.save(entry(40_300)))
        counterAt(40_300)
        sink.addDistance(7f)

        repository.delete(listOf(ids[1]))

        assertEquals(0f, counterAt(40_000).gpsKm, 1e-4f)
    }
}
