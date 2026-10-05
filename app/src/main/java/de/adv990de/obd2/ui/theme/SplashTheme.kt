package de.adv990de.obd2.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.StyleRes
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences

/** `Resources.ID_NULL`: kein eigenes Splash-Theme — es gilt das Theme aus dem Manifest. */
internal const val SPLASH_THEME_NONE = 0

/** `SplashScreen.setSplashScreenTheme` und `Activity.getSplashScreen` gibt es ab Android 12 (API 31). */
internal fun canSetSplashTheme(sdkInt: Int): Boolean = sdkInt >= 31

/**
 * Das Splash-Theme zum gewaehlten Farbschema [theme] (ein [AppPreferences]-Wert). Jedes Theme malt nur die
 * Hintergrundflaeche des Splash in die Hintergrundfarbe des Schemas (s. `res/values/splash_colors.xml`); das Icon bleibt
 * das der App. "Wie das System" und alles Unbekannte ergeben [SPLASH_THEME_NONE]: Das Manifest-Theme folgt dem
 * Systemmodus selbst (Ressourcen mit `-night`).
 *
 * Die Namen der Themes liegen beim System, wenn man sie setzt — sie duerfen nie umbenannt werden (`SplashThemeTest`).
 */
@StyleRes
internal fun splashThemeRes(theme: String): Int = when (theme) {
    AppPreferences.THEME_LIGHT -> R.style.Theme_OBD2Monitor_Splash_Light
    AppPreferences.THEME_DARK -> R.style.Theme_OBD2Monitor_Splash_Dark
    AppPreferences.THEME_BLUE -> R.style.Theme_OBD2Monitor_Splash_Blue
    AppPreferences.THEME_AMBER -> R.style.Theme_OBD2Monitor_Splash_Amber
    AppPreferences.THEME_GREEN -> R.style.Theme_OBD2Monitor_Splash_Green
    else -> SPLASH_THEME_NONE
}

/**
 * Merkt dem System das Splash-Theme fuer den NAECHSTEN Start vor (es wird von ihm persistiert und gilt fuer die ganze
 * App). Der Splash entsteht, bevor der App-Code laeuft; die Einstellung ist also erst beim uebernaechsten Blick
 * sichtbar. Vor Android 12 gibt es die API nicht — dort bleibt es beim Theme-Hintergrund.
 */
internal fun Activity.rememberSplashThemeFor(theme: String) {
    if (!canSetSplashTheme(Build.VERSION.SDK_INT)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setSplashScreenTheme(splashThemeRes(theme))
}
