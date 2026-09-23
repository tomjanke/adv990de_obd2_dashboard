package de.adv990de.obd2.ui.permission

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
@SuppressLint("InlinedApi")
internal fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.S) {
        // BLUETOOTH_SCAN is declared with neverForLocation in the manifest, so no location needed
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.BLUETOOTH)
        add(Manifest.permission.BLUETOOTH_ADMIN)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    var allGranted by remember {
        mutableStateOf(
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PermissionChecker.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        allGranted = results.values.all { it }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allGranted = requiredPermissions().all {
                    ContextCompat.checkSelfPermission(context, it) == PermissionChecker.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!allGranted) launcher.launch(requiredPermissions())
    }

    if (allGranted) {
        content()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
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
                Button(onClick = { launcher.launch(requiredPermissions()) }) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}
