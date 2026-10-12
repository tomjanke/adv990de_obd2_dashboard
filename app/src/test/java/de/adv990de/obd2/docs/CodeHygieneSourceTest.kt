package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regeln, die sich nicht im Typsystem ausdruecken lassen, aber bei jeder Aenderung wieder verletzt werden koennten (QS-Pflege,
 * v1.25.0). Jede Regel nennt in ihrer Meldung, WAS zu tun ist — nicht nur, dass sie verletzt wurde.
 */
class CodeHygieneSourceTest {

    private val mainRoot = File(projectRoot, "app/src/main/java")
    private val mainSources = mainRoot.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun relative(f: File) = f.relativeTo(projectRoot).path

    @Test
    fun `the parsers split on whitespace with one shared regex, not one compiled per response (PRO-15)`() {
        // `"\\s+".toRegex()` kompiliert das Muster bei JEDEM Aufruf neu — die Parser laufen je Messwert, bei der Live-Abfrage
        // mehrfach pro Sekunde. Die Konstante `WHITESPACE` (obd/Whitespace.kt) wird einmal gebaut.
        val perCall = Regex("""["']\\\\s\+["']\s*\.toRegex\(\)|Regex\(\s*["']\\\\s\+["']\s*\)""")
        val offenders = mainSources.filter { it.name != "Whitespace.kt" && perCall.containsMatchIn(it.readText()) }.map(::relative)
        assertTrue("Whitespace-Regex je Aufruf neu gebaut in $offenders - stattdessen `split(WHITESPACE)` aus obd/Whitespace.kt", offenders.isEmpty())
    }

    @Test
    fun `the UI collects state with the lifecycle, not with collectAsState (SCR)`() {
        // `collectAsState` sammelt, solange die Komposition existiert — auch im Hintergrund, wo nichts mehr zu zeichnen ist. Die Oberflaeche
        // nimmt Zustand mit `collectAsStateWithLifecycle` (nur zwischen STARTED und STOPPED). Das ist nur zulaessig, weil KEIN ViewModel dieser
        // App fuer seine Arbeit auf einen Sammler der Oberflaeche angewiesen ist: Polling und Aufzeichnung laufen im viewModelScope bzw. im
        // @ApplicationScope, die Oberflaeche liest nur mit (s. CLAUDE.md "Zustand sammeln"). Wer Arbeit an `WhileSubscribed` haengt,
        // verlaesst sich auf einen Sammler — dort gehoert ein `Eagerly` bzw. ein eigener Scope hin, nicht ein Rueckfall auf `collectAsState`.
        val offenders = mainSources.filter { Regex("""\bcollectAsState\b""").containsMatchIn(it.readText().lines().filterNot { line -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }.joinToString("\n")) }.map(::relative)
        assertTrue("collectAsState in $offenders - stattdessen collectAsStateWithLifecycle (androidx.lifecycle.compose)", offenders.isEmpty())
    }

    private val mainPackageRoot = File(mainRoot, "de/adv990de/obd2")

    /**
     * Dateien mit Composables, die bewusst KEINE `@Preview` haben — mit Grund. Wer hier etwas ergaenzt, schreibt auf, warum eine Vorschau
     * nichts zeigen koennte; eine Datei, die inzwischen eine Vorschau hat, muss wieder raus (der Test prueft das).
     */
    private val previewExempt: Map<String, String> = mapOf(
        "ui/UiText.kt" to "kein sichtbares Composable: loest nur Text auf (UiText.asString)",
        "ui/components/UndoSnackbarEffect.kt" to "ein Effekt ohne eigene Darstellung, er zeigt in den Snackbar-Host des Aufrufers",
        "ui/dashboard/DashboardScreen.kt" to "braucht hiltViewModel(); die Bausteine (RpmBar, Kacheln, Banner) haben Vorschauen in DashboardComponents.kt und SlotComponents.kt",
        "ui/freezeframe/FreezeFrameScreen.kt" to "braucht hiltViewModel() und hat keinen zustandslosen Teil",
        "ui/licenses/LicensesScreen.kt" to "liest die Lizenztexte aus den Assets der App, die eine Vorschau nicht kennt",
        "ui/loggingconfig/LoggingConfigScreen.kt" to "braucht hiltViewModel() und hat keinen zustandslosen Teil",
        "ui/navigation/AppNavHost.kt" to "der Navigationsgraph: jedes Ziel braucht hiltViewModel()",
        "ui/permission/LocationPermissionState.kt" to "kein sichtbares Composable: der Zustand der Standortberechtigung (remember)",
        "ui/settings/SettingsScreen.kt" to "braucht hiltViewModel(); die Geraetezeile (DeviceRow.kt) und die Zeilen (SettingsComponents.kt) haben Vorschauen",
        "ui/theme/AppTheme.kt" to "der Rahmen um die App (Theme und Mindestgroesse der Touch-Ziele), nichts Eigenes zu zeigen",
        "ui/theme/PreviewSupport.kt" to "der Rahmen der Vorschauen selbst",
        "ui/theme/SystemBars.kt" to "ein Effekt auf die Statusleiste des Fensters, ohne eigene Darstellung",
        "ui/vehicleinfo/VehicleInfoScreen.kt" to "braucht hiltViewModel() und hat keinen zustandslosen Teil",
    )

    private fun hasPreview(f: File) = Regex("""(?m)^\s*@Preview\b""").containsMatchIn(f.readText())

    @Test
    fun `every file with composables has a preview or a written reason not to (UI rule 3 of "Bei UI-Aenderungen IMMER")`() {
        val composableFiles = mainSources.filter { it.readText().contains("@Composable") }
        val missing = composableFiles
            .filter { !hasPreview(it) && it.relativeTo(mainPackageRoot).path !in previewExempt }
            .map { it.relativeTo(mainPackageRoot).path }
        assertTrue(
            "Composables ohne @Preview in $missing - eine Vorschau fuer die zustandslosen Teile (Rahmen: PreviewTheme) oder ein Eintrag mit Grund in previewExempt",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no preview exemption is stale`() {
        val stale = previewExempt.keys.filter { path ->
            val file = File(mainPackageRoot, path)
            !file.isFile || hasPreview(file)
        }
        assertTrue("Ausnahmen, die nicht mehr gelten (Datei fehlt oder hat eine Vorschau): $stale", stale.isEmpty())
        assertTrue("jede Ausnahme nennt einen Grund", previewExempt.values.all { it.isNotBlank() })
    }

    @Test
    fun `no test class has the same name in test and androidTest`() {
        // Sieben Klassen hiessen auf dem JVM und auf dem Geraet gleich (SettingsScreenTest, SparklineTest ...): in Berichten, im Filter
        // `--tests` und in der IDE-Suche nicht zu unterscheiden. Was ein Geraet braucht, endet auf `DeviceTest` (wie SessionRepositoryDeviceTest).
        fun names(dir: String) = File(projectRoot, dir).walkTopDown().filter { it.extension == "kt" }.map { it.nameWithoutExtension }.toSet()
        val clashes = names("app/src/test/java").intersect(names("app/src/androidTest/java"))
        assertTrue("gleiche Namen in test und androidTest: $clashes - die Geraete-Klasse bekommt das Suffix DeviceTest", clashes.isEmpty())
    }
}
