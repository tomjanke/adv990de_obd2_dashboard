package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import de.adv990de.obd2.fuel.FuelCapacityLimits
import de.adv990de.obd2.fuel.FuelRangeLimits
import de.adv990de.obd2.fuel.FuelReserveLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Die Einstellungen der Tankanzeige im DataStore (virtuell, Streckenzaehler — s. `de.adv990de.obd2.fuel`): Reichweite, gezaehlte Kilometer, Reserve, Tankinhalt und der Cache der gelernten Reichweite.
 * Herausgeloest aus [AppPreferences] (wie [AlarmPreferences] und [MaintenancePreferences]); `AppPreferences` reicht Fluesse und Setter unveraendert nach aussen durch, [data] ist ihr Lese-Fluss
 * (E/A-Fehler = Standardwerte).
 *
 * Die Schluessel sind persistierter Zustand (DataStore) — nie umbenennen oder mit anderer Bedeutung wiederverwenden, sonst faellt die Einstellung bei jedem Nutzer still auf den Standard zurueck
 * (`AppPreferencesTest` haelt sie fest). Ein gespeicherter Wert ausserhalb der Grenzen (nur von Hand moeglich) liest als nicht festgelegt.
 */
internal class FuelPreferences(
    private val dataStore: DataStore<Preferences>,
    data: Flow<Preferences>,
) {
    /**
     * Reichweite bei vollem Tank in ganzen km; **0 = nicht festgelegt** (die Tankanzeige bleibt ausgeblendet). Ein Wert ausserhalb von
     * [FuelRangeLimits] im Speicher (kann nur von Hand entstehen) gilt als nicht festgelegt.
     */
    val rangeFullKm: Flow<Int> = data.map { prefs ->
        (prefs[KEY_RANGE_FULL_KM] ?: 0).takeIf { it in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM } ?: 0
    }.distinctUntilChanged()

    /** [km] = 0 loescht die Reichweite; sonst muss der Wert in [FuelRangeLimits] liegen (die Eingabe prueft das schon, das hier ist das Netz). */
    suspend fun setRangeFullKm(km: Int) {
        require(km == 0 || km in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM) { "fuel range out of bounds: $km" }
        dataStore.edit { it[KEY_RANGE_FULL_KM] = km }
    }

    /** Seit dem letzten Zuruecksetzen gefahrene Kilometer (GPS). Ein negativer oder nicht endlicher Wert im Speicher liest als 0. */
    val kmSinceFill: Flow<Float> = data.map { prefs ->
        (prefs[KEY_KM_SINCE_FILL] ?: 0f).takeIf { it.isFinite() && it >= 0f } ?: 0f
    }.distinctUntilChanged()

    suspend fun setKmSinceFill(km: Float) {
        require(km.isFinite() && km >= 0f) { "fuel counter must be finite and >= 0: $km" }
        dataStore.edit { it[KEY_KM_SINCE_FILL] = km }
    }

    /**
     * Reserve der Tankanzeige in ganzen km Restreichweite; **0 = aus** (keine Marke im Balken, die Zahl wird nie rot). Ein Wert ausserhalb von [FuelReserveLimits] im Speicher
     * (kann nur von Hand entstehen) gilt als aus. Eine Reserve ab der Reichweite wird gespeichert, hat aber keine Marke (`reserveFraction`).
     */
    val reserveKm: Flow<Int> = data.map { prefs ->
        (prefs[KEY_RESERVE_KM] ?: 0).takeIf { it in FuelReserveLimits.MIN_KM..FuelReserveLimits.MAX_KM } ?: 0
    }.distinctUntilChanged()

    /** [km] = 0 schaltet die Reserve aus; sonst muss der Wert in [FuelReserveLimits] liegen (die Eingabe prueft das schon, das hier ist das Netz). */
    suspend fun setReserveKm(km: Int) {
        require(km == 0 || km in FuelReserveLimits.MIN_KM..FuelReserveLimits.MAX_KM) { "fuel reserve out of bounds: $km" }
        dataStore.edit { it[KEY_RESERVE_KM] = km }
    }

    /**
     * Tankinhalt in Zehntelliter (200 = 20,0 l); **0 = nicht festgelegt**. Mit Tankinhalt und mindestens drei Voll-zu-Voll-Intervallen im Logbuch berechnet die Tankanzeige ihre Reichweite
     * selbst (sonst gilt [rangeFullKm]). Ein Wert ausserhalb von [FuelCapacityLimits] im Speicher (kann nur von Hand entstehen) gilt als nicht festgelegt.
     */
    val tankCapacityDl: Flow<Int> = data.map { prefs ->
        (prefs[KEY_TANK_CAPACITY_DL] ?: 0).takeIf { it in FuelCapacityLimits.MIN_DL..FuelCapacityLimits.MAX_DL } ?: 0
    }.distinctUntilChanged()

    /** [dl] = 0 loescht den Tankinhalt; sonst muss der Wert in [FuelCapacityLimits] liegen (die Eingabe prueft das schon, das hier ist das Netz). */
    suspend fun setTankCapacityDl(dl: Int) {
        require(dl == 0 || dl in FuelCapacityLimits.MIN_DL..FuelCapacityLimits.MAX_DL) { "fuel tank capacity out of bounds: $dl" }
        dataStore.edit { it[KEY_TANK_CAPACITY_DL] = dl }
    }

    /**
     * Die zuletzt aus dem Logbuch berechnete Reichweite in km, **nur ein Cache fuer das erste Bild** (die Tankzeile soll beim Start nicht springen, bis die Logbuch-Abfrage antwortet); die
     * Quelle der Wahrheit ist die Rechnung (`FuelRangeSource`), diese Zahl ist nie ein Eingang. 0 = keiner. Ein Wert ausserhalb von [FuelRangeLimits] im Speicher gilt als keiner.
     */
    val rangeLearnedKm: Flow<Int> = data.map { prefs ->
        (prefs[KEY_RANGE_LEARNED_KM] ?: 0).takeIf { it in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM } ?: 0
    }.distinctUntilChanged()

    /** [km] = 0 loescht den Cache; sonst muss der Wert in [FuelRangeLimits] liegen. */
    suspend fun setRangeLearnedKm(km: Int) {
        require(km == 0 || km in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM) { "fuel learned range out of bounds: $km" }
        dataStore.edit { it[KEY_RANGE_LEARNED_KM] = km }
    }

    private companion object {
        val KEY_RANGE_FULL_KM = intPreferencesKey("fuel_range_full_km")
        val KEY_KM_SINCE_FILL = floatPreferencesKey("fuel_km_since_fill")
        val KEY_RESERVE_KM = intPreferencesKey("fuel_reserve_km")
        val KEY_TANK_CAPACITY_DL = intPreferencesKey("fuel_tank_capacity_dl")
        val KEY_RANGE_LEARNED_KM = intPreferencesKey("fuel_range_learned_km")
    }
}
