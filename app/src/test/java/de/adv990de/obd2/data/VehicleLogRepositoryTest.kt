package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.VehicleLogDao
import de.adv990de.obd2.data.db.entity.VehicleLogEntity
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Das Logbuch-Repository: Abbildung Eintrag <-> Zeile, Speichern, Loeschen in Bloecken, Import in einem Schritt. */
class VehicleLogRepositoryTest {

    private val day = LocalDate.of(2026, 10, 2)

    private val fuel = LogEntry(
        id = 0, type = LogType.FUEL, date = day, odometerKm = 40_362, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = "Shell, Autobahn", brand = null,
    )
    private val tires = LogEntry(
        id = 0, type = LogType.TIRE_CHANGE, date = day, odometerKm = 40_000, amountMl = null,
        fullTank = null, costCents = null, note = null, brand = "Michelin Anakee Adventure 150/70-18",
    )

    private fun row(type: String, id: Long = 1, epochDay: Long = day.toEpochDay(), km: Int = 40_362) = VehicleLogEntity(
        id = id, type = type, epochDay = epochDay, odometerKm = km,
        amountMl = null, fullTank = null, costCents = null, note = null, brand = null,
    )

    private val dao = mockk<VehicleLogDao> {
        coEvery { upsert(any()) } returns 5L
        coEvery { insertAll(any()) } just Runs
        coEvery { deleteByIds(any()) } just Runs
    }
    private val repo = VehicleLogRepository(dao)

    // --- Abbildung ---

    @Test
    fun `an entry is stored in whole units - the type by name, the day as epoch day, nothing as floating point`() = runTest {
        val saved = slot<VehicleLogEntity>()
        coEvery { dao.upsert(capture(saved)) } returns 9L

        repo.save(fuel)

        assertEquals(
            VehicleLogEntity(
                id = 0, type = "FUEL", epochDay = day.toEpochDay(), odometerKm = 40_362,
                amountMl = 14_370, fullTank = true, costCents = 2_743, note = "Shell, Autobahn", brand = null,
            ),
            saved.captured,
        )
    }

    @Test
    fun `the brand of a tire change is stored with it`() = runTest {
        val saved = slot<VehicleLogEntity>()
        coEvery { dao.upsert(capture(saved)) } returns 9L

        repo.save(tires)

        assertEquals("TIRE_CHANGE", saved.captured.type)
        assertEquals("Michelin Anakee Adventure 150/70-18", saved.captured.brand)
    }

    @Test
    fun `rows come back as entries with every field`() = runTest {
        every { dao.observeAll() } returns flowOf(
            listOf(
                VehicleLogEntity(
                    id = 3, type = "FUEL", epochDay = day.toEpochDay(), odometerKm = 40_362,
                    amountMl = 14_370, fullTank = false, costCents = 2_743, note = "n", brand = null,
                ),
                row("TIRE_CHANGE", id = 2).copy(brand = "Heidenau K60"),
            ),
        )

        val entries = repo.entries.first()

        assertEquals(
            listOf(
                LogEntry(3, LogType.FUEL, day, 40_362, 14_370, false, 2_743, "n", null),
                LogEntry(2, LogType.TIRE_CHANGE, day, 40_362, null, null, null, null, "Heidenau K60"),
            ),
            entries,
        )
    }

    @Test
    fun `a row with a type this version does not know is skipped, not a crash`() = runTest {
        every { dao.observeAll() } returns flowOf(listOf(row("FUEL", id = 1), row("FROM_THE_FUTURE", id = 2), row("OIL", id = 3)))

        assertEquals(listOf(1L, 3L), repo.entries.first().map { it.id })
    }

    @Test
    fun `get returns the entry, and nothing for a missing id or an unknown type`() = runTest {
        coEvery { dao.getById(1) } returns row("SERVICE_LARGE", id = 1)
        coEvery { dao.getById(2) } returns null
        coEvery { dao.getById(3) } returns row("FROM_THE_FUTURE", id = 3)

        assertEquals(LogType.SERVICE_LARGE, repo.get(1)!!.type)
        assertNull(repo.get(2))
        assertNull(repo.get(3))
    }

    // --- Speichern ---

    @Test
    fun `saving returns the id the database chose, and an existing id is kept`() = runTest {
        coEvery { dao.upsert(match { it.id == 0L }) } returns 41L
        coEvery { dao.upsert(match { it.id == 7L }) } returns 7L

        assertEquals(41L, repo.save(fuel))
        assertEquals(7L, repo.save(fuel.copy(id = 7)))
        coVerify(exactly = 1) { dao.upsert(match { it.id == 7L }) }
    }

    // --- Loeschen ---

    @Test
    fun `deleting works in chunks of 50 and ignores duplicates`() = runTest {
        repo.delete((1L..120L).toList() + listOf(1L, 2L, 3L))

        coVerify(exactly = 1) { dao.deleteByIds(match { it.size == 50 && it.first() == 1L }) }
        coVerify(exactly = 1) { dao.deleteByIds(match { it.size == 50 && it.first() == 51L }) }
        coVerify(exactly = 1) { dao.deleteByIds(match { it.size == 20 && it.last() == 120L }) }
    }

    @Test
    fun `deleting nothing touches nothing`() = runTest {
        repo.delete(emptyList())

        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    // --- Import ---

    @Test
    fun `an import writes all rows in ONE call, as new rows - whatever ids the entries carried`() = runTest {
        val written = slot<List<VehicleLogEntity>>()
        coEvery { dao.insertAll(capture(written)) } just Runs

        repo.importEntries(listOf(fuel.copy(id = 99), tires.copy(id = 98)))

        coVerify(exactly = 1) { dao.insertAll(any()) }
        assertEquals(listOf("FUEL", "TIRE_CHANGE"), written.captured.map { it.type })
        assertEquals("neue Zeilen, die Datenbank vergibt die Ids", listOf(0L, 0L), written.captured.map { it.id })
    }

    @Test
    fun `an import of nothing writes nothing`() = runTest {
        repo.importEntries(emptyList())

        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `a failing import is not swallowed - the caller must see that nothing was imported`() = runTest {
        coEvery { dao.insertAll(any()) } throws IllegalStateException("disk full")

        val failure = runCatching { repo.importEntries(listOf(fuel)) }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
    }

    // --- Stand ---

    @Test
    fun `the latest odometer entry is the one with the highest reading`() = runTest {
        coEvery { dao.latestByOdometer() } returns row("OIL", id = 4, km = 40_362)
        coEvery { dao.getAll() } returns listOf(row("FUEL", id = 1), row("OIL", id = 2))

        assertEquals(40_362, repo.latestByOdometer()!!.odometerKm)
        assertEquals("fuer den Export: ohne unbekannte Arten, aelteste zuerst wie geliefert", listOf(1L, 2L), repo.all().map { it.id })
    }

    @Test
    fun `without entries there is no latest reading`() = runTest {
        coEvery { dao.latestByOdometer() } returns null

        assertNull(repo.latestByOdometer())
    }

    @Test
    fun `the highest odometer reading follows the entries, null without any, repeated values once`() = runTest {
        every { dao.observeAll() } returns kotlinx.coroutines.flow.flowOf(
            emptyList(),
            listOf(row("FUEL", id = 1, km = 40_000)),
            listOf(row("FUEL", id = 1, km = 40_000), row("OIL", id = 2, km = 39_000)),
            listOf(row("FUEL", id = 1, km = 40_000), row("OIL", id = 2, km = 41_500)),
            listOf(row("FUEL", id = 1, km = 40_000), row("OIL", id = 2, km = 41_500), row("OIL", id = 3, km = 41_400)),
        )

        assertEquals(listOf(null, 40_000, 41_500), repo.maxOdometerKm.toList())
    }

    @Test
    fun `a row of an unknown kind does not count for the highest reading`() = runTest {
        every { dao.observeAll() } returns kotlinx.coroutines.flow.flowOf(listOf(row("FUEL", id = 1, km = 40_000), row("FROM_THE_FUTURE", id = 2, km = 99_000)))

        assertEquals(listOf(40_000), repo.maxOdometerKm.toList())
    }
}
