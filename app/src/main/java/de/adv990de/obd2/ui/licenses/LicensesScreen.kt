package de.adv990de.obd2.ui.licenses

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test-only selector for the list. */
internal const val LICENSES_LIST_TEST_TAG = "licenses_list"

/** Liest einen Lizenztext aus den Assets, oder `null`, wenn das nicht gelingt. */
internal fun readLicenseAsset(context: Context, assetName: String): String? =
    try {
        context.assets.open(assetName).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

/**
 * "Open-Source-Lizenzen": welche Komponenten die App mitbringt, unter welcher Lizenz, und darunter die vollstaendigen
 * Lizenztexte. Apache 2.0 verlangt bei der Weitergabe den Lizenzhinweis; die Texte stammen aus den Originalquellen
 * (`docs/licenses/`, als Assets eingebunden).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag(LICENSES_LIST_TEST_TAG),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(LICENSED_COMPONENTS, key = { it.name }) { component ->
                Column {
                    ListItem(
                        headlineContent = { Text(component.name) },
                        supportingContent = {
                            Text(stringResource(component.detailRes) + " · " + component.license.label)
                        },
                    )
                    HorizontalDivider()
                }
            }
            item { SectionLabel(stringResource(R.string.licenses_texts_header)) }
            items(LicenseKind.values().toList(), key = { it.name }) { kind -> LicenseText(kind) }
        }
    }
}

@Composable
private fun LicenseText(kind: LicenseKind) {
    val context = LocalContext.current
    // Von der Platte, nicht im Main-Thread: ein Text ist bis ~11 KB gross, die Liste hat vier.
    val text by produceState<String?>(initialValue = null, kind) {
        value = withContext(Dispatchers.IO) { readLicenseAsset(context, kind.assetName) }
    }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kind.label, style = MaterialTheme.typography.titleSmall)
        Text(
            text ?: stringResource(R.string.licenses_text_unavailable),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
