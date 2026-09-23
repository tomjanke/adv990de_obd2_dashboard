package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogsScreenTest {

    @Test
    fun `INFO parses to LogLevel INFO`() {
        assertEquals(LogLevel.INFO, parseLogLevelOrDefault("INFO"))
    }

    @Test
    fun `WARN parses to LogLevel WARN`() {
        assertEquals(LogLevel.WARN, parseLogLevelOrDefault("WARN"))
    }

    @Test
    fun `ERROR parses to LogLevel ERROR`() {
        assertEquals(LogLevel.ERROR, parseLogLevelOrDefault("ERROR"))
    }

    @Test
    fun `an empty string falls back to INFO`() {
        assertEquals(LogLevel.INFO, parseLogLevelOrDefault(""))
    }

    @Test
    fun `an unparseable string falls back to INFO`() {
        // Covers a corrupt/unexpected persisted level value (e.g. from an older schema
        // or manual DB edit) instead of crashing the log screen.
        assertEquals(LogLevel.INFO, parseLogLevelOrDefault("garbage"))
    }

    @Test
    fun `a lowercase variant is rejected and falls back to INFO`() {
        // LogLevel.valueOf() is case-sensitive, so "info" does not match LogLevel.INFO.
        assertEquals(LogLevel.INFO, parseLogLevelOrDefault("info"))
    }
}
