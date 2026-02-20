package de.adv990de.obd2.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import de.adv990de.obd2.ui.navigation.Screen

private val navItems = listOf(
    Triple(Screen.Dashboard, Icons.Default.Home, "Live"),
    Triple(Screen.Dtc, Icons.Default.Info, "DTCs"),
    Triple(Screen.History, Icons.Default.List, "Verlauf"),
    Triple(Screen.Settings, Icons.Default.Settings, "Einstellungen"),
)

@Composable
fun BottomNav(navController: NavController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    NavigationBar {
        navItems.forEach { (screen, icon, label) ->
            NavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = { navController.navigate(screen.route) { launchSingleTop = true } },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}
