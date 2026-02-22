package de.adv990de.obd2

import android.os.Bundle
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val displayVm: DisplaySettingsViewModel = hiltViewModel()
            val theme by displayVm.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (theme) {
                AppPreferences.THEME_DARK  -> true
                AppPreferences.THEME_LIGHT -> false
                else                       -> systemDark
            }
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                PermissionGate {
                    AppNavHost(rememberNavController())
                }
            }
        }
    }
}
