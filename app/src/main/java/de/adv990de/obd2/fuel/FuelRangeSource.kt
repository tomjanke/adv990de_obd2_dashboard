package de.adv990de.obd2.fuel

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.logbook.fuelReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Woher die Reichweite der Tankanzeige kommt: von Hand eingegeben oder aus dem Tankinhalt und dem Verbrauch des Logbuchs berechnet ([litersPer100Km]). */
sealed interface RangeOrigin {
    data object Manual : RangeOrigin
    data class Learned(val litersPer100Km: Double) : RangeOrigin
}

/** Die Reichweite bei vollem Tank, mit der die Tankanzeige rechnet ([km], 0 = keine) und ihre Herkunft. */
data class EffectiveRange(val km: Int, val origin: RangeOrigin)

/** Die aus dem Logbuch berechnete Reichweite ([km]) und der Verbrauch, aus dem sie stammt. */
data class LearnedRange(val km: Int, val litersPer100Km: Double)

/**
 * Die wirksame Reichweite der Tankanzeige (Entwurf `docs/superpowers/specs/2026-10-25-range-design.md`, D4/D5/D6): die manuelle Zahl ([AppPreferences.fuelRangeFullKm]) — oder, wenn ein
 * Tankinhalt hinterlegt ist **und** das Logbuch mindestens drei Voll-zu-Voll-Intervalle hat, **Tankinhalt / Verbrauch** ([learnedRangeKm], Verbrauch der letzten zehn Intervalle). Ohne
 * Schalter: wer die manuelle Zahl will, loescht den Tankinhalt.
 *
 * **Das Logbuch wird nur gelesen, solange ein Tankinhalt hinterlegt ist** (`flatMapLatest` auf den Tankinhalt): wer ihn nicht nutzt, bezahlt nichts — kein Abonnement der Tabelle, keine
 * Rechnung. Jede Aenderung eines Eintrags, des Tankinhalts oder der manuellen Zahl wirkt auf den naechsten Wert, ohne Neustart; ein gleich gebliebenes Ergebnis wird nicht noch einmal
 * gemeldet (eine Notiz an einem Oeleintrag aendert nichts).
 *
 * Ohne Scope und ohne `@Singleton`: eine Rechnung ueber zwei Quellen, die [FuelRepository] haelt (und dort den Cache fuer den Start schreibt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelRangeSource @Inject constructor(
    private val appPreferences: AppPreferences,
    private val logbook: VehicleLogRepository,
) {

    /** Die gelernte Reichweite allein (`null` = keine: kein Tankinhalt, zu wenige Intervalle oder ein unplausibles Ergebnis) — auch Quelle des Caches. */
    val learned: Flow<LearnedRange?> = appPreferences.fuelTankCapacityDl
        .flatMapLatest { capacityDl ->
            if (capacityDl <= 0) {
                flowOf(null)
            } else {
                logbook.entries
                    .map { entries -> recentLitersPer100Km(fuelReport(entries)) }
                    .distinctUntilChanged()
                    .map { consumption -> learnedRangeKm(capacityDl, consumption)?.let { LearnedRange(it, consumption!!) } }
            }
        }
        .distinctUntilChanged()

    /** Die Reichweite, mit der die Tankanzeige rechnet, und woher sie kommt. */
    val range: Flow<EffectiveRange> = combine(appPreferences.fuelRangeFullKm, learned) { manualKm, learned ->
        val km = effectiveRangeKm(manualKm, learned?.km)
        EffectiveRange(km, if (learned != null) RangeOrigin.Learned(learned.litersPer100Km) else RangeOrigin.Manual)
    }.distinctUntilChanged()

    /**
     * Der Startwert fuer das erste Bild, **synchron gelesen** (wie der Zaehler der Tankanzeige, ~3 ms): mit Tankinhalt der Cache der zuletzt berechneten Reichweite, sonst oder bei leerem
     * Cache die manuelle Zahl. Ohne Tankinhalt wird ein stehengebliebener Cache ignoriert — er zeigte sonst nach dem Loeschen des Tankinhalts bis zum ersten Wert die alte gelernte Zahl.
     * Nur ein Startwert: die Quelle der Wahrheit ist [range].
     */
    fun seedRangeKm(): Int {
        val manualKm = seedBlocking(0, source = appPreferences.fuelRangeFullKm)
        val capacityDl = seedBlocking(0, source = appPreferences.fuelTankCapacityDl)
        if (capacityDl <= 0) return manualKm
        val cachedKm = seedBlocking(0, source = appPreferences.fuelRangeLearnedKm)
        return if (cachedKm > 0) cachedKm else manualKm
    }
}
