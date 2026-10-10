package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import de.adv990de.obd2.R

/** Ein Text des Vorschau-Bands: die String-Ressource und ihre Format-Argumente (ohne Compose, direkt JVM-testbar). */
internal data class BannerLabel(@StringRes val res: Int, val args: List<Any> = emptyList())

/**
 * Der Text des Vorschau-Bands ohne Verbindung: schlicht "Vorschau · nicht verbunden" — oder, solange eine Wiederverbindung
 * laeuft, mit dem Versuch ("Vorschau · Verbindungsversuch 2/5"). Bis 1.24.11 las keine Oberflaeche
 * `DashboardUiState.reconnectAttempt`: das Band blieb waehrend aller fuenf Versuche (bis ~93 s) bei "nicht verbunden".
 *
 * [attempt] ist 1-basiert; 0 heisst "Auto-Connect beim Start" (`ConnectionEvent.Attempting(0, …)`) und hat noch keine Nummer.
 * Die Nummer gilt nur bei laufender Wiederverbindung ([isReconnecting]) — eine liegengebliebene faellt nicht auf.
 *
 * Der Versuch wird gemeldet, BEVOR seine Wartezeit beginnt (`ConnectionCoordinator`: erst `Attempting`, dann `delay`, dann
 * der Verbindungsaufbau): "Verbindungsversuch 1/5" steht also auch waehrend der ersten Wartezeit — wie die Zeile im App-Log.
 */
internal fun previewBannerLabel(isReconnecting: Boolean, attempt: Int, total: Int): BannerLabel = when {
    !isReconnecting -> BannerLabel(R.string.dashboard_preview_banner)
    attempt >= 1 -> BannerLabel(R.string.dashboard_preview_banner_attempt, listOf(attempt, total))
    else -> BannerLabel(R.string.dashboard_preview_banner_connecting)
}
