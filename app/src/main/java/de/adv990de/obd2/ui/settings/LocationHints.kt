package de.adv990de.obd2.ui.settings

/**
 * Zeigen die Einstellungen unter dem GPS-Schalter den Hinweis "Standortberechtigung fehlt"?
 *
 * Zwei Faelle, beide ohne Hinweis bis 1.24.4:
 *  - Der Schalter ist AN, die Berechtigung aber weg (in den Systemeinstellungen entzogen, "Nur dieses Mal"
 *    abgelaufen): GPS liefert dann still nichts (B10).
 *  - Der Nutzer hat den Schalter betaetigt, und der System-Dialog wurde ohne genauen Standort beantwortet
 *    ("Ungefaehr", "Ablehnen"): Der Schalter bleibt aus, ohne zu sagen warum ([lastRequestDenied], B21).
 */
internal fun gpsSpeedPermissionHint(useGpsSpeed: Boolean, fineGranted: Boolean, lastRequestDenied: Boolean): Boolean =
    !fineGranted && (useGpsSpeed || lastRequestDenied)

/**
 * Zeigen die Einstellungen unter "Navigationsleiste beim Fahren ausblenden" den Hinweis, dass die
 * Aussentemperatur ohne Standort fehlt? Die Berechtigung wird nur EINMAL pro Installation angefragt
 * (`weatherLocationPrompted`); wer dort "Nicht erlauben" waehlte, sah bis 1.24.4 nie, warum die Temperatur
 * fehlt (B21). Fine ODER coarse genuegt fuers Wetter.
 */
internal fun weatherPermissionHint(hideNavOnDrive: Boolean, fineGranted: Boolean, coarseGranted: Boolean): Boolean =
    hideNavOnDrive && !fineGranted && !coarseGranted
