package de.adv990de.obd2

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.ui.navigation.AppNavHost
import de.adv990de.obd2.ui.permission.PermissionGate
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var serviceController: ObdServiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val displayVm: DisplaySettingsViewModel = hiltViewModel()
            val theme by displayVm.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = resolveDarkTheme(theme, systemDark)
            AppTheme(
                colorScheme = resolveColorScheme(theme, darkTheme),
                typography = AppTypography
            ) {
                PermissionGate {
                    AppNavHost(rememberNavController())
                }
            }
        }
    }

    // Volle Helligkeit nur waehrend die App tatsaechlich im Vordergrund ist (User-Wunsch
    // 2026-09-28, fuer Sonnenlicht-Lesbarkeit auf dem Motorrad) — kein WRITE_SETTINGS noetig,
    // da ein reines Fenster-Attribut statt einer System-Einstellung. onPause() setzt zurueck,
    // sonst bliebe die Helligkeit beim Wechsel in eine andere App faelschlich auf Maximum.
    override fun onResume() {
        super.onResume()
        window.attributes = window.attributes.apply { screenBrightness = brightnessOverrideFor(resumed = true) }
    }

    override fun onPause() {
        window.attributes = window.attributes.apply { screenBrightness = brightnessOverrideFor(resumed = false) }
        super.onPause()
    }

    // Polling und Aufzeichnung leben im ViewModel dieser Activity (B1). Wird sie wirklich beendet — Wegwischen, "Aktivitaeten
    // nicht behalten", Zurueck am Root auf API <= 30 — stirbt das ViewModel und mit ihm die Aufzeichnung; Verbindung und die
    // Benachrichtigung "Fahrzeugdaten werden aufgezeichnet" blieben ohne diesen Aufruf stehen. Eine Neuerzeugung
    // (Drehung, Themenwechsel) behaelt das ViewModel und darf nichts beenden.
    override fun onDestroy() {
        if (::serviceController.isInitialized && shouldEndSessionOnDestroy(isChangingConfigurations)) {
            serviceController.endSessionInBackground()
        }
        super.onDestroy()
    }
}

/**
 * Whether destroying [MainActivity] ends the OBD session: exactly when its ViewModelStore is cleared, i.e. when the
 * activity is not merely being recreated for a configuration change (ComponentActivity clears it under the same
 * condition). Pure so the rule is JVM-testable; the framework call itself is verified on the device.
 */
internal fun shouldEndSessionOnDestroy(isChangingConfigurations: Boolean): Boolean = !isChangingConfigurations

/**
 * Maps a persisted [AppPreferences] theme string to whether the app should render in dark mode.
 * [AppPreferences.THEME_SYSTEM] and any unrecognized value fall back to [systemDark].
 */
internal fun resolveDarkTheme(theme: String, systemDark: Boolean): Boolean = when (theme) {
    AppPreferences.THEME_DARK,
    AppPreferences.THEME_BLUE,
    AppPreferences.THEME_AMBER,
    AppPreferences.THEME_GREEN -> true
    AppPreferences.THEME_LIGHT -> false
    else                       -> systemDark
}

/**
 * Maps a persisted [AppPreferences] theme string to the [ColorScheme] used by [MaterialTheme].
 * The named color themes ([AppPreferences.THEME_BLUE]/[AppPreferences.THEME_AMBER]/
 * [AppPreferences.THEME_GREEN]) resolve to their fixed cockpit schemes; everything else
 * (including [AppPreferences.THEME_SYSTEM] and [AppPreferences.THEME_LIGHT]) falls back to
 * Material 3's default [darkColorScheme] or [lightColorScheme] depending on [darkTheme].
 */
internal fun resolveColorScheme(theme: String, darkTheme: Boolean): ColorScheme = when (theme) {
    AppPreferences.THEME_BLUE  -> blueColorScheme
    AppPreferences.THEME_AMBER -> amberColorScheme
    AppPreferences.THEME_GREEN -> greenColorScheme
    else                       -> if (darkTheme) darkColorScheme() else lightColorScheme()
}

/**
 * The [android.view.Window.attributes] `screenBrightness` override for [MainActivity]'s
 * onResume/onPause: maximum while the app is actually in the foreground, otherwise
 * [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] (defer to the system setting).
 */
internal fun brightnessOverrideFor(resumed: Boolean): Float =
    if (resumed) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
