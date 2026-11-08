package de.adv990de.obd2.logbook

/** So weit muss der GPS-Zaehler seit dem letzten Schreiben gewachsen sein, bevor er wieder geschrieben wird (~20 s bei 90 km/h); ein Prozess-Kill verliert hoechstens so viel. */
const val ODOMETER_PERSIST_STEP_KM = 0.5f

/**
 * Der GPS-Zaehler fuer den geschaetzten Kilometerstand als **reiner Zustand** (die Huelle mit Speicher und Datenbank ist `LogbookOdometer`). Zwei Dinge muessen bekannt sein, bevor er etwas
 * entscheiden kann: der **gespeicherte** Zaehler ([withStored]) und der **hoechste Stand im Logbuch** ([withLoggedMax]); erst dann ist er [ready] und [counter] hat einen Wert. Kilometer davor
 * ([withDistance]) werden gesammelt und beim Bereitwerden aufaddiert — sie gehen nie verloren, ein noch nicht gelesener Speicherwert wird nie ueberschrieben.
 *
 * Der Zaehler gehoert zu **einem** Stand: aendert sich der hoechste Stand im Logbuch (neuer, geloeschter Eintrag), beginnt er bei 0 neu; ohne jeden Eintrag gibt es keinen Zaehler und Kilometer
 * zaehlen nichts.
 */
internal data class OdometerCounter(
    private val storedKnown: Boolean = false,
    private val stored: GpsOdometer? = null,
    private val maxKnown: Boolean = false,
    private val loggedMax: Int? = null,
    private val pendingKm: Float = 0f,
    val counter: GpsOdometer? = null,
    /** Ein wiederhergestellter Zaehler, dessen Stand das Logbuch noch nicht gemeldet hat (s. [withRestored]). */
    private val restoredPending: GpsOdometer? = null,
) {
    val ready: Boolean get() = storedKnown && maxKnown

    /** Der gespeicherte Zaehler wurde gelesen (`null` = keiner gespeichert). Nur der erste Aufruf zaehlt. */
    fun withStored(value: GpsOdometer?): OdometerCounter =
        if (storedKnown) this else copy(storedKnown = true, stored = value).settledIfReady()

    /** Der hoechste Kilometerstand im Logbuch ist [max] (`null` ohne Eintrag). */
    fun withLoggedMax(max: Int?): OdometerCounter {
        if (!maxKnown) return copy(maxKnown = true, loggedMax = max).settledIfReady()
        val pending = restoredPending
        return when {
            max == null -> copy(loggedMax = null, counter = null, restoredPending = null)
            pending != null && pending.anchorKm == max -> copy(loggedMax = max, counter = pending, restoredPending = null)
            counter?.anchorKm == max -> copy(loggedMax = max, restoredPending = null)
            else -> copy(loggedMax = max, counter = GpsOdometer(max, 0f), restoredPending = null)
        }
    }

    /**
     * Ein aus einem Paket wiederhergestellter Zaehler. Gehoert er zum jetzigen hoechsten Stand, gilt er sofort. Sonst wird er **gemerkt**: nach dem Import der Logbuch-Eintraege meldet
     * [VehicleLogRepository.maxOdometerKm] den neuen hoechsten Stand asynchron, und ohne den Merker finge der Zaehler dann bei 0 neu an (ein Rennen, das nur manchmal auftraete). Trifft ein
     * Stand ein, der dem gemerkten entspricht, gilt dieser; jeder andere Stand verwirft den Merker. Ein unbrauchbarer Zaehler (negativ, nicht endlich) aendert nichts.
     */
    fun withRestored(value: GpsOdometer): OdometerCounter = when {
        !value.gpsKm.isFinite() || value.gpsKm < 0f -> this
        ready && loggedMax == value.anchorKm -> copy(counter = value, restoredPending = null)
        else -> copy(restoredPending = value)
    }

    /** [km] gefahrene Kilometer; unbrauchbare Werte (0, negativ, nicht endlich) zaehlen nichts. */
    fun withDistance(km: Float): OdometerCounter {
        if (!km.isFinite() || km <= 0f) return this
        return if (ready) copy(counter = counter?.let { it.copy(gpsKm = it.gpsKm + km) }) else copy(pendingKm = pendingKm + km)
    }

    /**
     * Im Augenblick des Bereitwerdens (der zweite der beiden Anfangswerte trifft ein): der gespeicherte Zaehler gilt, wenn er zum hoechsten Stand gehoert, sonst beginnt ein neuer bei 0; die
     * gesammelten Kilometer kommen dazu. Ohne Eintrag gibt es keinen Zaehler.
     */
    private fun settledIfReady(): OdometerCounter {
        if (!ready) return this
        val max = loggedMax
        val base = restoredPending?.takeIf { it.anchorKm == max } ?: stored?.takeIf { it.anchorKm == max }
        val km = (base?.gpsKm ?: 0f) + pendingKm
        return copy(counter = max?.let { GpsOdometer(it, km) }, pendingKm = 0f, restoredPending = null)
    }
}

/**
 * Ob [counter] jetzt geschrieben werden soll, wenn zuletzt [persisted] geschrieben (oder gelesen) wurde: noch nie, ein neuer Stand als Anker, ein zurueckgesetzter Zaehler oder ein Zuwachs um
 * [ODOMETER_PERSIST_STEP_KM]. Kleine Aenderungen warten auf den naechsten Schritt oder das Ende der Sitzung.
 */
internal fun shouldPersistOdometer(counter: GpsOdometer, persisted: GpsOdometer?): Boolean =
    persisted == null || counter.anchorKm != persisted.anchorKm || counter.gpsKm < persisted.gpsKm || counter.gpsKm - persisted.gpsKm >= ODOMETER_PERSIST_STEP_KM
