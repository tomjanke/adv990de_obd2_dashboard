package de.adv990de.obd2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.navigation.Screen

private val navItems: List<Triple<Screen, ImageVector, Int>> = listOf(
    Triple(Screen.Dashboard, Icons.Default.Home,     R.string.nav_live),
    Triple(Screen.Dtc,       Icons.Default.Info,     R.string.nav_dtcs),
    Triple(Screen.History,   Icons.Default.List,     R.string.nav_history),
    Triple(Screen.Settings,  Icons.Default.Settings, R.string.settings_title),
)

@Composable
fun BottomNav(
    navController: NavController,
    vm: DriveStateViewModel = hiltViewModel()
) {
    val isNavHidden by vm.isNavHidden.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    AnimatedVisibility(
        visible = !isNavHidden,
        enter = slideInVertically { it },
        exit  = slideOutVertically { it }
    ) {
        Column {
            HorizontalDivider()
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().height(64.dp),
                windowInsets = WindowInsets(0)
            ) {
                navItems.forEach { (screen, icon, labelRes) ->
                    val label = stringResource(labelRes)
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    }
}
