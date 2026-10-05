package de.adv990de.obd2.ui.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Dtc : Screen("dtc")
    object FreezeFrame : Screen("freeze_frame")
    object VehicleInfo : Screen("vehicle_info")
    object History : Screen("history")
    object Settings : Screen("settings")
    object LoggingConfig : Screen("logging_config")
    object AppInfo : Screen("app_info")
    object AppLogs : Screen("app_logs")
    object GearSettings : Screen("gear_settings")
    object Licenses : Screen("licenses")
}
