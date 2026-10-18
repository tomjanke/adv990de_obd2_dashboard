package de.adv990de.obd2.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.terrain.GpsFix
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die einzige Stelle der App, die fuer die laufende Geschwindigkeit den [LocationManager] kennt.
 * (Seit 2026-09-27 gibt es eine zweite, getrennte: [AndroidCurrentLocationProvider] liest dort
 * nur den letzten bekannten Standort fuer die Wetterabfrage.)
 *
 * Sie trifft bewusst keine Entscheidung ueber Geschwindigkeiten: kein Umrechnen, kein Filtern, kein Zustand — die
 * Logik liegt in [GpsSpeedSource]. Seit 2026-10-18 meldet sie ueber [startFixUpdates] zusaetzlich ganze Fixe fuer die
 * Gelaende-Kachel (`terrain/`): ein eigener Listener, die Zuordnung der Felder steht in [toGpsFix] (mit JVM-Test),
 * die Auswertung in `TerrainTracker`.
 *
 * Bewusst ueber den Framework-Manager statt ueber die Play-Services: Das Projekt hat keine
 * Google-Play-Abhaengigkeit, und fuer eine reine Geschwindigkeit reicht der GPS-Provider.
 */
@Singleton
class AndroidLocationSpeedProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSpeedProvider {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? = register(
        minIntervalMs = MIN_INTERVAL_MS,
        onLocation = { location -> onSpeed(if (location.hasSpeed()) location.speed else null) },
        onDisabled = { onSpeed(null) },
    )

    override fun startFixUpdates(minIntervalMs: Long, onFix: (GpsFix?) -> Unit): (() -> Unit)? = register(
        minIntervalMs = minIntervalMs,
        onLocation = { location -> onFix(location.toGpsFix()) },
        onDisabled = { onFix(null) },
    )

    /** Die eine Stelle, die einen [LocationListener] am GPS-Anbieter anmeldet; [startUpdates] und [startFixUpdates] unterscheiden sich nur in dem, was sie aus dem Fix machen. */
    private fun register(
        minIntervalMs: Long,
        onLocation: (Location) -> Unit,
        onDisabled: () -> Unit,
    ): (() -> Unit)? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        // Kein SAM-Lambda: vor API 30 sind die uebrigen Methoden des Interface abstrakt, ein
        // Lambda wuerde auf solchen Geraeten mit AbstractMethodError abstuerzen.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            @Deprecated("Vor API 30 abstrakt und deshalb pflichtig zu implementieren.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = onDisabled()
        }

        return try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minIntervalMs,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            ({ manager.removeUpdates(listener) })
        } catch (_: SecurityException) {
            // Berechtigung zwischen Pruefung und Registrierung entzogen.
            null
        }
    }

    internal companion object {
        /** 200 ms: bewusst schneller als der ~500ms-OBD-Zyklus, damit die GPS-Geschwindigkeit
         *  nicht spuerbar hinter der Tachoanzeige herlaeuft. Kostet etwas mehr Akku, greift aber
         *  nur, waehrend GPS-Speed in den Einstellungen eingeschaltet ist. */
        const val MIN_INTERVAL_MS = 200L
        const val MIN_DISTANCE_M = 0f
    }
}

/**
 * Ein [Location] als [GpsFix] fuer die Gelaende-Kachel: jedes Feld nur, wenn der Fix es traegt (`hasX()`), sonst null — nie 0. Die Hoehe kommt ab Android 14
 * ([sdkInt] 34) ueber dem Meeresniveau (`getMslAltitudeMeters`), davor und ohne MSL-Angabe ueber dem Ellipsoid (in Deutschland mehrere Dutzend Meter daneben).
 * Die Vertikalgenauigkeit gilt fuer beide Angaben (die MSL-Genauigkeit unterscheidet sich nur um die Unsicherheit des Geoidmodells). Die Zeit ist die
 * monotone des Fixes. [sdkInt] ist ein Parameter, damit beide Zweige im JVM-Test laufen (wie bei `LocationPermissions`).
 */
@SuppressLint("NewApi") // der MSL-Zweig steht hinter dem Vergleich mit sdkInt (Standard: Build.VERSION.SDK_INT); Lint sieht ihn durch den Parameter nicht
internal fun Location.toGpsFix(sdkInt: Int = Build.VERSION.SDK_INT): GpsFix = GpsFix(
    elapsedMs = elapsedRealtimeNanos / 1_000_000L,
    speedKmh = if (hasSpeed()) speed * 3.6f else null,
    altitudeM = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasMslAltitude() -> mslAltitudeMeters.toFloat()
        hasAltitude() -> altitude.toFloat()
        else -> null
    },
    verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    bearingDeg = if (hasBearing()) bearing else null,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
)
