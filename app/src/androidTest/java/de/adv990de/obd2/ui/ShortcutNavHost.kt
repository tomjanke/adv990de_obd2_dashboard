package de.adv990de.obd2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.transfer.TRANSFER_MODE_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PART_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PICK_ARG
import org.junit.Assert.assertEquals

/** Test tag of the stand-in page that [ShortcutNavHost] shows for the data transfer route. */
internal const val SHORTCUT_TARGET_TEST_TAG = "shortcut_target"

/**
 * A tiny nav host for the tests of the export and import shortcuts in the lists: the list under test is the start destination, and the route of the data transfer page
 * (declared with the same arguments and defaults as in `AppNavHost`) shows only a marker. The real page is not composed here — its own tests cover it; what a shortcut
 * promises is the **route and its arguments**, and those are read from the back stack entry ([assertTransferTarget]). The test builds the [navController] with
 * `rememberNavController()` itself and keeps it. [extraDestinations] adds further placeholder pages (e.g. the entry page of the logbook).
 */
@Composable
internal fun ShortcutNavHost(
    navController: NavHostController,
    extraDestinations: NavGraphBuilder.() -> Unit = {},
    start: @Composable (NavHostController) -> Unit,
) {
    NavHost(navController, startDestination = "shortcut_start") {
        composable("shortcut_start") { start(navController) }
        composable(
            Screen.DataTransfer.route,
            arguments = listOf(
                navArgument(TRANSFER_MODE_ARG) { type = NavType.StringType; defaultValue = "export" },
                navArgument(TRANSFER_PART_ARG) { type = NavType.StringType; defaultValue = "" },
                navArgument(TRANSFER_PICK_ARG) { type = NavType.BoolType; defaultValue = false },
            ),
        ) { Box { Text("transfer page", Modifier.testTag(SHORTCUT_TARGET_TEST_TAG)) } }
        extraDestinations()
    }
}

/** What the back stack entry on top says: the data transfer destination with exactly these arguments (call on the UI thread). */
internal fun assertTransferTarget(navController: NavHostController, mode: String, part: String, pick: Boolean) {
    val entry = checkNotNull(navController.currentBackStackEntry) { "no destination" }
    assertEquals(Screen.DataTransfer.route, entry.destination.route)
    assertEquals(mode, entry.arguments?.getString(TRANSFER_MODE_ARG))
    assertEquals(part, entry.arguments?.getString(TRANSFER_PART_ARG))
    assertEquals(pick, entry.arguments?.getBoolean(TRANSFER_PICK_ARG))
}
