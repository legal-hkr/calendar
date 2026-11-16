package sh.hnet.calendar.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.Scope

/** A delete the user asked for: the event, and of a repeating one which occurrences, counted from the one starting at [begin]. */
data class EventDelete(val eventId: Long, val begin: Long, val scope: Scope) {
    /** Whether [occurrence] is among what this takes away; an occurrence changed on its own belongs to its series. */
    fun hides(occurrence: Occurrence): Boolean = (occurrence.eventId == eventId || occurrence.originalId == eventId) && when (scope) {
        Scope.ALL -> true
        Scope.ONLY_THIS -> occurrence.begin == begin
        Scope.FOLLOWING -> occurrence.begin >= begin
    }
}

/**
 * Deleting with Undo (SPEC §2.5), as in the family's other apps: the event leaves every view at
 * once, but the provider is told only when the Undo snackbar has gone – or the app has left the
 * screen, which the owner counts as the user's decision too. Until then Undo only shows it again.
 * One delete waits at a time: the next one carries out the one before.
 */
class DeleteQueue(private val scope: CoroutineScope, private val writer: EventWriter) {
    private val _waiting = MutableStateFlow<EventDelete?>(null)
    /** The delete that can still be undone. */
    val waiting: StateFlow<EventDelete?> = _waiting

    private val _hidden = MutableStateFlow<List<EventDelete>>(emptyList())
    /** What the views leave out: the waiting delete, and those being carried out until the views have read the provider again. */
    val hidden: StateFlow<List<EventDelete>> = _hidden

    fun hides(occurrence: Occurrence): Boolean = _hidden.value.any { it.hides(occurrence) }

    fun delete(delete: EventDelete) {
        commit()
        _hidden.update { it + delete }
        _waiting.value = delete
    }

    fun undo() {
        val delete = _waiting.value ?: return
        _waiting.value = null
        _hidden.update { it - delete }
    }

    /** Carries the waiting delete out. Safe to call when nothing waits. */
    fun commit() {
        val delete = _waiting.value ?: return
        _waiting.value = null
        scope.launch {
            try {
                writer.delete(delete.eventId, delete.begin, delete.scope)
                // The views read the provider again a moment after it changed; until then the event stays out of sight
                delay(LINGER)
            } catch (_: Exception) {
                // Access gone or the provider refused: the event shows again, as it is still there
            } finally {
                _hidden.update { it - delete }
            }
        }
    }

    private companion object {
        const val LINGER = 1_000L
    }
}
