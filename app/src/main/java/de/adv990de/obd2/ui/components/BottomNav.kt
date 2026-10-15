package de.adv990de.obd2.ui.components

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.ExitAppViewModel
import de.adv990de.obd2.ui.navigation.BottomNavAction
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.navigation.bottomNavAction
import de.adv990de.obd2.ui.navigation.parentTab
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.weather.WeatherViewModel
import kotlinx.coroutines.launch

private val navItems: List<Triple<Screen, ImageVector, Int>> = listOf(
    Triple(Screen.Dashboard, Icons.Default.Home,     R.string.nav_live),
    Triple(Screen.Dtc,       Icons.Default.Info,     R.string.nav_dtcs),
    Triple(Screen.History,   Icons.AutoMirrored.Filled.List, R.string.nav_history),
    Triple(Screen.Settings,  Icons.Default.Settings, R.string.settings_title),
)

/**
 * Zeigt entweder die `NavigationBar` oder [AmbientStatusRow] — abhaengig von
 * [DriveStateViewModel.isNavHidden] (Fahrzeug bewegt sich UND `hideNavOnDrive`-Einstellung an).
 * Sitzt in der einen app-weiten `Scaffold` (s. `AppNavHost.kt`), erscheint also auf jedem Screen,
 * nicht nur dem Dashboard — User-bestaetigte Design-Entscheidung.
 *
 * `weatherVm` ist wie `vm` ein Parameter mit `hiltViewModel()`-Default statt eines lokalen Values,
 * genau nach dem bereits bestehenden `vm: DriveStateViewModel`-Muster: Tests koennen so eine
 * kontrollierte Instanz einsetzen, ohne echte Hilt-Aufloesung von [WeatherViewModel] — dessen
 * `outsideTempC` pollt, sobald es gesammelt wird, mit echten Netzwerk-Calls (s. `BottomNavTest.kt`).
 * Gesammelt wird es deshalb NUR im Zweig, der [AmbientStatusRow] zeigt, und mit
 * `collectAsStateWithLifecycle`: kein Abruf, solange das Hauptmenue sichtbar ist oder die App im
 * Hintergrund liegt (s. KDoc von [WeatherViewModel]).
 *
 * `displayVm` liefert die Einstellung "Imperiale Einheiten" fuer die Temperatur in
 * [AmbientStatusRow] — gleiches Parameter-Muster. Gesammelt wird sie hier oben (wie `isNavHidden`)
 * statt erst in der Zeile: `useImperial` ist ein `WhileSubscribed`-StateFlow mit Startwert `false`,
 * ein erst beim Einblenden der Zeile beginnendes Sammeln koennte bei imperialer Einstellung sonst
 * kurz °C zeigen, bevor der echte DataStore-Wert eintrifft.
 */
@Composable
fun BottomNav(
    navController: NavController,
    vm: DriveStateViewModel = hiltViewModel(),
    weatherVm: WeatherViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel(),
    exitAppVm: ExitAppViewModel = hiltViewModel(),
) {
    val isNavHidden by vm.isNavHidden.collectAsStateWithLifecycle()
    val useImperial by displayVm.useImperial.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // rememberSaveable (UID-23): eine Drehung mit offenem Dialog schloss ihn bisher (Beenden bleibt eine bewusste Handlung).
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    if (showExitConfirmation) {
        ExitConfirmationDialog(
            onConfirm = {
                showExitConfirmation = false
                coroutineScope.launch {
                    exitAppVm.exitApp()
                    (context as? Activity)?.finishAffinity()
                }
            },
            onDismiss = { showExitConfirmation = false },
        )
    }

    AnimatedContent(
        targetState = isNavHidden,
        transitionSpec = { slideInVertically { it } togetherWith slideOutVertically { it } },
        label = "BottomNavOrAmbientStatus",
    ) { hidden ->
        if (hidden) {
            val outsideTempC by weatherVm.outsideTempC.collectAsStateWithLifecycle()
            AmbientStatusRow(outsideTempC = outsideTempC, useImperial = useImperial)
        } else {
            Column {
                HorizontalDivider()
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding().height(64.dp),
                    // `surface` statt M3-Default `surfaceContainer` (Material3 1.4.0, per javap
                    // geprueft; Tonal-Elevation dort ohnehin 0dp): dieselbe Flaeche wie
                    // AmbientStatusRow, damit der AnimatedContent-Wechsel keinen Farbsprung zeigt.
                    // Zugleich die Cockpit-Toenung: die drei Cockpit-Schemata definieren kein
                    // surfaceContainer, der Default waere das neutrale M3-Grau. Dokumentierte
                    // M3-Abweichung, s. CLAUDE.md.
                    containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets(0)
                ) {
                    // Explizite 10sp statt MaterialTheme.typography.labelSmall (Stand 2026-09-29,
                    // User-Wunsch "kleinere Schrift"): labelSmall traegt wie jede Rolle in
                    // AppTypography BIKE_SCALE (1.18x), macht "Einstellungen" also nur geringfuegig
                    // kleiner (~13sp statt ~14sp) — im Portraet-Modus (5 Spalten, ~72dp breit)
                    // reichte das nicht, das Wort brach weiterhin um und die zweite Zeile wurde vom
                    // festen 64dp-Leistenrahmen abgeschnitten (visuell auf dem Pixel 8 Pro
                    // bestaetigt). Bewusst UNSKALIERT, nur fuer diesen einen Anwendungsfall (Nav-
                    // Labels stehen in keiner Zeile der Typografie-Hierarchie in CLAUDE.md) —
                    // andere Textrollen/Live-Werte bleiben von BIKE_SCALE unberuehrt. Im Querformat
                    // (viel breitere Spalten) waere das nicht noetig gewesen, gilt hier aber
                    // einheitlich fuer beide Ausrichtungen. fontFamily/Farbe erbt Text() weiterhin
                    // vom umgebenden LocalTextStyle (Space Grotesk, onSurfaceVariant).
                    val navLabelStyle = TextStyle(fontSize = 10.sp)
                    navItems.forEach { (screen, icon, labelRes) ->
                        val label = stringResource(labelRes)
                        NavigationBarItem(
                            // parentTab(): a sub-page keeps the tab it was opened from highlighted.
                            selected = parentTab(currentRoute) == screen,
                            onClick = {
                                val switchTab = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                when (bottomNavAction(currentRoute, screen)) {
                                    BottomNavAction.Ignore -> Unit
                                    // Re-tap on the highlighted tab from one of its sub-pages: back to the tab's start page.
                                    // popBackStack() is false when that page is not on the stack — then navigate as usual.
                                    BottomNavAction.PopToTabRoot ->
                                        if (!navController.popBackStack(screen.route, inclusive = false)) switchTab()
                                    BottomNavAction.SwitchTab -> switchTab()
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, style = navLabelStyle) },
                            alwaysShowLabel = true
                        )
                    }
                    // Kein Navigationsziel, deshalb ausserhalb von navItems: loest den
                    // Bestaetigungsdialog aus statt navController.navigate(). NavigationBar
                    // gewichtet alle Kinder automatisch gleich, damit sind alle fuenf Punkte
                    // gleichmaessig verteilt, ohne eigene Layout-Logik.
                    val exitLabel = stringResource(R.string.nav_exit)
                    NavigationBarItem(
                        selected = false,
                        onClick = { showExitConfirmation = true },
                        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = exitLabel) },
                        label = { Text(exitLabel, style = navLabelStyle) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    }
}

/**
 * Sicherheitsabfrage vor [ExitAppViewModel.exitApp] — sitzt permanent im Hauptmenue, ein
 * versehentlicher Treffer mit Handschuhen auf holpriger Strecke soll die App nicht sofort beenden.
 */
@Composable
internal fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exit_app_dialog_title)) },
        text = { Text(stringResource(R.string.exit_app_dialog_message)) },
        // Ein Dialog ist ein eigenes Fenster mit eigener ViewConfiguration: die 56-dp-Regel aus AppTheme gilt hier nur,
        // wenn sie in den Slots erneut gesetzt wird (s. ProvideMinTouchTarget).
        confirmButton = {
            ProvideMinTouchTarget { TextButton(onClick = onConfirm) { Text(stringResource(R.string.exit_app_confirm)) } }
        },
        dismissButton = {
            ProvideMinTouchTarget { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

@Preview(name = "ExitConfirmationDialog · dark (blue cockpit)")
@Composable
private fun PreviewExitConfirmationDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        ExitConfirmationDialog(onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "ExitConfirmationDialog · light (Default scheme)")
@Composable
private fun PreviewExitConfirmationDialogLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        ExitConfirmationDialog(onConfirm = {}, onDismiss = {})
    }
}
