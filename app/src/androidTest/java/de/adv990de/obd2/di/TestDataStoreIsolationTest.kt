package de.adv990de.obd2.di

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Jeder Test hat seinen eigenen DataStore (B37, `TestAppModule`). Zwei SPIEGELBILDLICHE Tests: Beide schreiben ein
 * anderes Farbschema und verlangen vorher den Standardwert. Ein geteilter Speicher — der echte `app_prefs` der
 * Debug-App, wie bis 1.24.4 — liesse den jeweils zweiten (oder schon den ersten, falls der Entwickler ein Schema
 * gewaehlt hat) rot werden, egal in welcher Reihenfolge sie laufen.
 */
@HiltAndroidTest
class TestDataStoreIsolationTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Inject lateinit var appPreferences: AppPreferences

    @Before fun setup() = hiltRule.inject()

    private fun startsFromTheDefaultThenWrites(written: String) = runBlocking {
        assertEquals(
            "ein frischer Speicher: Standard, nicht das Ergebnis eines anderen Tests oder der Entwicklereinstellung",
            AppPreferences.THEME_SYSTEM,
            appPreferences.theme.first(),
        )
        appPreferences.setTheme(written)
        assertEquals(written, appPreferences.theme.first())
    }

    @Test fun firstTest_startsFromTheDefault() = startsFromTheDefaultThenWrites(AppPreferences.THEME_DARK)

    @Test fun secondTest_startsFromTheDefaultToo() = startsFromTheDefaultThenWrites(AppPreferences.THEME_AMBER)
}
