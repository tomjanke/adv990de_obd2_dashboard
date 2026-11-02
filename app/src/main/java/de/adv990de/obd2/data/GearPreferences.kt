package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Die Getriebe-Einstellungen der virtuellen Ganganzeige im DataStore: Radumfang, Primaer- und Sekundaeruebersetzung und die Gesamtuebersetzung je Gang. Herausgeloest aus [AppPreferences]
 * (wie [AlarmPreferences] und [MaintenancePreferences]); `AppPreferences` reicht Fluesse und Setter unveraendert nach aussen durch, [data] ist ihr Lese-Fluss (E/A-Fehler = Standardwerte).
 * Die Standardwerte stehen weiter in `AppPreferences` (`DEFAULT_WHEEL_CIRCUMFERENCE_MM`, `DEFAULT_PRIMARY_RATIO`, `DEFAULT_SECONDARY_RATIO`, `DEFAULT_GEAR_RATIOS`), dort suchen sie Oberflaeche und Tests.
 *
 * Die Schluessel sind persistierter Zustand (DataStore) — nie umbenennen oder mit anderer Bedeutung wiederverwenden, sonst faellt die Einstellung bei jedem Nutzer still auf den Standard zurueck
 * (`GearPreferencesTest` haelt sie fest).
 */
internal class GearPreferences(
    private val dataStore: DataStore<Preferences>,
    data: Flow<Preferences>,
) {
    val wheelCircumferenceMm: Flow<Float> = data.map { prefs ->
        prefs[KEY_WHEEL_CIRCUMFERENCE_MM] ?: AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM
    }.distinctUntilChanged() // DataStore emits on every write; suppress value-equal emissions

    suspend fun setWheelCircumferenceMm(value: Float) {
        dataStore.edit { it[KEY_WHEEL_CIRCUMFERENCE_MM] = value }
    }

    val primaryRatio: Flow<Float> = data.map { prefs ->
        prefs[KEY_PRIMARY_RATIO] ?: AppPreferences.DEFAULT_PRIMARY_RATIO
    }.distinctUntilChanged()

    suspend fun setPrimaryRatio(value: Float) {
        dataStore.edit { it[KEY_PRIMARY_RATIO] = value }
    }

    val secondaryRatio: Flow<Float> = data.map { prefs ->
        prefs[KEY_SECONDARY_RATIO] ?: AppPreferences.DEFAULT_SECONDARY_RATIO
    }.distinctUntilChanged()

    suspend fun setSecondaryRatio(value: Float) {
        dataStore.edit { it[KEY_SECONDARY_RATIO] = value }
    }

    /** Gesamt-Getriebeuebersetzung je Gang (Index 0 = 1. Gang ... Index 5 = 6. Gang). Sechs
     *  einzelne Keys statt einer CSV-kodierten Liste, damit jedes Settings-Eingabefeld
     *  unabhaengig von den anderen fuenf geschrieben werden kann. */
    val gearRatios: Flow<List<Float>> = data.map { prefs ->
        GEAR_RATIO_KEYS.mapIndexed { i, key -> prefs[key] ?: AppPreferences.DEFAULT_GEAR_RATIOS[i] }
    }.distinctUntilChanged()

    suspend fun setGearRatio(index: Int, value: Float) {
        require(index in 0..5) { "index must be 0..5" }
        dataStore.edit { it[GEAR_RATIO_KEYS[index]] = value }
    }

    private companion object {
        val KEY_WHEEL_CIRCUMFERENCE_MM = floatPreferencesKey("wheel_circumference_mm")
        val KEY_PRIMARY_RATIO = floatPreferencesKey("primary_ratio")
        val KEY_SECONDARY_RATIO = floatPreferencesKey("secondary_ratio")
        val GEAR_RATIO_KEYS = listOf(
            floatPreferencesKey("gear_ratio_1"), floatPreferencesKey("gear_ratio_2"), floatPreferencesKey("gear_ratio_3"),
            floatPreferencesKey("gear_ratio_4"), floatPreferencesKey("gear_ratio_5"), floatPreferencesKey("gear_ratio_6"),
        )
    }
}
