package de.adv990de.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Erklaerung vor dem Hintergrund-Standort muss VOR der System-Anfrage stehen (Play-Richtlinie „Prominent Disclosure“): die Anfrage
 * `ACCESS_BACKGROUND_LOCATION` darf nirgends direkt ausgeloest werden, sondern nur als Antwort „Weiter“ auf den Dialog. Die System-Anfrage ist im Geraete-Test nicht
 * bedienbar (sie erscheint vor der Test-App, und die Berechtigung zu entziehen beendet den Testprozess); dass `SettingsScreen` den Dialog zeigt und der Weg zur
 * Anfrage nur ueber ihn fuehrt, haelt deshalb der Quelltext fest. Wer den Ablauf aendert, aendert diese Entscheidung bewusst (und diesen Test).
 */
class BackgroundDisclosureWiringSourceTest {

    private val main = File(projectRoot, "app/src/main/java/de/adv990de/obd2")
    private val settingsScreen = File(main, "ui/settings/SettingsScreen.kt").readText()

    /** Der Rumpf der ersten Funktion, deren Kopf zu [signature] passt (Klammern gezaehlt); Kommentare sind schon entfernt. */
    private fun bodyOf(source: String, signature: Regex): String {
        val start = signature.find(source)?.range?.last ?: error("Funktion nicht gefunden: $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("Klammern der Funktion $signature sind nicht geschlossen")
    }

    private fun withoutComments(source: String) = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `the system request for background location is launched in exactly one place in the whole app`() {
        val launches = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""launch\(\s*Manifest\.permission\.ACCESS_BACKGROUND_LOCATION""").containsMatchIn(withoutComments(it.readText())) }
            .map { it.name }.toList()
        assertEquals("die Anfrage steht nur in SettingsScreen: $launches", listOf("SettingsScreen.kt"), launches)
    }

    @Test
    fun `that one launch sits behind the answer continue and nowhere else`() {
        val screen = withoutComments(settingsScreen)
        val answer = bodyOf(screen, Regex("""fun answerBackgroundDisclosure\([^)]*\)\s*\{"""))
        assertTrue("answerBackgroundDisclosure muss die Antwort ueber backgroundDisclosureOutcome auswerten", answer.contains("backgroundDisclosureOutcome("))
        assertTrue(
            "die Anfrage darf nur laufen, wenn outcome.requestPermission gilt",
            Regex("""if\s*\(\s*outcome\.requestPermission\s*\)\s*\{?\s*backgroundLocationLauncher\.launch\(""").containsMatchIn(answer),
        )
        assertTrue("jede Antwort merkt sich, dass gefragt wurde", Regex("""if\s*\(\s*outcome\.markPrompted\s*\)\s*displayVm\.setBackgroundLocationPrompted\(true\)""").containsMatchIn(answer))
        assertEquals("genau ein launch im Bildschirm", 1, Regex("""backgroundLocationLauncher\.launch\(""").findAll(screen).count())
    }

    @Test
    fun `the escalation only shows the explanation, it never launches the request itself`() {
        val screen = withoutComments(settingsScreen)
        val escalate = bodyOf(screen, Regex("""fun escalateToBackgroundLocationIfNeeded\(\)\s*\{"""))
        assertTrue("escalate… zeigt die Erklaerung", escalate.contains("showBackgroundDisclosure = true"))
        assertFalse("escalate… darf die Anfrage nicht selbst starten", escalate.contains(".launch("))
        assertFalse("escalate… darf die Frage nicht schon als gestellt merken (das geschieht mit der Antwort)", escalate.contains("setBackgroundLocationPrompted"))
    }

    @Test
    fun `the flag survives a rotation and the dialog is wired to both answers`() {
        val screen = withoutComments(settingsScreen)
        assertTrue(
            "showBackgroundDisclosure muss rememberSaveable sein (eine Drehung darf die Erklaerung nicht verschlucken)",
            Regex("""var showBackgroundDisclosure by rememberSaveable""").containsMatchIn(screen),
        )
        val shown = Regex("""if\s*\(\s*showBackgroundDisclosure\s*\)\s*\{?\s*BackgroundLocationDisclosureDialog\(([\s\S]*?)\n\s*\)""").find(screen)
        assertTrue("SettingsScreen muss den Dialog zeigen, solange showBackgroundDisclosure gilt", shown != null)
        val args = shown!!.groupValues[1]
        assertTrue("onContinue ist die Antwort CONTINUE", Regex("""onContinue\s*=\s*\{\s*answerBackgroundDisclosure\(BackgroundDisclosureChoice\.CONTINUE\)""").containsMatchIn(args))
        assertTrue("onNotNow ist die Antwort NOT_NOW", Regex("""onNotNow\s*=\s*\{\s*answerBackgroundDisclosure\(BackgroundDisclosureChoice\.NOT_NOW\)""").containsMatchIn(args))
    }

    @Test
    fun `the dialog treats dismissing as not now and never as consent`() {
        val dialog = withoutComments(File(main, "ui/settings/BackgroundLocationDisclosure.kt").readText())
        assertTrue("onDismissRequest muss onNotNow sein", Regex("""onDismissRequest\s*=\s*onNotNow""").containsMatchIn(dialog))
        assertFalse("onDismissRequest darf nie onContinue sein", Regex("""onDismissRequest\s*=\s*onContinue""").containsMatchIn(dialog))
        assertTrue("ProvideMinTouchTarget in den Knopf-Slots (Fenster-Komponenten erben die 56-dp-Regel nicht)", dialog.contains("ProvideMinTouchTarget"))
    }
}
