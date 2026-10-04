package de.adv990de.obd2.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UndoableDeletion] with virtual time: the 10 s window, undo, the "only the latest batch is
 * undoable" rule, hiding keys until the commit has really finished, and failure handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UndoableDeletionTest {

    private class Recorder {
        val committed = mutableListOf<Set<String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        suspend fun commit(keys: Set<String>) {
            gate?.await()
            failWith?.let { throw it }
            committed += keys
        }
    }

    private fun TestScope.deletion(
        rec: Recorder,
        settleMillis: Long = 0,
        onFail: (Throwable) -> Unit = {},
    ) = UndoableDeletion<String>(
        scope = backgroundScope,
        windowMillis = 10_000,
        settleMillis = settleMillis,
        onCommitFailed = onFail,
    ) { rec.commit(it) }

    @Test
    fun `schedule hides the keys at once and does not commit inside the window`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a", "b"))

        assertEquals(setOf("a", "b"), d.pending.value)
        assertNotNull(d.openBatchId.value)
        advanceTimeBy(9_999); runCurrent()
        assertTrue(rec.committed.isEmpty())
        assertEquals(setOf("a", "b"), d.pending.value)
    }

    @Test
    fun `it commits exactly the scheduled keys once the window has passed`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()

        assertEquals(listOf(setOf("a")), rec.committed)
        assertTrue(d.pending.value.isEmpty())
        assertNull(d.openBatchId.value)
    }

    @Test
    fun `keys stay pending while the commit is still running so nothing flickers back`() = runTest {
        val rec = Recorder().apply { gate = CompletableDeferred() }
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()

        assertTrue(rec.committed.isEmpty())                // blocked on the gate
        assertEquals(setOf("a"), d.pending.value)          // still hidden
        assertNull(d.openBatchId.value)                    // but no longer undoable

        rec.gate!!.complete(Unit); runCurrent()

        assertEquals(listOf(setOf("a")), rec.committed)
        assertTrue(d.pending.value.isEmpty())
    }

    @Test
    fun `undo inside the window brings the keys back and never commits`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(5_000); runCurrent()
        d.undo()

        assertTrue(d.pending.value.isEmpty())
        assertNull(d.openBatchId.value)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(rec.committed.isEmpty())
    }

    @Test
    fun `undo after the window has passed does nothing`() = runTest {
        val rec = Recorder().apply { gate = CompletableDeferred() }
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()
        d.undo()                                           // too late: the commit already started

        assertEquals(setOf("a"), d.pending.value)
        rec.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf(setOf("a")), rec.committed)
    }

    @Test
    fun `scheduling a second batch commits the first at once and only the second stays undoable`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(1_000); runCurrent()
        d.schedule(setOf("b")); runCurrent()

        assertEquals(listOf(setOf("a")), rec.committed)    // the first one is final now
        assertEquals(setOf("b"), d.pending.value)

        d.undo()                                           // reverts only "b"
        assertTrue(d.pending.value.isEmpty())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(setOf("a")), rec.committed)
    }

    @Test
    fun `pending is the union of the open batch and a commit that is still running`() = runTest {
        val rec = Recorder().apply { gate = CompletableDeferred() }
        val d = deletion(rec)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()                // "a" is committing (blocked)
        d.schedule(setOf("b"))

        assertEquals(setOf("a", "b"), d.pending.value)
    }

    @Test
    fun `flush commits the open batch at once and the timer does not commit it a second time`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a"))
        d.flush(); runCurrent()

        assertEquals(listOf(setOf("a")), rec.committed)
        assertNull(d.openBatchId.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, rec.committed.size)
    }

    @Test
    fun `flush without an open batch does nothing`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.flush(); runCurrent()

        assertTrue(rec.committed.isEmpty())
        assertTrue(d.pending.value.isEmpty())
    }

    @Test
    fun `an empty key set is ignored`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(emptySet())
        advanceTimeBy(60_000); runCurrent()

        assertNull(d.openBatchId.value)
        assertTrue(rec.committed.isEmpty())
    }

    @Test
    fun `a failing commit un-hides the keys, reports the error and later batches still work`() = runTest {
        val rec = Recorder().apply { failWith = IllegalStateException("db down") }
        var failure: Throwable? = null
        val d = deletion(rec, onFail = { failure = it })

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()

        assertTrue(d.pending.value.isEmpty())              // the rows are still in the database
        assertEquals("db down", failure?.message)

        rec.failWith = null
        d.schedule(setOf("b"))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(setOf("b")), rec.committed)
    }

    @Test
    fun `a stale timer of an undone batch never commits a later batch early`() = runTest {
        val rec = Recorder()
        val d = deletion(rec)

        d.schedule(setOf("a"))                             // timer would fire at t = 10 s
        advanceTimeBy(1_000); runCurrent()
        d.undo()
        advanceTimeBy(1_000); runCurrent()
        d.schedule(setOf("b"))                             // its own window ends at t = 12 s

        advanceTimeBy(9_000); runCurrent()                 // t = 11 s: a's old deadline has passed
        assertTrue("b must still be undoable", rec.committed.isEmpty())
        assertEquals(setOf("b"), d.pending.value)

        advanceTimeBy(1_000); runCurrent()                 // t = 12 s
        assertEquals(listOf(setOf("b")), rec.committed)
    }

    @Test
    fun `after a successful commit the keys stay hidden for the settle time`() = runTest {
        // The list is fed by a database Flow that re-emits asynchronously after the delete — releasing
        // the keys the moment the DAO call returns could flash the old rows back for a frame.
        val rec = Recorder()
        val d = deletion(rec, settleMillis = 300)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(setOf("a")), rec.committed)
        assertEquals(setOf("a"), d.pending.value)           // committed, but still hidden

        advanceTimeBy(299); runCurrent()
        assertEquals(setOf("a"), d.pending.value)
        advanceTimeBy(1); runCurrent()
        assertTrue(d.pending.value.isEmpty())
    }

    @Test
    fun `a failed commit releases the keys at once without waiting for the settle time`() = runTest {
        val rec = Recorder().apply { failWith = IllegalStateException("db down") }
        val d = deletion(rec, settleMillis = 300)

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()

        assertTrue(d.pending.value.isEmpty())
    }

    @Test
    fun `every schedule opens a new batch id`() = runTest {
        val d = deletion(Recorder())

        d.schedule(setOf("a"))
        val first = d.openBatchId.value
        d.schedule(setOf("b"))
        val second = d.openBatchId.value

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `a cancelled scope is not reported as a commit failure`() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val rec = Recorder().apply { gate = CompletableDeferred() }
        var failed = false
        val d = UndoableDeletion<String>(scope, windowMillis = 10_000, settleMillis = 0, onCommitFailed = { failed = true }) { rec.commit(it) }

        d.schedule(setOf("a"))
        advanceTimeBy(10_000); runCurrent()                // commit is running, blocked on the gate
        job.cancel(); runCurrent()

        assertFalse(failed)
        assertTrue(d.pending.value.isEmpty())              // not stuck hidden after cancellation
    }
}
