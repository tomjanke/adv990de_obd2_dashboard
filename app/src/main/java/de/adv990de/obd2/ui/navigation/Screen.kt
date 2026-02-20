package de.adv990de.obd2.ui.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Dtc : Screen("dtc")
    object FreezeFrame : Screen("freeze_frame")
    object VehicleInfo : Screen("vehicle_info")
    object History : Screen("history")
    object Settings : Screen("settings")
    object DashboardConfig : Screen("dashboard_config")
}
