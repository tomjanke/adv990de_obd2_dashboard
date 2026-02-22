package de.adv990de.obd2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import de.adv990de.obd2.ui.dashboard.DashboardScreen
import de.adv990de.obd2.ui.dashboardconfig.DashboardConfigScreen
import de.adv990de.obd2.ui.dtc.DtcScreen
import de.adv990de.obd2.ui.freezeframe.FreezeFrameScreen
import de.adv990de.obd2.ui.history.HistoryScreen
import de.adv990de.obd2.ui.loggingconfig.LoggingConfigScreen
import de.adv990de.obd2.ui.settings.SettingsScreen
import de.adv990de.obd2.ui.vehicleinfo.VehicleInfoScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
        composable(Screen.Dashboard.route) { DashboardScreen(navController) }
        composable(Screen.Dtc.route) { DtcScreen(navController) }
        composable(Screen.FreezeFrame.route) { FreezeFrameScreen(navController) }
        composable(Screen.VehicleInfo.route) { VehicleInfoScreen(navController) }
        composable(Screen.History.route) { HistoryScreen(navController) }
        composable(Screen.Settings.route) { SettingsScreen(navController) }
        composable(Screen.DashboardConfig.route) { DashboardConfigScreen(navController) }
        composable(Screen.LoggingConfig.route) { LoggingConfigScreen(navController) }
    }
}
