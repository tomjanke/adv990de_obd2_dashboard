package de.adv990de.obd2.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Wie lange auf einen frisch angeforderten Fix gewartet wird, wenn kein gecachter Standort
 *  vorliegt — pro Provider (GPS und Netzwerk bekommen je ihre eigene Frist). */
private const val ACTIVE_FIX_TIMEOUT_MS = 15_000L

/**
 * Die einzige Stelle der App, die fuer die Wetterabfrage den [LocationManager] kennt. Trifft
 * bewusst keine Entscheidung (kein Filtern, kein Umrechnen) — reiner Framework-Zugriff, analog
 * [AndroidLocationSpeedProvider].
 *
 * Fuer Wetter genuegt der ungefaehre Standort (Open-Meteo rechnet ohnehin auf ein Raster von rund
 * einem Kilometer, s. `OpenMeteoApi`), deshalb reicht FINE ODER COARSE — anders als die
 * GPS-Geschwindigkeit, die einen genauen Fix braucht.
 */
@Singleton
class AndroidCurrentLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CurrentLocationProvider {

    override fun hasPermission(): Boolean =
        LocationPermissions.hasFineLocation(context) || LocationPermissions.hasCoarseLocation(context)

    /**
     * Zuerst der JUENGSTE gecachte Fix (GPS oder Netzwerk), sofern er hoechstens [CACHED_FIX_MAX_AGE_MS] alt ist
     * (schnell, kein Warten) — erst wenn kein frischer da ist, wird je Provider (GPS dann Netzwerk) EIN frischer Fix aktiv
     * angefragt (`requestSingleUpdate`, Timeout [ACTIVE_FIX_TIMEOUT_MS]). Scheitert auch das, ist das Ergebnis `null`:
     * lieber keine Temperatur als eine fuer die falsche Gegend (SEC-5; vorher zaehlte jeder gecachte Fix, egal wie alt, und
     * der GPS-Cache schlug einen frischen Netzwerk-Fix).
     *
     * Ohne den aktiven Zweig lieferte diese Funktion auf einem Geraet ohne kuerzlich von
     * IRGENDEINER App anfragten Standort (z. B. "Geschwindigkeit per GPS" aus, die einzige andere
     * Stelle im Projekt mit einer aktiven Standortanfrage) dauerhaft `null` — die Aussentemperatur
     * waere fuer solche Nutzer nie erschienen, obwohl Berechtigung und API-Abruf funktionieren
     * (Feldbericht 2026-09-28, nach Release v1.17.0).
     *
     * Jeder Provider in eigenem try: auf API 26-30 wirft der GPS-Provider bei nur COARSE-
     * Berechtigung eine [SecurityException] — die darf den Netzwerk-Versuch nicht verhindern,
     * weder beim Cache- noch beim aktiven Zweig.
     */
    override suspend fun lastKnownLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val cached = freshestFix(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).map { provider ->
                cachedFrom(manager, provider)?.let {
                    LocationFix(it.latitude, it.longitude, fixAgeMs(nowNanos, it.elapsedRealtimeNanos))
                }
            }
        )
        if (cached != null) return cached.latitude to cached.longitude
        val active = activeFixFrom(manager, LocationManager.GPS_PROVIDER)
            ?: activeFixFrom(manager, LocationManager.NETWORK_PROVIDER)
        return active?.let { it.latitude to it.longitude }
    }

    private fun cachedFrom(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            // Berechtigung zwischen Pruefung und Aufruf entzogen, oder (API 26-30) nur COARSE
            // erteilt und dieser Provider verlangt FINE.
            null
        }

    /**
     * Meldet sich beim [provider] fuer genau ein Update an und wartet bis zu
     * [ACTIVE_FIX_TIMEOUT_MS] darauf. `removeUpdates()` bei Abbruch (Timeout oder Coroutine-
     * Cancellation) verhindert, dass der Listener registriert bleibt, nachdem niemand mehr auf
     * ihn wartet — `requestSingleUpdate` meldet sich zwar nach dem ERSTEN Update selbst ab, aber
     * nicht, wenn gar keins mehr kommt.
     */
    private suspend fun activeFixFrom(manager: LocationManager, provider: String): Location? =
        withTimeoutOrNull(ACTIVE_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }
                    @Suppress("DEPRECATION")
                    @Deprecated("Deprecated in Java", ReplaceWith(""))
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    @Suppress("DEPRECATION") // kein Ersatz mit minSdk 26; requestLocationUpdates mit
                    // numUpdates=1 waere die einzige Alternative und meldet sich nicht selbst ab.
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: SecurityException) {
                    continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    // Geraet hat diesen Provider gar nicht registriert (z.B. kein NETWORK_PROVIDER
                    // ohne Google Play Services) — requestSingleUpdate wirft dann sofort statt nur
                    // nie zu antworten. Ohne diesen catch liefe die Exception ungefangen bis in
                    // WeatherRepository.fetchCurrentTemperatureC()s "wirft nie"-Vertrag und von dort
                    // in WeatherViewModels ungeschuetztes Poll-Flow (Backlog-Sweep 2026-09-28).
                    continuation.resume(null)
                }
            }
        }
}
