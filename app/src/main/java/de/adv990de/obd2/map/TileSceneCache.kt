package de.adv990de.obd2.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Die zuletzt gebrauchten Kacheln in einer Form, die der Zeichner direkt nutzt ([T], zum Beispiel eine Szene mit fertigen Pfaden). [request] bestellt Kacheln: jede fehlende wird im Hintergrund
 * mit [load] geladen (hoechstens [parallel] zugleich, jede nur einmal), danach ruft [onLoaded] auf — der Zeichner muss dann neu zeichnen. [peek] liest, was schon da ist, ohne etwas zu bestellen;
 * es ist fuer das Zeichnen gedacht und blockiert nie. Eine Kachel, die die Datei nicht hat (`load` liefert `null`), wird als „fehlt“ gemerkt und nicht erneut gelesen ([isSettled]).
 * Ueber [capacity] Eintraege fliegt der am laengsten nicht gebrauchte hinaus; was [peek] oder [request] zuletzt anfasste, bleibt.
 */
class TileSceneCache<T : Any>(
    private val capacity: Int,
    private val scope: CoroutineScope,
    private val parallel: Int = 2,
    private val onLoaded: () -> Unit,
    private val load: suspend (TileKey) -> T?,
) {
    private class Entry<T>(val value: T?)

    private val lock = Any()
    private val entries = object : LinkedHashMap<TileKey, Entry<T>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, Entry<T>>) = size > capacity
    }
    private val inFlight = HashSet<TileKey>()
    private val permits = Semaphore(parallel)

    /** Die geladene Kachel oder `null` (noch nicht geladen oder nicht in der Datei). Haelt sie im Speicher. */
    fun peek(key: TileKey): T? = synchronized(lock) { entries[key]?.value }

    /** `true`, wenn die Antwort feststeht: geladen oder als fehlend erkannt. */
    fun isSettled(key: TileKey): Boolean = synchronized(lock) { entries.containsKey(key) }

    /** Bestellt die [keys], die weder da noch unterwegs sind (in der gegebenen Reihenfolge). */
    fun request(keys: Collection<TileKey>) {
        val todo = synchronized(lock) {
            keys.filter { entries[it] == null && it !in inFlight }.distinct().also { inFlight.addAll(it) }
        }
        for (key in todo) {
            scope.launch {
                val value = try {
                    permits.withPermit { load(key) }
                } catch (e: CancellationException) {
                    throw e // der Bereich endet, mit ihm die Ablage
                } catch (_: Exception) {
                    null // eine Kachel, die sich nicht laden laesst, bleibt eine leere Stelle
                }
                synchronized(lock) {
                    inFlight.remove(key)
                    entries[key] = Entry(value)
                }
                onLoaded()
            }
        }
    }
}
