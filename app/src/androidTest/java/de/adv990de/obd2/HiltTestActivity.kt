package de.adv990de.obd2

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Minimal ComponentActivity used as host for Compose UI tests that need Hilt injection.
 *
 * Must be declared in the debug manifest so the test APK includes it.
 * Each test uses [createAndroidComposeRule]<HiltTestActivity> to set content
 * inside an @HiltAndroidTest class.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ein vollstaendiger Instrumentation-Lauf dauert laenger als die Display-Zeitsperre
        // vieler Geraete. Schaltet sich der Bildschirm zwischendurch ab oder greift der
        // Sperrbildschirm, startet diese Activity nicht mehr sichtbar — Compose meldet dann
        // "No compose hierarchies found in the app", und ab diesem Moment faellt jeder
        // folgende UI-Test um, unabhaengig davon, was er prueft.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }
}
