package de.adv990de.obd2

import android.os.Bundle
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
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val displayVm: DisplaySettingsViewModel = hiltViewModel()
            val theme by displayVm.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = resolveDarkTheme(theme, systemDark)
            MaterialTheme(
                colorScheme = resolveColorScheme(theme, darkTheme),
                typography = AppTypography
            ) {
                PermissionGate {
                    AppNavHost(rememberNavController())
                }
            }
        }
    }
}

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
