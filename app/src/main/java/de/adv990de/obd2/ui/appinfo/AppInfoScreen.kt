package de.adv990de.obd2.ui.appinfo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.adv990de.obd2.crash.CrashReportingStatus
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.settings.CrashReportingSettingsViewModel
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * Quelle der Aussentemperatur (s. `weather/OpenMeteoApi.kt`). Deren Lizenz CC BY 4.0
 * (https://creativecommons.org/licenses/by/4.0/) verlangt Namensnennung MIT Link auf die Quelle
 * UND einen Link auf den Lizenztext selbst — reine Namensnennung des Lizenznamens als Text
 * genuegt laut Lizenztext nicht. Open-Meteos eigene Anleitung (open-meteo.com/en/licence,
 * abgerufen 2026-09-27) verlinkt fuer die Namensnennung genau diese Adresse; [CC_BY_4_0_URL]
 * unten deckt den zweiten, separaten Link auf den Lizenztext ab. Deshalb sind es zwei echte,
 * antippbare Zeilen und kein reiner Text.
 */
internal const val OPEN_METEO_URL = "https://open-meteo.com/"

/** Der Lizenztext selbst — s. KDoc auf [OPEN_METEO_URL] oben. */
internal const val CC_BY_4_0_URL = "https://creativecommons.org/licenses/by/4.0/"

/** Test-only selector for the attribution row — purely a semantics marker, no effect on
 *  production behavior (same convention as `GPS_SPEED_SWITCH_TEST_TAG` in `SettingsScreen.kt`). */
internal const val WEATHER_ATTRIBUTION_TEST_TAG = "app_info_weather_attribution"

/** Test-only selector for the CC BY 4.0 license row, same reasoning as [WEATHER_ATTRIBUTION_TEST_TAG]. */
internal const val WEATHER_LICENSE_TEST_TAG = "app_info_weather_license"

/** Test-only selector for the row that opens the open-source licences page. */
internal const val LICENSES_ROW_TEST_TAG = "app_info_licenses"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(
    navController: NavController,
    crashVm: CrashReportingSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val crashStatus by crashVm.status.collectAsStateWithLifecycle()
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    @Suppress("DEPRECATION")
    val versionCode = packageInfo.versionCode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_info_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                // Diese Fix-Runde fuegte zwei neue, mindestens 64dp hohe tappable Zeilen
                // (WeatherAttributionRow/WeatherLicenseRow) zu vormals einer einzigen kurzen
                // Textzeile hinzu — ohne Scroll koennte der Inhalt im Querformat oder auf
                // kleineren Geraeten ueber den Bildschirmrand hinausragen und abgeschnitten werden.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoRow(stringResource(R.string.app_info_name_label), stringResource(R.string.app_name))
            HorizontalDivider()
            InfoRow(stringResource(R.string.app_info_version_label), packageInfo.versionName ?: "-")
            HorizontalDivider()
            InfoRow(stringResource(R.string.app_info_build_label), versionCode.toString())
            HorizontalDivider()
            // Was mit Fehlerdaten passiert, steht in der App selbst (QS B33/SEC-10: Sentry war nirgends offengelegt).
            InfoRow(
                stringResource(R.string.app_info_crash_reports_label),
                stringResource(
                    when (crashStatus) {
                        CrashReportingStatus.On -> R.string.app_info_crash_reports_on
                        CrashReportingStatus.Off -> R.string.app_info_crash_reports_off
                        CrashReportingStatus.NotIncluded -> R.string.app_info_crash_reports_not_included
                    }
                ),
            )
            HorizontalDivider()
            WeatherAttributionRow(onClick = { openInBrowser(context, OPEN_METEO_URL) })
            HorizontalDivider()
            WeatherLicenseRow(onClick = { openInBrowser(context, CC_BY_4_0_URL) })
            HorizontalDivider()
            // Die Lizenzen der mitgelieferten Bibliotheken und Schriften — eine Seite IN der App (kein Browser).
            LinkRow(
                text = stringResource(R.string.app_info_licenses),
                onClickLabel = stringResource(R.string.app_info_licenses_action),
                testTag = LICENSES_ROW_TEST_TAG,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                onClick = { navController.navigate(Screen.Licenses.route) },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A tappable link row shared by the Open-Meteo credit row and the CC BY 4.0 license row below it:
 * text + "open externally" icon in `primary` (the usual link signal color), the whole row is the
 * tap target. Minimum height 64dp — the list row height for settings screens per CLAUDE.md,
 * comfortably over the 56dp glove-friendly touch minimum too.
 */
@Composable
private fun LinkRow(
    text: String,
    onClickLabel: String,
    testTag: String,
    onClick: () -> Unit,
    icon: ImageVector = AppIcons.OpenInNew,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            icon,
            // Dekorativ: Zeilentext und onClickLabel tragen die Bedeutung fuer TalkBack.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Credits Open-Meteo as the weather-data source, linking its homepage (Open-Meteo's own
 *  suggested attribution link, s. KDoc on [OPEN_METEO_URL]). */
@Composable
private fun WeatherAttributionRow(onClick: () -> Unit) {
    LinkRow(
        text = stringResource(R.string.app_info_weather_attribution),
        onClickLabel = stringResource(R.string.app_info_open_website),
        testTag = WEATHER_ATTRIBUTION_TEST_TAG,
        onClick = onClick,
    )
}

/** Links the CC BY 4.0 licence text itself — naming the licence next to [WeatherAttributionRow]
 *  is not enough per the licence's own terms, a link to the licence text is required too. */
@Composable
private fun WeatherLicenseRow(onClick: () -> Unit) {
    LinkRow(
        text = stringResource(R.string.app_info_weather_license),
        onClickLabel = stringResource(R.string.app_info_open_license),
        testTag = WEATHER_LICENSE_TEST_TAG,
        onClick = onClick,
    )
}

/**
 * Oeffnet [url] im Browser. Faengt [ActivityNotFoundException] ab (Geraet ganz ohne Browser) —
 * dasselbe Muster wie der Teilen-Intent in `HistoryScreen`, statt die App abstuerzen zu lassen;
 * es gibt dann schlicht nichts, das den Link oeffnen koennte.
 */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // Kein Browser installiert — bewusst kein Absturz.
    }
}

// region — Previews

@Preview(name = "WeatherAttributionRow · blue", showBackground = true)
@Composable
private fun PreviewWeatherAttributionRowBlue() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) { WeatherAttributionRow(onClick = {}) }
        }
    }
}

@Preview(name = "WeatherAttributionRow · default light", showBackground = true)
@Composable
private fun PreviewWeatherAttributionRowDefaultLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) { WeatherAttributionRow(onClick = {}) }
        }
    }
}

@Preview(name = "WeatherLicenseRow · blue", showBackground = true)
@Composable
private fun PreviewWeatherLicenseRowBlue() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) { WeatherLicenseRow(onClick = {}) }
        }
    }
}

@Preview(name = "WeatherLicenseRow · default light", showBackground = true)
@Composable
private fun PreviewWeatherLicenseRowDefaultLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) { WeatherLicenseRow(onClick = {}) }
        }
    }
}

// endregion
