package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Die Testabdeckung ist **auf Abruf** messbar (`scripts/coverage.sh`) und als Stand festgehalten (`docs/qa/coverage.md`). Zwei Dinge dürfen dabei nie kippen:
 *  1. Ohne `-PcoverageReport` ändert sich am Build **nichts** — kein JaCoCo-Plugin, kein Agent, keine Instrumentierung. Sonst liefe jeder Gate-Lauf und jeder Release langsamer und
 *     bräuchte JaCoCo-Artefakte, und `testDebugUnitTest` bekäme einen Agenten, den niemand bestellt hat.
 *  2. Der festgehaltene Stand ist die **kombinierte** Messung (JVM- und Geräte-Tests); eine reine JVM-Messung ist eine Untergrenze und überschreibt ihn nie.
 * Dieser Test hält beides fest, dazu dass Skripte, Tests und Stand da sind, übersetzbar bleiben und die JaCoCo-Artefakte zur geprüften Abhängigkeitsliste gehören.
 */
class CoverageSetupTest {

    private val buildFile = File(projectRoot, "app/build.gradle.kts")
    private val script = File(projectRoot, "scripts/coverage.sh")
    private val summary = File(projectRoot, "scripts/coverage_summary.py")
    private val summaryTests = File(projectRoot, "scripts/test_coverage_summary.py")
    private val snapshot = File(projectRoot, "docs/qa/coverage.md")
    private val verification = File(projectRoot, "gradle/verification-metadata.xml")

    private fun run(vararg command: String): Process? = try {
        ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (_: java.io.IOException) {
        null
    }

    @Test
    fun `the scripts, their tests and the snapshot are in the repository`() {
        assertTrue("scripts/coverage.sh fehlt", script.isFile)
        assertTrue("scripts/coverage_summary.py fehlt", summary.isFile)
        assertTrue("scripts/test_coverage_summary.py fehlt", summaryTests.isFile)
        assertTrue("docs/qa/coverage.md fehlt", snapshot.isFile)
    }

    @Test
    fun `without the property the build is unchanged - plugin, agent and instrumentation are all behind coverageReport`() {
        val text = buildFile.readText()
        assertTrue("die Property coverageReport fehlt", """providers.gradleProperty("coverageReport")""" in text)
        assertTrue("Agent fuer die JVM-Tests nicht an die Property gebunden", Regex("""enableUnitTestCoverage\s*=\s*coverageEnabled""").containsMatchIn(text))
        assertTrue("Instrumentierung der Geraete-Tests nicht an die Property gebunden", Regex("""enableAndroidTestCoverage\s*=\s*coverageEnabled""").containsMatchIn(text))
        val gate = text.indexOf("if (coverageEnabled) {")
        assertTrue("der Block if (coverageEnabled) fehlt", gate > 0)
        val applyAt = text.indexOf("""apply(plugin = "jacoco")""")
        assertTrue("das JaCoCo-Plugin wird nicht (oder nicht hinter der Property) angewendet", applyAt > gate)
        val pluginsBlock = text.substringAfter("plugins {").substringBefore("}")
        assertFalse("JaCoCo steht im plugins-Block und wirkte auf jeden Build: $pluginsBlock", "jacoco" in pluginsBlock.lowercase())
        assertEquals("coverageEnabled wird genau einmal aus der Property gebildet", 1, Regex("""val coverageEnabled\s*=""").findAll(text).count())
    }

    @Test
    fun `the report leaves out generated code and merges both kinds of tests unless asked for JVM only`() {
        val text = buildFile.readText()
        // Nur die Liste selbst zaehlt: dieselben Woerter stehen auch in Kommentaren (BuildConfig, Hilt), eine Suche im ganzen Text sahe eine entfernte Ausnahme nicht.
        val generated = text.substringAfter("val generatedCode = listOf(").substringBefore("\n    )")
        assertTrue("die Liste generatedCode fehlt", generated.length > 20 && generated != text)
        for (pattern in listOf(
            "**/R.class", "**/BuildConfig.*", "**/*_Factory.class", "**/*_MembersInjector.class", "**/Hilt_*.class",
            "**/*_HiltComponents*", "**/Dagger*.class", "hilt_aggregated_deps/**", "**/*_Impl.class",
        )) {
            assertTrue("die Ausnahme fuer erzeugten Code '$pattern' fehlt in generatedCode", "\"$pattern\"" in generated)
        }
        assertFalse("ComposableSingletons gehoeren NICHT in die Ausnahmen (ihre Rumpfe sind Quelltext der Bildschirme)", "ComposableSingletons" in generated)
        assertTrue("die JVM-Daten fehlen im Bericht", "outputs/unit_test_code_coverage" in text)
        assertTrue("die Geraete-Daten fehlen im Bericht", "outputs/code_coverage" in text)
        assertTrue("coverageJvmOnly fehlt", "coverageJvmOnly" in text)
        assertTrue("der Bericht laeuft nicht nach den Tests", """mustRunAfter("testDebugUnitTest", "connectedDebugAndroidTest")""" in text)
    }

    @Test
    fun `the JaCoCo artifacts belong to the verified dependencies`() {
        val text = verification.readText()
        for (name in listOf("org.jacoco.agent", "org.jacoco.ant", "org.jacoco.core", "org.jacoco.report")) {
            assertTrue("$name fehlt in gradle/verification-metadata.xml", """group="org.jacoco" name="$name"""" in text)
        }
    }

    @Test
    fun `a JVM-only run never overwrites the committed snapshot with a lower bound`() {
        val text = script.readText()
        val jvmBranch = Regex("""if \[ "\${'$'}mode" = jvm \]; then[\s\S]*?out="([^"]+)"[\s\S]*?else[\s\S]*?out="([^"]+)"""").find(text)
        assertTrue("die Verzweigung nach dem Modus fehlt", jvmBranch != null)
        assertEquals("app/build/reports/jacoco/coverage-jvm.md", jvmBranch!!.groupValues[1])
        assertEquals("docs/qa/coverage.md", jvmBranch.groupValues[2])
    }

    @Test
    fun `the device run checks that the phone is awake and unlocked and never bypasses the lock`() {
        val text = script.readText()
        for (expected in listOf("check_device", "mWakefulness=Awake", "isKeyguardShowing=false", "genau ein Geraet", "umgeht die Sperre nie")) {
            assertTrue("coverage.sh nennt '$expected' nicht", expected in text)
        }
        assertFalse("das Skript darf die Sperre nie umgehen", "input keyevent" in text || "KEYCODE" in text)
        assertTrue("pipefail fehlt (der Exit-Code von Gradle muss zaehlen)", "set -euo pipefail" in text)
    }

    @Test
    fun `the shell script is valid`() {
        val bash = run("bash", "-n", script.absolutePath)
        assumeTrue("bash nicht verfuegbar", bash != null)
        val output = bash!!.inputStream.bufferedReader().readText()
        assertTrue(bash.waitFor(30, TimeUnit.SECONDS))
        assertEquals("Syntaxfehler in coverage.sh: $output", 0, bash.exitValue())
    }

    @Test
    fun `the summary script is valid Python`() {
        val python = run("python3", "-c", "import ast,sys; ast.parse(open(sys.argv[1]).read())", summary.absolutePath)
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue(python.waitFor(30, TimeUnit.SECONDS))
        assertEquals("Syntaxfehler in coverage_summary.py: $output", 0, python.exitValue())
    }

    @Test
    fun `the calculations of the summary pass their own Python tests`() {
        val python = run("python3", "-I", summaryTests.absolutePath)
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue("test_coverage_summary.py lief nicht in 60 s", python.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Python-Tests der Abdeckungs-Auswertung scheitern:\n$output", 0, python.exitValue())
    }

    @Test
    fun `the snapshot is the combined measurement and names date, version, method and limits`() {
        val doc = snapshot.readText()
        for (expected in listOf("**Stand:**", "**Version:**", "**Messweise:** JVM- und Geräte-Tests", "scripts/coverage.sh", "Zeilenabdeckung", "## Je Paket", "**Gesamt:")) {
            assertTrue("coverage.md nennt '$expected' nicht", expected in doc)
        }
        assertFalse("der festgehaltene Stand ist eine Untergrenze (nur JVM) — er muss die kombinierte Messung sein", "Untergrenze:" in doc)
        assertFalse("im festgehaltenen Stand steht eine Warnung ueber fehlgeschlagene Tests", "**Achtung:**" in doc)
    }

    @Test
    fun `the readme and CLAUDE_md point to the script and the snapshot`() {
        for (name in listOf("README.md", "CLAUDE.md")) {
            val text = File(projectRoot, name).readText()
            assertTrue("$name nennt scripts/coverage.sh nicht", "scripts/coverage.sh" in text)
            assertTrue("$name nennt docs/qa/coverage.md nicht", "docs/qa/coverage.md" in text)
        }
    }
}
