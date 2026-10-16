package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md ("Build & Test (lokal)", Unterabschnitt "Teststufen") legt fest, welche Tests bei welcher Aenderung laufen. Die Regel
 * nennt Testklassen und ein Paket beim Namen: wird eine umbenannt oder verschoben, liefe der gezielte Lauf ins Leere und die
 * Regel waere stillschweigend veraltet — genau die Art Drift, die die uebrigen Doku-Tests verhindern. Der Gate-Satz (volle
 * JVM-Suite vor jedem Code-Commit) ist das, was die Stufen sicher macht, denn es gibt kein CI; er darf nicht still wegfallen.
 */
class TestLevelsDocsTest {

    private val buildAndTest: String by lazy {
        val text = File(projectRoot, "CLAUDE.md").readText()
        val start = text.indexOf("## Build & Test (lokal)")
        assertTrue("CLAUDE.md braucht den Abschnitt '## Build & Test (lokal)'", start >= 0)
        // "\n## " beginnt NICHT bei "\n### ": der Unterabschnitt gehoert noch zum Abschnitt.
        val end = text.indexOf("\n## ", start + 1)
        text.substring(start, if (end < 0) text.length else end)
    }

    private val levels: String by lazy {
        val text = buildAndTest.substringAfter("### Teststufen", missingDelimiterValue = "")
        assertTrue("'## Build & Test (lokal)' braucht den Unterabschnitt '### Teststufen'", text.isNotBlank())
        text
    }

    private fun testClassNames(): Set<String> =
        listOf("app/src/test", "app/src/androidTest")
            .flatMap { File(projectRoot, it).walkTopDown().filter { f -> f.extension == "kt" }.map { f -> f.nameWithoutExtension }.toList() }
            .toSet()

    @Test
    fun `every test class the test levels name exists`() {
        val named = Regex("`([A-Z][A-Za-z0-9]*Test)`").findAll(levels).map { it.groupValues[1] }.toSet()
        assertTrue("Die Teststufen nennen mindestens drei Testklassen beim Namen (das Netz ueber Dateien hinweg), war: $named", named.size >= 3)

        val missing = named - testClassNames()
        assertTrue("Testklassen aus den Teststufen, die es nicht mehr gibt: $missing", missing.isEmpty())
    }

    @Test
    fun `every test package the test levels name exists`() {
        val named = Regex("de\\.adv990de\\.obd2(?:\\.[a-z]+)+\\.\\*").findAll(levels).map { it.value }.toSet()
        assertTrue("Die Teststufen nennen mindestens ein Test-Paket, war: $named", named.isNotEmpty())

        val missing = named.filterNot { File(projectRoot, "app/src/test/java/" + it.removeSuffix(".*").replace('.', '/')).isDirectory }
        assertTrue("Test-Pakete aus den Teststufen, die es nicht mehr gibt: $missing", missing.isEmpty())
    }

    @Test
    fun `the test levels keep the full JVM suite as the gate before every code commit`() {
        assertTrue("Der Gate-Satz fehlt: 'vor jedem Code-Commit'", "vor jedem Code-Commit" in levels)
        assertTrue("Der Gate-Satz nennt die volle JVM-Suite", "volle JVM-Suite" in levels)
        assertTrue("Der Gate-Satz sagt, dass es keine Ausnahme gibt", "ohne Ausnahme" in levels)
    }

    @Test
    fun `the test levels say that test counts come from a full run only`() {
        // Eine Zahl aus einem gezielten Lauf in README oder CLAUDE.md waere falsch (nur ein Bruchteil der Tests).
        assertTrue("Die Regel zu den Testzahlen fehlt", "Testzahlen" in levels && "vollen Lauf" in levels)
    }
}
