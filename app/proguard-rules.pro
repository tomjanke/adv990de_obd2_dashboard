# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
# Keep entity data classes (referenced by name in Room-generated SQL and cursors)
-keep class de.adv990de.obd2.data.db.entity.** { *; }
# Keep DAO interfaces (Room generates implementations that reference them by name)
-keep interface de.adv990de.obd2.data.db.** { *; }

# Kotlin serialization
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
