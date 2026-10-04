package de.adv990de.obd2.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How long deleted keys stay hidden after the commit returned: a database-backed list re-emits
 *  asynchronously, so releasing them at once could flash the deleted rows back for a frame. */
const val DELETE_SETTLE_MS = 300L

/** Length of the undo window — the same 10 s as `SnackbarDuration.Long`, so the "Rückgängig"
 *  snackbar is visible exactly as long as the deletion can still be taken back. */
const val UNDO_WINDOW_MS = 10_000L

/**
 * The shared "local delete" contract of the list screens (App-Logs, later Verlauf and the fault
 * history): hide now, delete for real after a short window, let the user take it back in between.
 * Pure Kotlin + coroutines, so every rule below is JVM-testable with virtual time.
 *
 * - [schedule] hides [keys] at once ([pending]) and starts the window. Nothing is deleted yet.
 * - [undo] inside the window brings the keys back and cancels the deletion.
 * - When the window ends the keys are handed to [commit] (the real deletion). They stay in
 *   [pending] until [commit] has finished AND [settleMillis] more have passed, so a list that is
 *   fed by the database cannot show them again for a moment between "undo no longer possible" and
 *   "row really gone" (Room re-emits asynchronously after the delete). A failed commit releases
 *   them at once.
 * - Only the LATEST batch is undoable: scheduling a new batch commits the previous one at once
 *   (the same rule as every "undo" snackbar — a new action replaces the old offer).
 * - [flush] commits the open batch right now; call it when the screen goes away, because the undo
 *   UI is gone with it.
 *
 * The helper owns the ONE clock. The snackbar is only a view of [openBatchId] (shown without its
 * own timeout, dismissed when the batch closes), so the offer on screen and the real deletion can
 * never drift apart — not even with accessibility timeouts that stretch a snackbar.
 *
 * [scope] must outlive the screen (the app-wide `@ApplicationScope`), otherwise leaving the screen
 * would cancel a running commit. [onCommitFailed] is called when [commit] throws: the keys are
 * un-hidden again (the rows are still in the database) and later batches work as usual. A
 * cancelled [scope] is not a failure. [onCommitFailed] runs on [scope]'s dispatcher.
 */
class UndoableDeletion<K>(
    private val scope: CoroutineScope,
    private val windowMillis: Long = UNDO_WINDOW_MS,
    private val settleMillis: Long = DELETE_SETTLE_MS,
    private val onCommitFailed: (Throwable) -> Unit = {},
    private val commit: suspend (Set<K>) -> Unit,
) {
    private class Batch<K>(val id: Long, val keys: Set<K>, var timer: Job? = null)

    private val lock = Any()
    private var nextId = 1L
    private var open: Batch<K>? = null
    private val committing = mutableListOf<Set<K>>()

    private val _pending = MutableStateFlow<Set<K>>(emptySet())

    /** Keys the UI must hide: the open (undoable) batch plus batches whose commit is still running. */
    val pending: StateFlow<Set<K>> = _pending.asStateFlow()

    private val _openBatchId = MutableStateFlow<Long?>(null)

    /** Id of the undoable batch, null when there is none. Changes with every [schedule], so an
     *  effect keyed on it restarts per batch. */
    val openBatchId: StateFlow<Long?> = _openBatchId.asStateFlow()

    /** Hides [keys] now and commits them after the window unless [undo] is called. Empty: no-op. */
    fun schedule(keys: Set<K>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            open?.let { previous ->
                previous.timer?.cancel()
                beginCommit(previous)
            }
            val batch = Batch(nextId++, keys.toSet())
            batch.timer = scope.launch {
                delay(windowMillis)
                expire(batch.id)
            }
            open = batch
            publish()
        }
    }

    /** Takes the open batch back. Too late (window over, commit started): does nothing. */
    fun undo() {
        synchronized(lock) {
            val batch = open ?: return
            batch.timer?.cancel()
            open = null
            publish()
        }
    }

    /** Commits the open batch right now, if there is one. */
    fun flush() {
        synchronized(lock) {
            val batch = open ?: return
            batch.timer?.cancel()
            beginCommit(batch)
        }
    }

    private fun expire(batchId: Long) {
        synchronized(lock) {
            val batch = open ?: return
            if (batch.id != batchId) return
            beginCommit(batch)
        }
    }

    /** Must be called with [lock] held. Moves the open batch to "committing" and runs the commit in
     *  its own coroutine, so cancelling a timer can never cancel a commit that already started. */
    private fun beginCommit(batch: Batch<K>) {
        open = null
        committing += batch.keys
        publish()
        scope.launch {
            try {
                try {
                    commit(batch.keys)
                    if (settleMillis > 0) delay(settleMillis)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    onCommitFailed(t)
                }
            } finally {
                synchronized(lock) {
                    committing.remove(batch.keys)
                    publish()
                }
            }
        }
    }

    /** Must be called with [lock] held. */
    private fun publish() {
        _pending.value = buildSet {
            open?.let { addAll(it.keys) }
            committing.forEach { addAll(it) }
        }
        _openBatchId.value = open?.id
    }
}
