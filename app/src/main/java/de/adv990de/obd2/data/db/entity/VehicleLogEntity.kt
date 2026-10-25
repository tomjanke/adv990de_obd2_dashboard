package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Eine Zeile des Logbuchs (Datenbank v6). Alles in ganzen Einheiten, nie in Fliesskomma: [amountMl] (Liter × 1000), [costCents] (Euro × 100), das Datum als
 * [epochDay] (`LocalDate.toEpochDay()`, zeitzonenunabhaengig). [type] ist der **Name** des `LogType` — persistierter Zustand, nie umbenennen.
 * [fullTank], [amountMl], [costCents], [note] und [brand] sind nur bei den Arten belegt, die sie tragen (sonst `NULL`).
 */
@Entity(tableName = "vehicle_log")
data class VehicleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val epochDay: Long,
    val odometerKm: Int,
    val amountMl: Int?,
    val fullTank: Boolean?,
    val costCents: Int?,
    val note: String?,
    val brand: String?,
)
