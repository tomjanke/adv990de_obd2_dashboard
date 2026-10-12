package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QS C / SEC-7: Der Build prueft, was er herunterlaedt. Vorher wurde die Gradle-Distribution ohne Pruefsumme geladen, und
 * Abhaengigkeiten wurden gar nicht verifiziert — ein manipulierter Download waere nicht aufgefallen.
 *
 *  - `distributionSha256Sum` in `gradle-wrapper.properties`: Gradle verwirft eine Distribution mit anderer Pruefsumme.
 *  - `gradle/verification-metadata.xml`: SHA-256 jeder Abhaengigkeit und jedes Plugins; Gradle bricht ab, wenn ein Artefakt
 *    nicht passt. Neue oder aktualisierte Abhaengigkeiten brauchen einen Lauf von
 *    `./gradlew --write-verification-metadata sha256 <Aufgaben>` (s. CLAUDE.md).
 *
 * Bekannte Grenze: Das JDK holt der foojay-Resolver (Daemon-JVM-Kriterien in `gradle/gradle-daemon-jvm.properties`);
 * Gradle kennt dafuer keine Pruefsumme.
 */
class BuildIntegrityTest {

    private fun read(relative: String) = File(projectRoot, relative).readText()

    @Test
    fun `the Gradle distribution is pinned by its SHA-256`() {
        val props = read("gradle/wrapper/gradle-wrapper.properties")
        val sum = Regex("""^distributionSha256Sum=([0-9a-f]{64})$""", RegexOption.MULTILINE).find(props)?.groupValues?.get(1)
        assertTrue("distributionSha256Sum (64 Hex-Zeichen) fehlt in gradle-wrapper.properties", sum != null)
    }

    @Test
    fun `the pinned checksum is the one of the distribution named in the URL`() {
        // Gradle 9.5.0 (bin): offiziell von services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256 und von
        // gradle.org/release-checksums bestaetigt, ausserdem selbst gehasht (140319124 Bytes). Wird die Version angehoben,
        // MUSS diese Zahl (und die Pruefsumme in den Properties) gemeinsam geaendert werden.
        val props = read("gradle/wrapper/gradle-wrapper.properties")
        assertTrue("distributionUrl nennt 9.5.0-bin", props.contains("gradle-9.5.0-bin.zip"))
        assertTrue(
            props.contains("distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"),
        )
    }

    @Test
    fun `dependency verification is on and checks SHA-256`() {
        val file = File(projectRoot, "gradle/verification-metadata.xml")
        assertTrue("gradle/verification-metadata.xml fehlt", file.isFile)
        val xml = file.readText()
        assertTrue(xml.contains("<verify-metadata>true</verify-metadata>"))
        assertTrue("SHA-256-Eintraege erwartet", xml.contains("<sha256 value="))
        assertTrue("keine Pruefsummen-Ausnahmen fuer alles (trust-everything)", !xml.contains("<trusted-artifacts>"))
    }

    @Test
    fun `every direct runtime dependency has verified artifacts`() {
        val xml = read("gradle/verification-metadata.xml")
        val catalog = read("gradle/libs.versions.toml")
        val modules = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*\{\s*module\s*=\s*"([^":]+):([^":]+)"""", RegexOption.MULTILINE)
            .findAll(catalog).associate { it.groupValues[1].replace('_', '-').replace('.', '-') to (it.groupValues[2] to it.groupValues[3]) }
        val accessors = Regex("""^\s*implementation\((?:platform\()?libs\.([A-Za-z0-9.]+)\)""", RegexOption.MULTILINE)
            .findAll(read("app/build.gradle.kts")).map { it.groupValues[1] }.toList()
        assertTrue("Parser sieht Abhaengigkeiten", accessors.size > 10)

        val missing = accessors.mapNotNull { modules[it.replace('.', '-')] }
            .filter { (group, artifact) -> !Regex("""<component group="${Regex.escape(group)}" name="${Regex.escape(artifact)}"""").containsMatchIn(xml) }
        assertEquals("Direkte Abhaengigkeiten ohne verifizierte Artefakte (Metadaten neu schreiben): $missing", emptyList<Pair<String, String>>(), missing)
    }
}
