package de.adv990de.obd2

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
        // Keep screen on for the entire app session (all screens, not just while connected).
        // Android clears this automatically when the app goes to the background.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val displayVm: DisplaySettingsViewModel = hiltViewModel()
            val theme by displayVm.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (theme) {
                AppPreferences.THEME_DARK,
                AppPreferences.THEME_BLUE,
                AppPreferences.THEME_AMBER,
                AppPreferences.THEME_GREEN -> true
                AppPreferences.THEME_LIGHT -> false
                else                       -> systemDark
            }
            MaterialTheme(
                colorScheme = when (theme) {
                    AppPreferences.THEME_BLUE  -> blueColorScheme
                    AppPreferences.THEME_AMBER -> amberColorScheme
                    AppPreferences.THEME_GREEN -> greenColorScheme
                    else                       -> if (darkTheme) darkColorScheme() else lightColorScheme()
                },
                typography = AppTypography
            ) {
                PermissionGate {
                    AppNavHost(rememberNavController())
                }
            }
        }
    }
}
