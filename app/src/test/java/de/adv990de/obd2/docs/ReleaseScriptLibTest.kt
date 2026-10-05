package de.adv990de.obd2.docs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * B31: Die Pruefungen, die `scripts/release.sh` vor dem Signieren macht — ob Keystore und Gradle-Properties nur der
 * Eigentuemer lesen darf und ob das APK mit dem erwarteten Zertifikat signiert ist — liegen in `scripts/release-lib.sh`
 * und werden hier ECHT ausgefuehrt (bash, temporaere Dateien), nicht nur als Text geprueft. Ein falscher Schluessel
 * (z. B. nach dem Umzug des Keystores) wuerde ein Update erzeugen, das sich ueber die installierte App nicht einspielen laesst.
 */
class ReleaseScriptLibTest {

    private val projectRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")

    private lateinit var tmp: File

    @Before fun setup() {
        assumeTrue("bash fehlt", File("/bin/bash").exists())
        tmp = Files.createTempDirectory("release-lib-test").toFile()
    }

    @After fun teardown() { if (::tmp.isInitialized) tmp.deleteRecursively() }

    private class Result(val exit: Int, val out: String)

    /** Fuehrt [script] in bash aus, nachdem `scripts/release-lib.sh` eingelesen wurde. */
    private fun bash(script: String, env: Map<String, String> = emptyMap(), stdin: String? = null): Result {
        val pb = ProcessBuilder("bash", "-c", "source '${File(projectRoot, "scripts/release-lib.sh")}'\n$script")
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("bash haengt", p.waitFor(30, TimeUnit.SECONDS))
        return Result(p.exitValue(), out.trim())
    }

    private fun fileWithMode(name: String, mode: String, content: String = "x"): File =
        File(tmp, name).also {
            it.writeText(content)
            Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString(mode))
        }

    // ── is_private_file ───────────────────────────────────────────────────

    @Test
    fun `a file only the owner can access is private`() {
        for (mode in listOf("rw-------", "r--------", "rwx------")) {
            val f = fileWithMode("secret-$mode", mode)
            assertEquals("Modus $mode", 0, bash("is_private_file '$f'").exit)
        }
    }

    @Test
    fun `a file readable by group or others is not private - that was the finding (mode 644)`() {
        for (mode in listOf("rw-r--r--", "rw-r-----", "rw----r--", "rw-rw----", "rwxrwxrwx")) {
            val f = fileWithMode("open-$mode", mode)
            assertFalse("Modus $mode darf nicht als privat gelten", bash("is_private_file '$f'").exit == 0)
        }
    }

    // ── signer_sha256 ─────────────────────────────────────────────────────

    private val apksignerOutput = """
        Signer #1 certificate DN: CN=example, O=example, C=DE
        Signer #1 certificate SHA-256 digest: BE3DDD382CC6989E30F7B0EDBA88CA841CD5BCD13C5CB22FC56518EC4E9A10FA
        Signer #1 certificate SHA-1 digest: 9372cbe058642020100884d25a032e06aec61559
    """.trimIndent()

    @Test
    fun `signer_sha256 takes the SHA-256 digest of the first signer, lower case, not the SHA-1`() {
        val r = bash("signer_sha256", stdin = apksignerOutput)

        assertEquals("be3ddd382cc6989e30f7b0edba88ca841cd5bcd13c5cb22fc56518ec4e9a10fa", r.out)
    }

    @Test
    fun `signer_sha256 yields nothing when apksigner printed no certificate`() {
        assertEquals("", bash("signer_sha256", stdin = "Verifies\nDoes not verify").out)
    }

    // ── signer_matches_pin ────────────────────────────────────────────────

    private val pin = "be3ddd382cc6989e30f7b0edba88ca841cd5bcd13c5cb22fc56518ec4e9a10fa"

    @Test
    fun `the pinned certificate matches - regardless of case and trailing newline in the pin file`() {
        val pinFile = File(tmp, "pin").apply { writeText(pin.uppercase() + "\n") }

        assertEquals(0, bash("signer_matches_pin '$pinFile'", stdin = apksignerOutput).exit)
    }

    @Test
    fun `a different certificate does not match the pin - and the message names both`() {
        val pinFile = File(tmp, "pin").apply { writeText("0".repeat(64) + "\n") }

        val r = bash("signer_matches_pin '$pinFile'", stdin = apksignerOutput)

        assertTrue("Exit-Code ungleich 0, war ${r.exit}", r.exit != 0)
        assertTrue("nennt den tatsaechlichen Fingerabdruck: ${r.out}", pin in r.out)
        assertTrue("nennt den erwarteten: ${r.out}", "0".repeat(64) in r.out)
    }

    @Test
    fun `no certificate in the apksigner output never matches - an empty digest must not equal an empty pin`() {
        val emptyPin = File(tmp, "pin").apply { writeText("\n") }

        assertTrue(bash("signer_matches_pin '$emptyPin'", stdin = "nothing useful").exit != 0)
    }

    // ── gradle_property ───────────────────────────────────────────────────

    @Test
    fun `gradle_property prefers the environment over the properties file`() {
        val props = File(tmp, "gradle.properties").apply { writeText("KEYSTORE_FILE=/from/file\nOTHER=1\n") }

        val r = bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "/from/env"))

        assertEquals("/from/env", r.out)
    }

    @Test
    fun `gradle_property reads the key from the properties file and ignores other keys and comments`() {
        val props = File(tmp, "gradle.properties").apply {
            writeText("# KEYSTORE_FILE=/commented\nKEYSTORE_FILE_BACKUP=/other\nKEYSTORE_FILE=/from/file\n")
        }

        assertEquals("/from/file", bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "")).out)
    }

    @Test
    fun `gradle_property is empty when neither environment nor file define the key`() {
        val props = File(tmp, "gradle.properties").apply { writeText("OTHER=1\n") }

        assertEquals("", bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "")).out)
        assertEquals("", bash("gradle_property KEYSTORE_FILE '${File(tmp, "missing")}'", env = mapOf("KEYSTORE_FILE" to "")).out)
    }

    @Test
    fun `gradle_property never prints a secret value for a key it was not asked for`() {
        val props = File(tmp, "gradle.properties").apply { writeText("KEYSTORE_PASSWORD=geheim123\nKEYSTORE_FILE=/k\n") }

        val r = bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to ""))

        assertFalse("geheim123" in r.out)
    }

    // ── expand_home ───────────────────────────────────────────────────────

    @Test
    fun `expand_home resolves a leading tilde like Gradle's KEYSTORE_FILE does`() {
        assertEquals("/home/x/keys/a.jks", bash("expand_home '~/keys/a.jks'", env = mapOf("HOME" to "/home/x")).out)
        assertEquals("/abs/a.jks", bash("expand_home '/abs/a.jks'", env = mapOf("HOME" to "/home/x")).out)
        assertEquals("rel/a~b.jks", bash("expand_home 'rel/a~b.jks'", env = mapOf("HOME" to "/home/x")).out)
    }
}
