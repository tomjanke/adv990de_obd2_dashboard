package de.adv990de.obd2.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Liest den ersten Wert von [source] SYNCHRON — gedacht fuer den Start, um Startwerte zu setzen (QS C / UID-12).
 *
 * Theme und Kachel-Belegung kommen asynchron aus dem DataStore, und die Einstellung wird erst waehrend der ersten
 * Komposition abonniert: Das erste Bild zeigte deshalb kurz das Standard-Theme und leere Kacheln, dann sprang es um. Am
 * Pixel gemessen dauert der erste Zugriff beim Kaltstart rund 3 ms (ein zweiter ~2 ms) — etwa ein Bild lang. Der blockierende
 * Lesevorgang kostet auf dem Main-Thread dieselbe Zeit einmal und vermeidet den Sprung.
 *
 * Das Ergebnis ist ein STARTWERT, nicht die Quelle der Wahrheit: Der Aufrufer abonniert [source] danach wie bisher und
 * bekommt jede Aenderung. Kommt innerhalb von [timeoutMs] kein Wert (haengende Platte), ist die Flow leer oder wirft sie, gilt
 * [default] — ein Fehler hier darf den Start nie verhindern.
 */
fun <T : Any> seedBlocking(default: T, timeoutMs: Long = 250, source: Flow<T>): T =
    try {
        runBlocking { withTimeoutOrNull(timeoutMs) { source.first() } } ?: default
    } catch (_: Exception) {
        default
    }
