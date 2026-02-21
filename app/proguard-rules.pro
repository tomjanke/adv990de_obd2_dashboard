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

# OBD2 — sealed exception hierarchy (catch-clauses survive shrinking)
-keep class de.adv990de.obd2.obd.model.ObdException { *; }
-keep class de.adv990de.obd2.obd.model.ObdException$* { *; }

# UiText sealed class (pattern-matched in DashboardUiState)
-keep class de.adv990de.obd2.ui.UiText { *; }
-keep class de.adv990de.obd2.ui.UiText$* { *; }

# Kotlin data classes — copy(), componentN() used via reflection in tests/coroutines
-keepclassmembers class de.adv990de.obd2.** {
    public synthetic void copy$default(...);
}

# Kotlin coroutines
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Jetpack Navigation — route strings
-keepnames class de.adv990de.obd2.ui.navigation.Screen { *; }

# Paging 3 — PagingSource subclasses referenced via Room reflection
-keep class * extends androidx.paging.PagingSource { *; }
