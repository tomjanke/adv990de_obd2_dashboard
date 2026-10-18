package de.adv990de.obd2.ui.permission

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Sind Benachrichtigungen für die App erlaubt? `areNotificationsEnabled()` ist falsch, wenn ab Android 13 `POST_NOTIFICATIONS` fehlt **und** wenn der Nutzer sie in den
 * Systemeinstellungen gesperrt hat (auf jeder Stufe) — beide Gründe sehen für die App gleich aus und haben dieselbe Folge: kein Alarm erscheint in der Statusleiste.
 */
private fun readNotificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Der aktuelle Stand der Benachrichtigungen als Compose-Zustand. Er kann sich AUSSERHALB der App ändern (Systemeinstellungen, Berechtigungsdialog); wer ihn einmal beim
 * Komponieren liest, zeigt danach Veraltetes — deshalb liest diese Funktion bei jedem `ON_RESUME` neu, genau wie [rememberLocationPermissionState].
 */
@Composable
fun rememberNotificationsEnabled(): Boolean = rememberNotificationsEnabled(::readNotificationsEnabled)

/** Wie [rememberNotificationsEnabled], mit der Lesefunktion als Naht für Tests: das System lässt sich dort nicht verändern (die Berechtigung zu entziehen beendet den Testprozess). */
@Composable
internal fun rememberNotificationsEnabled(read: (Context) -> Boolean): Boolean {
    val context = LocalContext.current
    val currentRead by rememberUpdatedState(read)
    var enabled by remember(context) { mutableStateOf(currentRead(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = currentRead(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}
