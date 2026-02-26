package de.adv990de.obd2

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
class HiltTestActivity : ComponentActivity()
