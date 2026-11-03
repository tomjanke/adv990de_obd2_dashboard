package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QS B33 am Quelltext: Sentry startet nicht von selbst (sonst koennte ein Absturz in den ersten Millisekunden gegen die
 * Einstellung des Nutzers gemeldet werden), und das APK traegt weder den Native-Handler noch den Replay-Teil — die App
 * hat keinen eigenen Native-Code, vorher lagen `libsentry.so` und `libsentry-android.so` in drei Architekturen im APK.
 */
class CrashReportingConfigTest {

    private fun read(relative: String) = File(projectRoot, relative).readText()

    @Test
    fun `the manifest does not auto-initialise Sentry`() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(
            "io.sentry.auto-init muss false sein: Der Start haengt am Schalter (CrashReportingController)",
            Regex("""io\.sentry\.auto-init"\s+android:value="false"""").containsMatchIn(manifest),
        )
    }

    @Test
    fun `the app depends on sentry-android-core only - no native handler, no replay`() {
        val catalog = read("gradle/libs.versions.toml")
        assertTrue("sentry-android-core ist im Katalog", catalog.contains("""module = "io.sentry:sentry-android-core""""))
        assertFalse(
            "das Sammelpaket io.sentry:sentry-android zieht NDK und Replay nach",
            Regex("""module = "io\.sentry:sentry-android"""").containsMatchIn(catalog),
        )
    }

    @Test
    fun `the README no longer promises more than the switch delivers`() {
        // Der Schalter heisst in der App "Absturzberichte senden" bzw. "Send crash reports"; jede ausfuehrliche Fassung nennt ihn in ihrer Sprache.
        for ((path, label) in listOf(ReadmeFiles.DE to "Absturzberichte senden", ReadmeFiles.EN to "Send crash reports")) {
            val readme = ReadmeFiles.read(path)
            assertTrue("der Schalter ist in $path dokumentiert", readme.contains(label))
            assertFalse("die alte, ungenaue Aussage in $path", readme.contains("optional, deaktivierbar"))
        }
    }
}
