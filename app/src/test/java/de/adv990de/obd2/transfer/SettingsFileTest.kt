package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.AppPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `settings.json`: schreiben in Katalogreihenfolge, lesen mit Pruefung je Wert, Plan gegen den jetzigen Stand. */
class SettingsFileTest {

    @Test
    fun `the sample covers the catalog`() {
        assertEquals(SettingsCatalog.exported.map { it.key }, sampleSettings().keys.toList().let { k -> SettingsCatalog.exported.map { it.key }.filter { it in k } })
        assertEquals(36, sampleSettings().size)
    }

    @Test
    fun `every kind survives the round trip, a float stays the same float`() {
        val values = sampleSettings()
        val parsed = parseSettingsJson(settingsToJson(values))
        assertEquals(emptyList<RejectedSetting>(), parsed.rejected)
        assertEquals(emptyList<String>(), parsed.unknownKeys)
        assertEquals(values, parsed.valid)
        assertEquals(1.8571f, parsed.valid["primary_ratio"])
        assertEquals(2.9168f, parsed.valid["gear_ratio_1"])
        assertEquals(0f, parsed.valid["gear_ratio_5"])
        assertEquals(205, parsed.valid["fuel_tank_capacity_dl"])
    }

    @Test
    fun `the defaults survive the round trip too`() {
        val values = mapOf<String, Any>(
            "theme" to AppPreferences.THEME_SYSTEM,
            "primary_ratio" to AppPreferences.DEFAULT_PRIMARY_RATIO,
            "wheel_circumference_mm" to AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
            "slot_pids" to AppPreferences.DEFAULT_SLOT_PIDS.joinToString(","),
        )
        assertEquals(values, parseSettingsJson(settingsToJson(values)).valid)
    }

    @Test
    fun `the file names format 1 and writes the keys in the order of the catalog`() {
        val text = settingsToJson(sampleSettings())
        val json = JSONObject(text)
        assertEquals(1, json.getInt("format"))
        val written = Regex("\"([a-z0-9_]+)\":").findAll(text.substringAfter("\"values\"")).map { it.groupValues[1] }.toList()
        assertEquals(SettingsCatalog.exported.map { it.key }, written)
    }

    @Test
    fun `only catalog keys are written`() {
        val text = settingsToJson(mapOf("theme" to "dark", "device_address" to "AA:BB", "fuel_km_since_fill" to 3f, "nonsense" to 1))
        assertEquals(setOf("theme"), JSONObject(text).getJSONObject("values").keys().asSequence().toSet())
    }

    @Test
    fun `a text with quotes and a backslash is escaped`() {
        val text = settingsToJson(mapOf("slot_pids" to "a\"b\\c"))
        assertEquals("a\"b\\c", JSONObject(text).getJSONObject("values").getString("slot_pids"))
    }

    @Test
    fun `unknown and excluded keys are listed and never applied`() {
        val text = """{"format":1,"values":{"theme":"dark","device_address":"AA:BB:CC:DD:EE:FF","crash_reports_enabled":false,"fuel_km_since_fill":3.5,"somethingNew":1}}"""
        val parsed = parseSettingsJson(text)
        assertEquals(mapOf<String, Any>("theme" to "dark"), parsed.valid)
        assertEquals(listOf("crash_reports_enabled", "device_address", "fuel_km_since_fill", "somethingNew"), parsed.unknownKeys)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test
    fun `an invalid value is rejected with its reason, the valid ones stay`() {
        val text = """{"format":1,"values":{"theme":"purple","use_imperial":1,"fuel_range_full_km":20,"obd_watchdog_timeout_seconds":25,"use_gps_speed":true,"gear_ratio_1":null}}"""
        val parsed = parseSettingsJson(text)
        assertEquals(mapOf<String, Any>("use_gps_speed" to true), parsed.valid)
        val byKey = parsed.rejected.associate { it.key to it.reason }
        assertEquals(
            mapOf(
                "theme" to SettingReject.UNKNOWN_VALUE,
                "use_imperial" to SettingReject.WRONG_TYPE,
                "fuel_range_full_km" to SettingReject.OUT_OF_RANGE,
                "obd_watchdog_timeout_seconds" to SettingReject.NOT_AN_OPTION,
                "gear_ratio_1" to SettingReject.WRONG_TYPE,
            ),
            byKey,
        )
        assertEquals(listOf("theme", "use_imperial", "obd_watchdog_timeout_seconds", "fuel_range_full_km", "gear_ratio_1"), parsed.rejected.map { it.key })
    }

    @Test
    fun `a newer format is read`() {
        val parsed = parseSettingsJson("""{"format":7,"values":{"theme":"green"}}""")
        assertEquals(mapOf<String, Any>("theme" to "green"), parsed.valid)
    }

    @Test
    fun `a file that is not a settings file is refused`() {
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("") }
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("not json") }
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("""{"format":1}""") }
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("""{"format":1,"values":[1]}""") }
        assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("""{"format":1,"values":"theme"}""") }
    }

    @Test
    fun `an empty values object is a valid, empty file`() {
        val parsed = parseSettingsJson("""{"format":1,"values":{}}""")
        assertTrue(parsed.valid.isEmpty() && parsed.rejected.isEmpty() && parsed.unknownKeys.isEmpty())
    }

    // ── Plan ──────────────────────────────────────────────────────────────

    @Test
    fun `the plan names what changes and counts what stays`() {
        val parsed = parseSettingsJson("""{"format":1,"values":{"theme":"dark","use_imperial":true,"fuel_reserve_km":40,"gear_ratio_2":2.1333,"primary_ratio":1.9143}}""")
        val current = mapOf<String, Any>("theme" to "system", "use_imperial" to true, "fuel_reserve_km" to 0, "gear_ratio_2" to 2.1333f, "primary_ratio" to 1.5f)
        val plan = planSettings(parsed, current)
        assertEquals(listOf("theme", "fuel_reserve_km", "primary_ratio"), plan.changes.map { it.spec.key })
        assertEquals("system", plan.changes[0].from)
        assertEquals("dark", plan.changes[0].to)
        assertEquals(0, plan.changes[1].from)
        assertEquals(40, plan.changes[1].to)
        assertEquals(1.5f, plan.changes[2].from)
        assertEquals(1.9143f, plan.changes[2].to)
        assertEquals(2, plan.unchanged)
        assertEquals(5, plan.total)
        assertEquals(1, plan.changesBy(SettingsGroup.DISPLAY))
        assertEquals(1, plan.changesBy(SettingsGroup.VEHICLE_FUEL))
        assertEquals(1, plan.changesBy(SettingsGroup.GEAR))
        assertEquals(0, plan.changesBy(SettingsGroup.ALARMS))
    }

    @Test
    fun `a key missing from the file is neither a change nor a rejection`() {
        val parsed = parseSettingsJson("""{"format":1,"values":{"theme":"dark"}}""")
        val plan = planSettings(parsed, mapOf("theme" to "dark", "use_imperial" to true))
        assertTrue(plan.changes.isEmpty())
        assertEquals(1, plan.unchanged)
        assertEquals(0, plan.rejected.size)
        assertEquals(1, plan.total)
    }

    @Test
    fun `rejected and unknown keys go into the plan and count in the total`() {
        val parsed = parseSettingsJson("""{"format":1,"values":{"theme":"purple","use_imperial":true,"device_name":"x"}}""")
        val plan = planSettings(parsed, mapOf("use_imperial" to false))
        assertEquals(listOf("theme"), plan.rejected.map { it.key })
        assertEquals(listOf("device_name"), plan.unknownKeys)
        assertEquals(1, plan.changes.size)
        assertEquals(2, plan.total)
    }

    @Test
    fun `a value the current state does not know yet is a change from null`() {
        val parsed = parseSettingsJson("""{"format":1,"values":{"theme":"dark"}}""")
        val plan = planSettings(parsed, emptyMap())
        assertEquals(1, plan.changes.size)
        assertEquals(null, plan.changes[0].from)
        assertFalse(plan.changes.isEmpty())
    }
}
