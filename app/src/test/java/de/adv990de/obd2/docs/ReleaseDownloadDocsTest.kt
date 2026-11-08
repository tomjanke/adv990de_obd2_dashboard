package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Abschnitt „Herunterladen“ / „Download“ der READMEs und die Beschreibung von `--publish` im Release-Ablauf (2026-11-08, Backlog C, Plan E).
 *
 * Die Links, der Dateiname, die Pruefzeile und der Fingerabdruck stehen an mehreren Stellen (`scripts/release-lib.sh` und `scripts/release.sh` erzeugen das GitHub-Release,
 * die READMEs beschreiben es, `scripts/release-cert.sha256` haelt den Fingerabdruck fest). Der Test haelt sie zusammen: wer den Dateinamen im Skript aendert, bricht den
 * stabilen Link `…/releases/latest/download/<Name>`; wer den Schluessel wechselt, laesst den Fingerabdruck im README falsch zurueck.
 */
class ReleaseDownloadDocsTest {

    private val repository = "https://github.com/tomjanke/adv990de_obd2_dashboard"
    private val latestPage = "$repository/releases/latest"
    private val assetName = "OBD-Dashboard.apk"
    private val apkLink = "$repository/releases/latest/download/$assetName"
    private val checksumLink = "$apkLink.sha256"

    private fun text(relative: String): String {
        val f = File(projectRoot, relative)
        assertTrue("$relative fehlt", f.isFile)
        return f.readText()
    }

    // ── Der Dateiname und die Links ───────────────────────────────────────

    @Test
    fun `the file name of the release script is the one the READMEs link`() {
        val declared = Regex("""RELEASE_ASSET_NAME="([^"]+)"""").find(text("scripts/release-lib.sh"))?.groupValues?.get(1)
        assertEquals("scripts/release-lib.sh nennt einen anderen Dateinamen als die READMEs verlinken — der stabile Link wuerde brechen", assetName, declared)
        for (path in ReadmeFiles.all) {
            assertTrue("$path nennt $assetName nicht", text(path).contains("`$assetName`"))
        }
    }

    @Test
    fun `every README links the page of the latest release - the short one in both languages`() {
        for (path in ReadmeFiles.detailed) {
            assertTrue("$path verlinkt $latestPage nicht", text(path).contains("]($latestPage)"))
        }
        val short = text(ReadmeFiles.SHORT)
        assertTrue("Der englische Teil von README.md verlinkt $latestPage nicht", short.substringBefore("\n## Deutsch\n").contains("]($latestPage)"))
        assertTrue("Der deutsche Teil von README.md verlinkt $latestPage nicht", short.substringAfter("\n## Deutsch\n").contains("]($latestPage)"))
    }

    @Test
    fun `the detailed READMEs give the stable links of both files and no link to one version`() {
        for (path in ReadmeFiles.detailed) {
            val readme = text(path)
            assertTrue("$path nennt $apkLink nicht", readme.contains(apkLink))
            assertTrue("$path nennt $checksumLink nicht", readme.contains(checksumLink))
        }
        for (path in ReadmeFiles.all) {
            // Ein Link auf eine Version (…/releases/download/v1.45.1/…) veraltet mit dem naechsten Release; die stabilen Links zeigen auf latest.
            assertFalse("$path verlinkt eine feste Version statt latest", text(path).contains("/releases/download/"))
        }
    }

    @Test
    fun `the short README points to the download section of the matching language`() {
        val short = text(ReadmeFiles.SHORT)
        val english = short.substringBefore("\n## Deutsch\n")
        val german = short.substringAfter("\n## Deutsch\n")
        assertTrue("Der englische Teil von README.md verweist nicht auf #download", english.contains("](docs/README.en.md#download)"))
        assertTrue("Der deutsche Teil von README.md verweist nicht auf #herunterladen", german.contains("](docs/README.de.md#herunterladen)"))
    }

    @Test
    fun `the links name the repository that the issue forms and the security advisory use`() {
        assertTrue(".github/ISSUE_TEMPLATE/config.yml nennt ein anderes Repository als die Download-Links", text(".github/ISSUE_TEMPLATE/config.yml").contains(repository))
    }

    // ── Pruefen ───────────────────────────────────────────────────────────

    @Test
    fun `the READMEs show the check command that the release text shows`() {
        // release-lib.sh schreibt den Befehl in den Text des GitHub-Releases; die READMEs zeigen ihn gleich.
        assertTrue("release-lib.sh nennt den Befehl nicht mehr", text("scripts/release-lib.sh").contains("shasum -a 256 -c \$RELEASE_ASSET_NAME.sha256"))
        for (path in ReadmeFiles.detailed) {
            assertTrue("$path zeigt den Pruefbefehl nicht", text(path).lines().any { it.trim() == "shasum -a 256 -c $assetName.sha256" })
        }
    }

    @Test
    fun `the detailed READMEs name the certificate fingerprint that the pin holds`() {
        val pin = text("scripts/release-cert.sha256").trim().lowercase()
        assertTrue("scripts/release-cert.sha256 ist kein SHA-256-Fingerabdruck: '$pin'", Regex("[0-9a-f]{64}").matches(pin))
        for (path in ReadmeFiles.detailed) {
            assertTrue("$path nennt den Fingerabdruck aus scripts/release-cert.sha256 nicht (Schluessel gewechselt?)", text(path).contains("`$pin`"))
        }
    }

    // ── Veroeffentlichen ──────────────────────────────────────────────────

    @Test
    fun `the detailed READMEs describe the publish step with its confirmation and the dry run`() {
        val script = text("scripts/release.sh")
        for (word in listOf("--publish", "--dry-run", "RELEASE_PUBLISH_CONFIRM")) {
            assertTrue("scripts/release.sh nennt $word nicht mehr", script.contains(word))
            for (path in ReadmeFiles.detailed) assertTrue("$path beschreibt $word nicht", text(path).contains(word))
        }
        for (path in ReadmeFiles.detailed) {
            assertTrue("$path zeigt den Trockenlauf nicht als Befehl", text(path).lines().any { it.trim() == "scripts/release.sh --publish --dry-run" })
            assertTrue("$path nennt die Pruefsumme-Datei nicht", text(path).contains("`$assetName.sha256`"))
        }
    }
}
