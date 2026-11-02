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
 * Das JDK laedt Gradle nie von selbst: Der foojay-Resolver loest die Daemon-JVM-Kriterien (`gradle/gradle-daemon-jvm.properties`)
 * auf, aber Gradle kennt fuer Toolchain-Downloads keine Pruefsumme. `org.gradle.java.installations.auto-download=false` macht aus
 * einem stillen, ungeprueften Download einen Abbruch mit klarer Meldung (QS-Pflege, v1.25.0).
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
        // Gradle 9.8.2 (bin): offiziell von downloads.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256 (frueher services.gradle.org,
        // leitet um) und von gradle.org/release-checksums bestaetigt; der Wrapper hat die Distribution beim Laden gegen diese Summe
        // geprueft (das Archiv bleibt danach nicht liegen, ein eigener Hash waere ein zweiter Download). Wird die Version angehoben,
        // MUSS diese Zahl (und die Pruefsumme in den Properties) gemeinsam geaendert werden.
        val props = read("gradle/wrapper/gradle-wrapper.properties")
        assertTrue("distributionUrl nennt 9.8.2-bin", props.contains("gradle-9.8.2-bin.zip"))
        assertTrue(
            props.contains("distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"),
        )
    }

    @Test
    fun `Gradle never downloads a JDK on its own - there is no checksum for it`() {
        val props = read("gradle.properties")
        assertTrue(
            "org.gradle.java.installations.auto-download=false fehlt in gradle.properties",
            Regex("""^org\.gradle\.java\.installations\.auto-download=false$""", RegexOption.MULTILINE).containsMatchIn(props),
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
    fun `the Kotlin Gradle plugin pin in the root build is the Kotlin version of the catalog`() {
        // AGP 9 bringt seine eigene (aeltere) KGP-Version mit; der Pin im buildscript-Block des Root-Builds hebt sie auf die Version
        // des Compose-Compiler-Plugins aus dem Catalog (`kotlin`). Der buildscript-Block kann den Catalog nicht lesen — die Zahl steht
        // zweimal. Laeuft sie auseinander, gewinnt bei der Aufloesung stillschweigend die hoehere: der Build laeuft, aber niemand
        // weiss mehr, welche Version den Code uebersetzt (aufgefallen beim Anheben auf 2.4.21: der Pin stand noch auf 2.4.10).
        val catalogKotlin = Regex("""^kotlin\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(read("gradle/libs.versions.toml"))?.groupValues?.get(1)
        val pinnedKotlin = Regex("""classpath\("org\.jetbrains\.kotlin:kotlin-gradle-plugin:([^"]+)"\)""").find(read("build.gradle.kts"))?.groupValues?.get(1)
        assertTrue("`kotlin = \"…\"` fehlt in libs.versions.toml", catalogKotlin != null)
        assertTrue("der kotlin-gradle-plugin-Pin fehlt im Root-build.gradle.kts", pinnedKotlin != null)
        assertEquals(
            "Der Pin in build.gradle.kts ($pinnedKotlin) und `kotlin` im Catalog ($catalogKotlin) muessen gleich sein - beide gemeinsam aendern",
            catalogKotlin, pinnedKotlin,
        )
    }

    @Test
    fun `the icon collection material-icons-extended stays out - the seven icons that needed it live in AppIcons`() {
        // Das Sammelpaket hat rund 10 700 Symbole, die App nutzt sieben; im Debug-Build steckten alle im APK. Die sieben stehen als `AppIcons` im Quelltext. Ein neues Symbol, das nur das
        // Sammelpaket hat, kommt dorthin (Pfad aus `Icons.Filled.<Name>.root`, ein Test haelt ihn fest) und holt das Paket nicht zurueck.
        for (file in listOf("app/build.gradle.kts", "gradle/libs.versions.toml", "gradle/verification-metadata.xml")) {
            val text = read(file)
            assertTrue("$file nennt das Sammelpaket material-icons-extended wieder (neues Symbol: in AppIcons ergaenzen)", !text.contains("icons-extended") && !text.contains("icons.extended"))
        }
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
