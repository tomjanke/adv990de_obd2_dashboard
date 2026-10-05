package de.adv990de.obd2.location

/**
 * Wie alt ein GECACHTER Standort hoechstens sein darf, damit er fuer die Wetterabfrage zaehlt. Zehn Minuten sind bei
 * Autobahntempo rund 20 km — weniger als die Aufloesung der Wetterdaten (Raster von mehreren Kilometern) bei dieser
 * Geschwindigkeit hergeben wuerde; laenger gilt ein Standort als "irgendwo in der Naehe", nicht als "hier".
 */
internal const val CACHED_FIX_MAX_AGE_MS: Long = 10 * 60 * 1000L

/** Ein Standort-Fix samt seinem Alter zum Zeitpunkt der Auswahl. */
internal data class LocationFix(val latitude: Double, val longitude: Double, val ageMs: Long)

/** Alter eines Fixes in Millisekunden, gemessen an der MONOTONEN Uhr (`elapsedRealtimeNanos`), nicht an der Wanduhr. */
internal fun fixAgeMs(nowElapsedNanos: Long, fixElapsedNanos: Long): Long = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L

/**
 * Der juengste Fix, der noch zaehlt (Alter von 0 bis [maxAgeMs], beides eingeschlossen), sonst `null`.
 *
 * Vorher nahm die App den gecachten GPS-Fix, wenn es einen gab — egal wie alt, und auch vor einem frischen
 * Netzwerk-Fix. Der Cache wird nur aktualisiert, wenn IRGENDEINE App einen Standort anfordert; bei ausgeschalteter
 * "Geschwindigkeit per GPS" friert er ein, und die Aussentemperatur galt fuer den Ort von vor Stunden. Ein Fix aus der
 * Zukunft (negatives Alter: die monotone Uhr hat nach einem Neustart neu begonnen) ist ungueltig.
 */
internal fun freshestFix(fixes: List<LocationFix?>, maxAgeMs: Long = CACHED_FIX_MAX_AGE_MS): LocationFix? =
    fixes.filterNotNull().filter { it.ageMs in 0..maxAgeMs }.minByOrNull { it.ageMs }
