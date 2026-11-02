package de.adv990de.obd2.ui.components

import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExportFeedback], die Rueckmeldung eines Exports an die Oberflaeche — der gemeinsame Teil dreier ViewModels (deren Tests pruefen dasselbe noch einmal durch ihren Export):
 * ein Abbruch ist nie ein Fehler, jeder andere Fehler hat einen sichtbaren Text, ein neuer Versuch loescht den alten Fehler, und „gespeichert“ kommt erst nach dem Export.
 */
class ExportFeedbackTest {

    private val feedback = ExportFeedback()

    // --- Teilen ---

    @Test
    fun `a shared export hands out its uri and has no error`() = runTest {
        val uri = mockk<Uri>()

        feedback.launchShare(this) { uri }
        advanceUntilIdle()

        assertSame(uri, feedback.shareUri.value)
        assertNull(feedback.error.value)
    }

    @Test
    fun `a failing export becomes the message of the exception and hands out nothing`() = runTest {
        feedback.launchShare(this) { throw IllegalStateException("disk full") }
        advanceUntilIdle()

        assertEquals("disk full", feedback.error.value)
        assertNull(feedback.shareUri.value)
    }

    @Test
    fun `an exception without a message still has a visible text - its class name, never empty`() = runTest {
        feedback.launchShare(this) { throw IllegalStateException() }
        advanceUntilIdle()

        assertEquals("IllegalStateException", feedback.error.value)
    }

    @Test
    fun `a cancellation is not an error`() = runTest {
        feedback.launchShare(this) { throw CancellationException("cancelled") }
        advanceUntilIdle()

        assertNull("a cancellation is no error text", feedback.error.value)
        assertNull(feedback.shareUri.value)
    }

    @Test
    fun `a new attempt clears the old error as soon as it starts`() = runTest {
        feedback.launchShare(this) { throw IllegalStateException("first") }
        advanceUntilIdle()
        assertEquals("first", feedback.error.value)

        val release = CompletableDeferred<Uri>()
        feedback.launchShare(this) { release.await() }
        advanceUntilIdle()

        assertNull("the old error is gone while the new export runs", feedback.error.value)
        release.complete(mockk())
        advanceUntilIdle()
        assertNotNull(feedback.shareUri.value)
    }

    // --- Speichern ---

    @Test
    fun `a saved export reports it after the export, once`() = runTest {
        val order = mutableListOf<String>()

        feedback.launchSave(this, onSaved = { order += "saved" }) { order += "exported" }
        advanceUntilIdle()

        assertEquals(listOf("exported", "saved"), order)
        assertNull(feedback.error.value)
    }

    @Test
    fun `a failing save reports no success and shows the error`() = runTest {
        var saved = false

        feedback.launchSave(this, onSaved = { saved = true }) { throw java.io.IOException("The chosen file could not be opened for writing") }
        advanceUntilIdle()

        assertFalse("nothing was saved", saved)
        assertEquals("The chosen file could not be opened for writing", feedback.error.value)
    }

    @Test
    fun `a cancelled save reports no success and no error`() = runTest {
        var saved = false

        feedback.launchSave(this, onSaved = { saved = true }) { throw CancellationException("cancelled") }
        advanceUntilIdle()

        assertFalse(saved)
        assertNull(feedback.error.value)
    }

    // --- quittieren ---

    @Test
    fun `each state is shown once - consuming resets it and only it`() = runTest {
        feedback.launchShare(this) { mockk() }
        advanceUntilIdle()
        feedback.reportNothingToExport()
        feedback.launchShare(this) { throw IllegalStateException("x") }
        advanceUntilIdle()
        // the failing attempt cleared nothing but the error: the first uri is still there until consumed
        assertNotNull(feedback.shareUri.value)
        assertTrue(feedback.nothingToExport.value)
        assertEquals("x", feedback.error.value)

        feedback.consumeShareUri()
        assertNull(feedback.shareUri.value)
        assertTrue(feedback.nothingToExport.value)
        assertEquals("x", feedback.error.value)

        feedback.consumeNothingToExport()
        assertFalse(feedback.nothingToExport.value)
        assertEquals("x", feedback.error.value)

        feedback.consumeError()
        assertNull(feedback.error.value)
    }

    @Test
    fun `nothing to export is reported once and does not start an export or touch the error`() = runTest {
        feedback.reportNothingToExport()

        assertTrue(feedback.nothingToExport.value)
        assertNull(feedback.shareUri.value)
        assertNull(feedback.error.value)
    }
}
