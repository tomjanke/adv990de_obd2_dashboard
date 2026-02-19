# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }

# Kotlin serialization
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
