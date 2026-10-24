package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Testhilfe "der Simulator friert ein" (`SimulatorFreeze`) darf nie in einem Release stehen: die Property `simFreezeAfterSeconds` wirkt nur im `debug`-Block, der Standardwert ist 0 (aus),
 * und der Simulator selbst haengt allein an dieser einen Stelle.
 */
class SimulatorFreezeSourceTest {

    private val gradle = File(projectRoot, "app/build.gradle.kts").readText()

    private fun block(name: String): String {
        val start = gradle.indexOf("        $name {")
        assertTrue("Block $name fehlt in app/build.gradle.kts", start >= 0)
        var depth = 0
        for (i in gradle.indexOf('{', start) until gradle.length) {
            when (gradle[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return gradle.substring(start, i + 1) }
            }
        }
        error("Block $name nicht geschlossen")
    }

    @Test
    fun `the default is off - zero seconds`() {
        assertTrue(gradle.contains("""buildConfigField("int", "SIM_FREEZE_AFTER_SECONDS", "0")"""))
    }

    @Test
    fun `only the debug build type reads the property - the release build type never mentions it`() {
        assertTrue("der Debug-Block liest die Property", block("debug").contains("simFreezeAfterSeconds"))
        assertEquals("der Release-Block kennt die Property nicht", false, block("release").contains("simFreezeAfterSeconds"))
        assertEquals("der Release-Block setzt das Feld nicht", false, block("release").contains("SIM_FREEZE_AFTER_SECONDS"))
        assertEquals("gelesen wird die Property genau einmal", 1, Regex("""prop\("simFreezeAfterSeconds"\)""").findAll(gradle).count())
    }

    @Test
    fun `the freeze time comes from that one build field and only the simulator connection uses it`() {
        val sources = File(projectRoot, "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val users = sources.filter { it.readText().contains("SIM_FREEZE_AFTER_SECONDS") }.map { it.name }
        assertEquals(listOf("SimulatorFreeze.kt"), users)
        val connections = sources.filter { it.readText().contains("SimulatorFreeze.atMs") }.map { it.name }
        assertEquals(listOf("SimulatorObdConnection.kt"), connections)
    }
}
