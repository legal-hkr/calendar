package sh.hnet.calendar.ui.event

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.data.EventWriter
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventDetails
import sh.hnet.calendar.model.EventDraft
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Repetition
import sh.hnet.calendar.model.Scope
import sh.hnet.calendar.ui.Screen

sealed interface EditorState {
    data object Loading : EditorState
    /** Nothing to edit: the event is gone, access was taken away, or no calendar can be written to. The form closes. */
    data object Gone : EditorState

    /**
     * @param draft What the form holds now
     * @param opened What it held when it opened: for telling whether anything was changed, and what
     * @param event The event being edited or copied; null for a new one
     * @param copy Whether [event] is only the pattern for a new one (Duplicate)
     * @param calendars The calendars that can be written to
     */
    data class Ready(val draft: EventDraft, val opened: EventDraft, val event: EventDetails?, val copy: Boolean, val calendars: List<CalendarInfo>) : EditorState {
        val editing: Boolean get() = event != null && !copy
        val changed: Boolean get() = draft != opened

        /** A repeating event whose occurrence was not changed on its own yet: saving asks which occurrences are meant. */
        val asksScope: Boolean get() = editing && event?.repeating == true && event.exception.not()

        /** The answers the question has: a changed repetition is not for one occurrence alone (the owner, 16 Nov 2026). */
        val scopes: List<Scope> get() = if (EventWriter.repetitionEdited(opened, draft)) listOf(Scope.FOLLOWING, Scope.ALL) else Scope.entries

        /** An occurrence that was changed on its own is one event: it has no repetition to set. */
        val mayRepeat: Boolean get() = !(editing && event?.exception == true)

        /**
         * Whether the event may go to [calendar] (SPEC §10.10, the owner's word on the logic): a new
         * one anywhere; an existing one only within its account, and only when it neither repeats
         * nor has guests – it is written anew there, and would lose them.
         */
        fun mayUse(calendar: CalendarInfo): Boolean {
            if (!editing || calendar.id == opened.calendarId) return true
            val home = calendars.firstOrNull { it.id == opened.calendarId } ?: return false
            val simple = event?.let { !it.repeating && !it.exception && it.guests.isEmpty() } ?: true
            return simple && home.accountType == calendar.accountType && home.accountName == calendar.accountName
        }

        fun sameAccount(calendar: CalendarInfo): Boolean {
            val home = calendars.firstOrNull { it.id == opened.calendarId } ?: return false
            return home.accountType == calendar.accountType && home.accountName == calendar.accountName
        }
    }
}

sealed interface SaveResult {
    data object Saved : SaveResult
    /** A repeating event: which occurrences? */
    data object AskScope : SaveResult
    /** A sync changed the event while it was being edited (SPEC §10.11). */
    data object Changed : SaveResult
    /** The event was deleted while it was being edited. */
    data object Vanished : SaveResult
    data class Failed(val details: String) : SaveResult
}

/** The editor's data (SPEC §10): a draft of a new event, of an existing one, or of its copy, and saving it. */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val _state = MutableStateFlow<EditorState>(EditorState.Loading)
    val state: StateFlow<EditorState> = _state
    private var opened = false
    /** What the event held when the form opened. */
    private var fingerprint: String? = null

    fun open(screen: Screen.Editor) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            _state.value = try {
                load(screen)
            } catch (_: SecurityException) {
                EditorState.Gone
            }
        }
    }

    private suspend fun load(screen: Screen.Editor): EditorState {
        val source = app.calendars
        if (!source.hasAccess()) return EditorState.Gone
        val calendars = CalendarChoices.apply(source.calendars(), app.calendarChoices.choices.first()).filter { it.writable }
        if (calendars.isEmpty()) return EditorState.Gone
        val zone = ZoneId.systemDefault()
        // A new event goes where the last one went, else to the first calendar shown
        val last = app.preferences.lastCalendar.first()
        val usual = calendars.firstOrNull { it.key == last } ?: calendars.firstOrNull { it.shown } ?: calendars.first()
        if (screen.eventId == 0L) {
            val now = LocalDateTime.now(zone)
            val day = if (screen.day > 0) JulianDay.toDate(screen.day) else now.toLocalDate()
            val time = if (screen.minute >= 0) LocalTime.of(screen.minute / 60, screen.minute % 60) else null
            val draft = EventDraft.new(usual.id, day, now, zone, time)
            return EditorState.Ready(draft, draft, null, false, calendars)
        }
        val event = source.event(screen.eventId, screen.begin, screen.end) ?: return EditorState.Gone
        var draft = EventDraft.of(event, zone)
        if (screen.copy) {
            // A copy is the one occurrence that was opened: it does not repeat (the owner, 16 Nov 2026), but can be made to
            draft = draft.copy(repetition = Repetition.None)
            // It may go anywhere; from a read-only calendar it goes to the usual one
            if (calendars.none { it.id == draft.calendarId }) draft = draft.copy(calendarId = usual.id)
        } else {
            if (calendars.none { it.id == draft.calendarId }) return EditorState.Gone
            fingerprint = app.writer.fingerprint(screen.eventId)
        }
        return EditorState.Ready(draft, draft, event, screen.copy, calendars)
    }

    fun change(transform: (EventDraft) -> EventDraft) {
        val ready = _state.value as? EditorState.Ready ?: return
        _state.value = ready.copy(draft = transform(ready.draft))
    }

    /**
     * Saves the draft. [scope] answers [SaveResult.AskScope]; [force] saves over what a sync changed
     * ([SaveResult.Changed]); [asNew] writes the draft as a new event ([SaveResult.Vanished]).
     */
    suspend fun save(scope: Scope? = null, force: Boolean = false, asNew: Boolean = false): SaveResult {
        val ready = _state.value as? EditorState.Ready ?: return SaveResult.Vanished
        val draft = ready.draft
        val event = ready.event
        val writer = app.writer
        return try {
            if (event == null || ready.copy || asNew) {
                writer.insert(draft)
                ready.calendars.firstOrNull { it.id == draft.calendarId }?.let { app.preferences.setLastCalendar(it.key) }
                return SaveResult.Saved
            }
            if (!ready.changed) return SaveResult.Saved
            if (!force) {
                val now = writer.fingerprint(event.eventId) ?: return SaveResult.Vanished
                if (now != fingerprint) return SaveResult.Changed
            }
            if (draft.calendarId != ready.opened.calendarId) {
                writer.move(event.eventId, draft)
                return SaveResult.Saved
            }
            if (ready.asksScope && scope == null) return SaveResult.AskScope
            if (writer.update(event.eventId, event.begin, ready.opened, draft, scope ?: Scope.ALL)) SaveResult.Saved else SaveResult.Vanished
        } catch (failure: Exception) {
            SaveResult.Failed(failure.stackTraceToString())
        }
    }
}
