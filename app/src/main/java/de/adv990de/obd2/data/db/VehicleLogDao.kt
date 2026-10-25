package de.adv990de.obd2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.adv990de.obd2.data.db.entity.VehicleLogEntity
import kotlinx.coroutines.flow.Flow

/** Das Logbuch (`vehicle_log`). Die Tabelle wird von der Aufbewahrung (`DataRetention`) nie angefasst. */
@Dao
interface VehicleLogDao {

    /** Neueste zuerst: Datum, dann Stand, dann Anlage — bei gleichem Tag und Stand springt die Liste nie. */
    @Query("SELECT * FROM vehicle_log ORDER BY epochDay DESC, odometerKm DESC, id DESC")
    fun observeAll(): Flow<List<VehicleLogEntity>>

    /** Aelteste zuerst — die Reihenfolge des CSV-Exports. */
    @Query("SELECT * FROM vehicle_log ORDER BY epochDay ASC, odometerKm ASC, id ASC")
    suspend fun getAll(): List<VehicleLogEntity>

    /** Neu (Id 0: die Datenbank vergibt sie) oder, bei vorhandener Id, die Zeile ersetzen — ein Aufruf fuer "speichern". */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VehicleLogEntity): Long

    /** Alle Zeilen oder keine: Room fuehrt eine Liste in **einer** Transaktion aus; ein Fehler in der Mitte laesst die Tabelle unveraendert. */
    @Insert
    suspend fun insertAll(entities: List<VehicleLogEntity>)

    @Query("SELECT * FROM vehicle_log WHERE id = :id")
    suspend fun getById(id: Long): VehicleLogEntity?

    /** Hoechstens 50 Ids je Aufruf (das Repository teilt): SQLite erlaubt auf API 26 nur 999 gebundene Variablen. */
    @Query("DELETE FROM vehicle_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Die Zeile mit dem hoechsten Kilometerstand (bei Gleichstand die juengste) — der "letzte Stand" im Formular. */
    @Query("SELECT * FROM vehicle_log ORDER BY odometerKm DESC, epochDay DESC, id DESC LIMIT 1")
    suspend fun latestByOdometer(): VehicleLogEntity?
}
