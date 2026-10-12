package de.adv990de.obd2.ui.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.adv990de.obd2.R

// Lint's InlinedApi guard only recognizes a direct `Build.VERSION.SDK_INT >= ...` comparison —
// it can't see through `sdkInt` as a parameter, even though the real call sites below all use
// the default (the true Build.VERSION.SDK_INT) and only tests ever override it. The guard here
// is real, just not statically visible to that specific check.
/**
 * Die Berechtigungen, ohne die die App nichts tun kann — NUR diese sperren sie. `POST_NOTIFICATIONS`
 * gehoert bewusst nicht dazu (s. [optionalPermissions]).
 */
@SuppressLint("InlinedApi")
internal fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
    // Die App verbindet sich nur mit gekoppelten Classic-Adaptern (kein BLE-Scan mehr, 1.24.9): das braucht
    // BLUETOOTH_CONNECT (ab API 31) bzw. BLUETOOTH (bis API 30). BLUETOOTH_SCAN, BLUETOOTH_ADMIN und der Standort bis
    // API 30 gehoerten nur zum entfernten BLE-Scan.
    if (sdkInt >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.BLUETOOTH)
    }
}.toTypedArray()

/**
 * Wird mit angefragt, sperrt aber nie: Der Vordergrunddienst laeuft auch ohne `POST_NOTIFICATIONS`
 * (nur seine Benachrichtigung bleibt unsichtbar). Als Pflicht blieb ein Nutzer, der sie zweimal
 * abgelehnt hat, fuer immer auf dem Sperrbildschirm — Android zeigt den Dialog dann nicht mehr.
 */
@SuppressLint("InlinedApi")
internal fun optionalPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

/** Alles, was der System-Dialog abfragt: Pflicht plus optional, in einem Aufruf. */
internal fun requestedPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    requiredPermissions(sdkInt) + optionalPermissions(sdkInt)

/** Ob die App benutzbar ist — haengt ausschliesslich an den Pflicht-Berechtigungen. */
internal fun allRequiredGranted(
    sdkInt: Int = Build.VERSION.SDK_INT,
    isGranted: (String) -> Boolean,
): Boolean = requiredPermissions(sdkInt).all(isGranted)

/** Ob der System-Dialog noch etwas zu fragen hat (auch eine fehlende optionale Berechtigung). */
internal fun anyRequestedMissing(
    sdkInt: Int = Build.VERSION.SDK_INT,
    isGranted: (String) -> Boolean,
): Boolean = !requestedPermissions(sdkInt).all(isGranted)

/** Oeffnet die App-Detailseite der Systemeinstellungen — der Weg zurueck, wenn der Dialog nicht mehr erscheint. */
internal fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Bewusst `remember`, nicht `rememberSaveable` (UID-23): der Wert ist KEIN Zustand der Oberflaeche, sondern eine Momentaufnahme des
    // Systems. Er wird bei jedem ON_RESUME neu gelesen; ein gespeicherter Wert wuerde nach einer Drehung eine inzwischen entzogene
    // Berechtigung als erteilt zeigen.
    var allGranted by remember { mutableStateOf(allRequiredGranted(isGranted = context::isGranted)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Den tatsaechlichen Zustand lesen statt der Ergebnis-Map: Die optionale Berechtigung darf
        // das Tor nicht schliessen, auch wenn sie in der Map als abgelehnt steht.
        allGranted = allRequiredGranted(isGranted = context::isGranted)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allGranted = allRequiredGranted(isGranted = context::isGranted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (anyRequestedMissing(isGranted = context::isGranted)) launcher.launch(requestedPermissions())
    }

    if (allGranted) {
        content()
    } else {
        PermissionRequiredScreen(
            onGrant = { launcher.launch(requestedPermissions()) },
            onOpenSettings = {
                try {
                    context.startActivity(appSettingsIntent(context.packageName))
                } catch (_: ActivityNotFoundException) {
                    // Kein Einstellungs-Handler (exotisches OEM-Image): nichts zu tun, der Hinweistext bleibt.
                }
            },
        )
    }
}

/**
 * Der Sperrbildschirm. Zustandslos, damit er sich testen laesst, ohne den echten System-Dialog
 * auszuloesen. Der zweite Knopf ist der Ausweg, wenn Android die Berechtigung nicht mehr erfragt
 * (zweimal abgelehnt = dauerhaft): ohne ihn lief der Erteilen-Knopf ins Leere.
 */
@Composable
internal fun PermissionRequiredScreen(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Scrollbar: im Querformat und bei grosser Schrift passen vier Zeilen Text und zwei 56-dp-Knoepfe sonst nicht.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.permission_bt_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.permission_bt_rationale),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Text(
                stringResource(R.string.permission_settings_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            // 56 dp: handschuhtauglich (CLAUDE.md, Design-Prinzip 4) — der M3-Standard waere 40 dp.
            Button(onClick = onGrant, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.permission_grant))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.permission_open_settings))
            }
        }
    }
}
