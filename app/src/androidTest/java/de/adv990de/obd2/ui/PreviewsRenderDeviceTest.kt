package de.adv990de.obd2.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier

/**
 * Rendert jede `@Preview`-Funktion der App auf dem Geraet — bisher wurden sie nur kompiliert, ob eine beim Komponieren wirft
 * oder nichts zeichnet, sah niemand (QS-Rest, v1.25.2).
 *
 * **Gefunden werden sie nach Form**, nicht nach der Annotation (die ist BINARY, zur Laufzeit nicht lesbar): eine statische Methode
 * `Preview…(Composer, int)` in einer Datei-Klasse (`…Kt`) der App — das ist eine parameterlose `@Composable`-Funktion. Dass jede
 * `@Preview` diese Form hat, erzwingt `CodeHygieneSourceTest`; er prueft auch, dass [PREVIEW_COUNT] die Zahl der Quelltexte ist.
 * Die Zahl hier steht also fuer „alle“: findet das Geraet weniger, fehlt eine Klasse im APK, findet es mehr, ist es eine Methode,
 * die nur so heisst.
 *
 * Jede Vorschau laeuft in einer eigenen Test-Umgebung (`runAndroidComposeUiTest`) — eine Ausnahme beim Komponieren zerstoert die
 * Komposition, die naechste soll trotzdem laufen; alle Fehler stehen am Ende zusammen in der Meldung. Die Uhr steht (`autoAdvance =
 * false`): Vorschauen mit endloser Animation (Verbindungspunkt, Blinken) kaemen sonst nie zur Ruhe.
 *
 * Mit dem Instrumentation-Argument `previewShots=true` schreibt der Test zusaetzlich je Vorschau ein Bild nach
 * `files/preview_shots/` der Debug-App (zum Ansehen: `adb exec-out run-as de.adv990de.obd2.debug cat files/preview_shots/<Name>.png`).
 */
@OptIn(ExperimentalTestApi::class, InternalComposeApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewsRenderDeviceTest {

    private data class PreviewRef(val className: String, val methodName: String) {
        val label: String get() = className.substringAfterLast('.') + "." + methodName
    }

    @Test
    fun everyPreview_composes_andDrawsSomething() {
        val previews = discoverPreviews()
        assertEquals(
            "Das Geraet fand ${previews.size} Vorschauen, erwartet $PREVIEW_COUNT (s. KDoc: PREVIEW_COUNT = Zahl der Quelltexte). " +
                "Gefunden: ${previews.map { it.label }}",
            PREVIEW_COUNT, previews.size,
        )

        val shotsDir = shotsDirIfRequested()
        val failures = mutableListOf<String>()
        for (preview in previews) {
            try {
                renderOnce(preview, shotsDir)
            } catch (t: Throwable) {
                // Die Ausnahme der Vorschau steckt in einer InvocationTargetException: die Ursache zeigen, nicht den Umschlag.
                val cause = generateSequence(t) { it.cause }.last()
                failures += "${preview.label}: ${cause::class.simpleName}: ${cause.message?.lineSequence()?.firstOrNull()}"
            }
        }
        assertTrue("${failures.size} von ${previews.size} Vorschauen scheitern:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun renderOnce(preview: PreviewRef, shotsDir: File?) {
        val method = Class.forName(preview.className).getDeclaredComposableMethod(preview.methodName)
            .also { it.asMethod().isAccessible = true } // die Vorschauen sind `private`
        runAndroidComposeUiTest<ComponentActivity> {
            mainClock.autoAdvance = false
            setContent { method.invoke(currentComposer, null) }
            // Zwei Frames: Komposition, dann Layout und Zeichnen.
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()

            // Ein Fenster-Composable (Dialog) zeichnet in ein eigenes Fenster: die Wurzel der Activity bleibt dort leer, der Inhalt liegt
            // in einer zweiten Wurzel.
            val roots = onAllNodes(isRoot())
            if (preview.methodName in WINDOW_PREVIEWS) {
                assertTrue("das Dialogfenster der Vorschau fehlt", roots.fetchSemanticsNodes().any { it.children.isNotEmpty() })
            } else {
                val size = onRoot().fetchSemanticsNode().size
                assertTrue("die Vorschau zeichnet nichts (Groesse $size)", size.width > 0 && size.height > 0)
            }
            // Das Bild eines Dialogs zeigt nur die leere Wurzel der Activity: ohne Aussage, also nicht geschrieben.
            if (shotsDir != null && preview.methodName !in WINDOW_PREVIEWS) {
                shotsDir.mkdirs()
                File(shotsDir, "${preview.methodName}.png").outputStream().use { out ->
                    roots.onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // DexFile ist die einzige Art, die Klassen des eigenen APK aufzuzaehlen; fuer die eigene APK-Datei erlaubt.
    private fun discoverPreviews(): List<PreviewRef> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = context.classLoader
        val dexFile = DexFile(context.packageCodePath)
        try {
            return dexFile.entries().toList()
                .filter { it.startsWith("de.adv990de.obd2.") && it.endsWith("Kt") }
                .flatMap { className ->
                    val clazz = try { Class.forName(className, false, loader) } catch (_: Throwable) { return@flatMap emptyList() }
                    clazz.declaredMethods
                        .filter { m ->
                            Modifier.isStatic(m.modifiers) && m.name.startsWith("Preview") &&
                                m.parameterTypes.contentEquals(arrayOf(Composer::class.java, Int::class.javaPrimitiveType))
                        }
                        .map { PreviewRef(className, it.name) }
                }
                .sortedBy { it.label }
        } finally {
            dexFile.close()
        }
    }

    private fun shotsDirIfRequested(): File? {
        if (InstrumentationRegistry.getArguments().getString("previewShots") != "true") return null
        return File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "preview_shots")
    }

    private companion object {
        /** Die Zahl der `@Preview`-Funktionen in `app/src/main` — `CodeHygieneSourceTest` haelt sie gleich. */
        const val PREVIEW_COUNT = 142

        /** Vorschauen von Fenster-Composables (`AlertDialog`): ihre Wurzel in der Activity ist leer, der Inhalt liegt im Dialog-Fenster. */
        val WINDOW_PREVIEWS = setOf(
            "PreviewExitConfirmationDialogDark", "PreviewExitConfirmationDialogLight",
            "PreviewConfirmDeleteDialogLight", "PreviewConfirmDeleteDialogDark",
            "PreviewFuelResetDialogDark", "PreviewFuelResetDialogLight",
            "PreviewFuelRangeDialogDark", "PreviewFuelRangeDialogLight",
            "PreviewFuelReserveDialogDark", "PreviewFuelReserveDialogLight",
            "PreviewFuelCapacityDialogDark", "PreviewFuelCapacityDialogLight",
            "PreviewSessionDetailDialogLight", "PreviewSessionDetailDialogDark",
            "PreviewLogbookWarningsDialogLight", "PreviewLogbookGaugeDialogDark",
            "PreviewLogbookGaugeCreditDialogDark", "PreviewLogbookGaugeCreditDialogLight",
            "PreviewMaintenanceIntervalDialogLight", "PreviewMaintenanceIntervalDialogDark",
        )
    }
}
