package de.adv990de.obd2.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import de.adv990de.obd2.ui.components.BottomNav
import de.adv990de.obd2.ui.dashboard.DashboardScreen
import de.adv990de.obd2.ui.dashboardconfig.DashboardConfigScreen
import de.adv990de.obd2.ui.dtc.DtcScreen
import de.adv990de.obd2.ui.freezeframe.FreezeFrameScreen
import de.adv990de.obd2.ui.history.HistoryScreen
import de.adv990de.obd2.ui.loggingconfig.LoggingConfigScreen
import de.adv990de.obd2.ui.settings.SettingsScreen
import de.adv990de.obd2.ui.appinfo.AppInfoScreen
import de.adv990de.obd2.ui.vehicleinfo.VehicleInfoScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    // Single Scaffold + BottomNav shared across all screens.
    // contentWindowInsets = WindowInsets(0) prevents the outer Scaffold from adding
    // system insets to innerPadding; each screen's own Scaffold handles those via
    // its default contentWindowInsets. Only the BottomNav height (which includes the
    // system bottom inset via NavigationBarDefaults.windowInsets) is applied here.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { BottomNav(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(navController) }
            composable(Screen.Dtc.route) { DtcScreen(navController) }
            composable(Screen.FreezeFrame.route) { FreezeFrameScreen(navController) }
            composable(Screen.VehicleInfo.route) { VehicleInfoScreen(navController) }
            composable(Screen.History.route) { HistoryScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
            composable(Screen.DashboardConfig.route) { DashboardConfigScreen(navController) }
            composable(Screen.LoggingConfig.route) { LoggingConfigScreen(navController) }
            composable(Screen.AppInfo.route) { AppInfoScreen(navController) }
        }
    }
}
