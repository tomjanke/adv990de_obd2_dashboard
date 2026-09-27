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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * Quelle der Aussentemperatur (s. `weather/OpenMeteoApi.kt`). Deren Lizenz CC BY 4.0
 * (https://open-meteo.com/en/licence, abgerufen 2026-09-27) verlangt Namensnennung MIT Link auf
 * die Quelle — Open-Meteos eigener Vorschlag verlinkt genau diese Adresse. Deshalb ist die
 * Namensnennung unten eine echte, antippbare Zeile und kein reiner Text.
 */
internal const val OPEN_METEO_URL = "https://open-meteo.com/"

/** Test-only selector for the attribution row — purely a semantics marker, no effect on
 *  production behavior (same convention as `GPS_SPEED_SWITCH_TEST_TAG` in `SettingsScreen.kt`). */
internal const val WEATHER_ATTRIBUTION_TEST_TAG = "app_info_weather_attribution"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(navController: NavController) {
    val context = LocalContext.current
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dashboard_config_back_desc))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoRow(stringResource(R.string.app_info_name_label), stringResource(R.string.app_name))
            HorizontalDivider()
            InfoRow(stringResource(R.string.app_info_version_label), packageInfo.versionName ?: "-")
            HorizontalDivider()
            InfoRow(stringResource(R.string.app_info_build_label), versionCode.toString())
            HorizontalDivider()
            WeatherAttributionRow(onClick = { openInBrowser(context, OPEN_METEO_URL) })
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
 * Die CC-BY-Namensnennung als Link-Zeile: Text + "extern oeffnen"-Symbol in `primary` (die
 * uebliche Link-Signalfarbe), die ganze Zeile ist das Tap-Ziel. Mindesthoehe 64dp — die
 * Zeilenhoehe fuer Listen unter den Einstellungen laut CLAUDE.md, damit zugleich ueber der
 * 56dp-Handschuh-Grenze fuer jeden Touch.
 */
@Composable
private fun WeatherAttributionRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(
                onClickLabel = stringResource(R.string.app_info_open_website),
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(WEATHER_ATTRIBUTION_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.app_info_weather_attribution),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            // Dekorativ: Zeilentext und onClickLabel tragen die Bedeutung fuer TalkBack.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
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

// endregion
