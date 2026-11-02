package de.adv990de.obd2.fuel

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wohin der Streckenzaehler der Tankanzeige seine Kilometer meldet — die Naht zwischen dem `DashboardViewModel` (misst) und dem
 * [FuelRepository] (haelt und speichert). Eine Schnittstelle statt der Klasse, damit das ViewModel ohne Datenbank und DataStore
 * gebaut werden kann ([None] fuer Tests, Hilt bindet das [FuelRepository], s. `FuelModule`).
 */
interface FuelDistanceSink {
    /** Zaehlt [km] (> 0) zur seit dem Tanken gefahrenen Strecke. */
    fun addDistance(km: Float)

    /** Schreibt den Zaehler jetzt in den Speicher, falls er sich seit dem letzten Schreiben geaendert hat (Sitzungsende). */
    fun flush()

    companion object {
        val None: FuelDistanceSink = object : FuelDistanceSink {
            override fun addDistance(km: Float) = Unit
            override fun flush() = Unit
        }

        /** Ein Sink, der jede Strecke und jedes [flush] an alle [sinks] weitergibt, in dieser Reihenfolge (die Tankanzeige und der Kilometerstand des Logbuchs zaehlen dieselben Kilometer). */
        fun fanOut(vararg sinks: FuelDistanceSink): FuelDistanceSink = object : FuelDistanceSink {
            override fun addDistance(km: Float) = sinks.forEach { it.addDistance(km) }
            override fun flush() = sinks.forEach { it.flush() }
        }
    }
}

/**
 * Haelt den Zaehler der virtuellen Tankanzeige (gefahrene Kilometer seit dem letzten Zuruecksetzen) und macht daraus zusammen mit der
 * Reichweite bei vollem Tank den [state], den die Tankzeile im Dashboard zeigt (Nutzerwunsch 2026-10-15).
 *
 * - **Ein Zaehler fuer die ganze App:** das `DashboardViewModel` schreibt ([addDistance]), das `FuelGaugeViewModel` liest — zwei ViewModels, die
 *   denselben Zustand brauchen, der einen Neustart ueberleben muss; deshalb ein Repository mit `@Singleton`.
 * - **Gespeichert wird gedrosselt:** der Zaehler waechst mehrmals je Sekunde, ein DataStore-Schreibvorgang je Meldung waere Verschwendung. Es wird
 *   geschrieben, sobald er um [PERSIST_STEP_KM] gewachsen ist, beim Zuruecksetzen sofort und per [flush] am Sitzungsende. Ein Prozess-Kill
 *   verliert so hoechstens [PERSIST_STEP_KM]. Alle Schreibvorgaenge laufen ueber **einen** Schreiber mit einem Kanal, der nur den
 *   neuesten Wert behaelt — so ueberholt nie ein aelterer Wert einen neueren.
 * - **Ein Wert, der noch nicht gelesen ist, wird nie ueberschrieben:** der Startwert wird synchron gelesen ([seedBlocking], ~3 ms); schlaegt das
 *   fehl (haengende Platte), bleibt der Zaehler `nicht geladen`, bis der Speicher antwortet, Kilometer davor werden aufaddiert, und solange
 *   ist [state] `null` (Leiste ausgeblendet) und nichts wird geschrieben. Ein Zuruecksetzen des Nutzers gewinnt gegen ein spaetes Laden.
 * - [state] ist `null`, wenn keine Reichweite hinterlegt ist (die Tankzeile bleibt dann ausgeblendet) oder der Zaehler noch nicht geladen ist.
 * - **Die Reichweite kommt aus der [FuelRangeSource]** (von Hand oder aus dem Logbuch berechnet, seit F5); die berechnete wird als Cache fuer das erste Bild gespeichert
 *   (`fuel_range_learned_km`, nur bei Aenderung; ein Schreibfehler ist best effort).
 */
@Singleton
class FuelRepository @Inject constructor(
    private val appPreferences: AppPreferences,
    private val rangeSource: FuelRangeSource,
    @ApplicationScope private val scope: CoroutineScope,
) : FuelDistanceSink {

    /** [loaded] = der gespeicherte Wert ist eingerechnet; [km] = Zaehler (bis dahin nur die seitdem gezaehlten Kilometer). */
    private data class Odometer(val loaded: Boolean, val km: Float)

    private val odometer = MutableStateFlow(seedOdometer())

    /** Der zuletzt zum Schreiben uebergebene (bzw. gelesene) Wert — nur fuer die Drosselung und [flush]. */
    @Volatile
    private var persistedKm: Float = odometer.value.km

    private val writeQueue = Channel<Float>(Channel.CONFLATED)

    /**
     * Die Reichweite, mit der die Tankanzeige rechnet — **einmal** abonniert und geteilt: der Zustand und der Cache fuer das erste Bild lesen dieselbe Quelle. Zwei Abonnements von
     * [FuelRangeSource.range] und [FuelRangeSource.learned] fragten die Logbuch-Tabelle und die Verbrauchsrechnung bei jeder Aenderung doppelt ab.
     */
    private val range: SharedFlow<EffectiveRange> = rangeSource.range.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** Reichweite bei voller Tankfuellung zusammen mit dem Zaehler; `null` = Tankzeile ausblenden. */
    val state: StateFlow<FuelGaugeState?> = combine(range, appPreferences.fuelReserveKm, odometer) { range, reserve, odo ->
        gaugeOf(range.km, reserve, odo)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        gaugeOf(
            rangeSource.seedRangeKm(),
            seedBlocking(0, source = appPreferences.fuelReserveKm),
            odometer.value,
        ),
    )

    /** Die zuletzt als Cache fuer das erste Bild gespeicherte gelernte Reichweite (0 = keine) — nur fuer die Entscheidung, ob geschrieben werden muss. */
    @Volatile
    private var cachedLearnedKm: Int = seedBlocking(0, source = appPreferences.fuelRangeLearnedKm)

    init {
        scope.launch {
            for (km in writeQueue) {
                try {
                    appPreferences.setFuelKmSinceFill(km)
                } catch (_: IOException) {
                    // Best effort: der Zaehler bleibt im Speicher, der naechste Schritt oder flush() versucht es erneut.
                }
            }
        }
        scope.launch {
            // Der Cache fuer das erste Bild: die zuletzt berechnete Reichweite, nur bei Aenderung geschrieben. Er ist kein Eingang der Rechnung (sonst wuerde das Schreiben eine zweite Rechnung ausloesen).
            // Gelernt ist die Reichweite genau dann, wenn ihre Herkunft das Logbuch ist; dann ist `km` die gelernte Zahl (`effectiveRangeKm`), sonst gibt es keine (0).
            range.map { if (it.origin is RangeOrigin.Learned) it.km else 0 }.distinctUntilChanged().collect { km ->
                if (km != cachedLearnedKm) {
                    try {
                        appPreferences.setFuelRangeLearnedKm(km)
                        cachedLearnedKm = km
                    } catch (_: IOException) {
                        // Best effort: ein verpasster Cache kostet nur einen Sprung beim naechsten Start.
                    }
                }
            }
        }
        if (!odometer.value.loaded) {
            scope.launch {
                val stored = appPreferences.fuelKmSinceFill.first()
                odometer.update { if (it.loaded) it else Odometer(loaded = true, km = stored + it.km) }
                persistedKm = stored
            }
        }
    }

    override fun addDistance(km: Float) {
        if (!km.isFinite() || km <= 0f) return
        val after = odometer.updateAndGet { it.copy(km = it.km + km) }
        if (after.loaded && after.km - persistedKm >= PERSIST_STEP_KM) persist(after.km)
    }

    override fun flush() {
        val current = odometer.value
        if (current.loaded && current.km != persistedKm) persist(current.km)
    }

    /** Setzt die Anzeige auf "voll" zurueck (nach dem Tanken) und schreibt sofort. Gewinnt gegen ein noch ausstehendes Laden. */
    fun reset() {
        odometer.value = Odometer(loaded = true, km = 0f)
        persist(0f)
    }

    /**
     * Hebt die Anzeige nach einer **Teilbetankung** an: zieht [km] vom Zaehler ab (nie unter 0 — voller als voll gibt es nicht) und schreibt sofort, wie [reset]. Eine unbrauchbare Strecke
     * (<= 0, NaN, unendlich) aendert nichts. Ein **noch nicht gelesener** Zaehler bleibt unberuehrt: von dem, was der Speicher noch liefert, wuesste die Rechnung nichts (die Rueckfrage im
     * Logbuch gibt es nur bei vorhandener Tankanzeige, also bei geladenem Zaehler).
     */
    fun credit(km: Float) {
        if (!km.isFinite() || km <= 0f) return
        val after = odometer.updateAndGet { if (it.loaded) it.copy(km = (it.km - km).coerceAtLeast(0f)) else it }
        if (after.loaded) persist(after.km)
    }

    private fun persist(km: Float) {
        persistedKm = km
        writeQueue.trySend(km)
    }

    private fun seedOdometer(): Odometer {
        val seeded = seedBlocking(NOT_LOADED, source = appPreferences.fuelKmSinceFill)
        return if (seeded >= 0f) Odometer(loaded = true, km = seeded) else Odometer(loaded = false, km = 0f)
    }

    private fun gaugeOf(range: Int, reserve: Int, odo: Odometer): FuelGaugeState? =
        if (range > 0 && odo.loaded) FuelGaugeState(range, odo.km, reserve) else null

    internal companion object {
        /** So weit muss der Zaehler seit dem letzten Schreiben gewachsen sein, bevor er wieder geschrieben wird (~9 s bei 80 km/h). */
        const val PERSIST_STEP_KM = 0.2f

        /** Kennzeichnet "Startwert nicht lesbar" — der gespeicherte Zaehler ist nie negativ ([AppPreferences.fuelKmSinceFill]). */
        private const val NOT_LOADED = -1f
    }
}
