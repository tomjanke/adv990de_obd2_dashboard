package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LogLevel { INFO, WARN, ERROR }

@Entity(tableName = "app_log")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,   // LogLevel.name — Room stores as TEXT
    val message: String
)
