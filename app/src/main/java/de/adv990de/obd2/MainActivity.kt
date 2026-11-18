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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.ui.navigation.AppNavHost
import de.adv990de.obd2.ui.permission.PermissionGate
import de.adv990de.obd2.ui.theme.AmbientLightSensor
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.BrightnessLevel
import de.adv990de.obd2.ui.theme.BrightnessReading
import de.adv990de.obd2.ui.theme.ambientBrightness
import de.adv990de.obd2.ui.theme.brightnessLogMessage
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import de.adv990de.obd2.ui.theme.rememberSplashThemeFor
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var serviceController: ObdServiceController

    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var ambientLight: AmbientLightSensor

    @Inject lateinit var appLog: AppLogRepository

    /** Die zuletzt gewaehlte Helligkeitsstufe; `onResume` setzt sie sofort, damit die App nicht erst voll hell aufblitzt, bis der Sensor meldet. */
    private var brightnessLevel = BrightnessLevel.DAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Das gewaehlte Farbschema VOR der ersten Komposition lesen (~3 ms, am Pixel gemessen): Die Einstellung wird sonst
        // erst waehrend der ersten Komposition abonniert, das erste Bild zeigte kurz das Standard-Theme, dann sprang es auf
        // Blau/Amber/Gruen (UID-12). Der gelesene Wert ist nur der Startwert; `collectAsStateWithLifecycle` folgt danach jeder Aenderung.
        val initialTheme = seedBlocking(AppPreferences.THEME_SYSTEM, source = appPreferences.theme)
        setContent {
            val theme by appPreferences.theme.collectAsStateWithLifecycle(initialValue = initialTheme)
            // Der System-Splash entsteht vor jedem App-Code und kennt nur Ressourcen: das Splash-Theme zum gewaehlten
            // Farbschema merkt sich das System fuer den NAECHSTEN Start (ui/theme/SplashTheme.kt).
            LaunchedEffect(theme) { rememberSplashThemeFor(theme) }
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
        // Der Sensor ist nur angemeldet, solange die App im Vordergrund steht und die Einstellung an ist (ambientBrightness).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ambientBrightness(appPreferences.dimInDark, ambientLight::luxUpdates).collect { onBrightnessReading(it) }
            }
        }
    }

    // Volle Helligkeit nur waehrend die App tatsaechlich im Vordergrund ist (User-Wunsch
    // 2026-09-28, fuer Sonnenlicht-Lesbarkeit auf dem Motorrad) — kein WRITE_SETTINGS noetig,
    // da ein reines Fenster-Attribut statt einer System-Einstellung. onPause() setzt zurueck,
    // sonst bliebe die Helligkeit beim Wechsel in eine andere App faelschlich auf Maximum.
    // Seit 2026-11-18 senkt der Lichtsensor sie in Daemmerung und Dunkelheit ab (ui/theme/AmbientBrightness.kt),
    // solange die Einstellung "Bei Dunkelheit dimmen" an ist.
    override fun onResume() {
        super.onResume()
        applyBrightness(brightnessLevel)
        // Eine Berechtigung (Standort) kann sich ausserhalb der App geaendert haben; steht der Dienst schon, bekommt er
        // den dazu passenden Typ (ObdServiceController.refreshServiceType). Ohne laufenden Dienst oder bei gleichem Typ tut das nichts.
        serviceController.refreshServiceTypeInBackground()
    }

    override fun onPause() {
        window.attributes = window.attributes.apply { screenBrightness = brightnessOverrideFor(resumed = false) }
        super.onPause()
    }

    private fun applyBrightness(level: BrightnessLevel) {
        window.attributes = window.attributes.apply { screenBrightness = brightnessOverrideFor(resumed = true, level = level) }
    }

    private suspend fun onBrightnessReading(reading: BrightnessReading) {
        val previous = brightnessLevel
        brightnessLevel = reading.level
        applyBrightness(reading.level)
        // ein Wechsel der Stufe steht im App-Log (mit dem gemessenen Lux-Wert): daran stellt man die Schwellen nach einer Fahrt nach
        if (reading.level != previous) appLog.log(LogLevel.INFO, brightnessLogMessage(previous, reading))
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
 * onResume/onPause: the brightness of [level] (full by default, lower at dusk and at night) while the app is actually
 * in the foreground, otherwise [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] (defer to the system setting).
 */
internal fun brightnessOverrideFor(resumed: Boolean, level: BrightnessLevel = BrightnessLevel.DAY): Float =
    if (resumed) level.windowBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
