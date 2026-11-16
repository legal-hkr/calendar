package sh.hnet.calendar.ui.event

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.model.Answer
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventDetails

sealed interface EventState {
    data object Loading : EventState
    /** The event is no more (deleted here or by a sync), or access was taken away: the page closes. */
    data object Gone : EventState

    /** @param calendar The event's calendar with the user's color; null if it vanished from the list */
    data class Ready(val event: EventDetails, val calendar: CalendarInfo?) : EventState
}

/** The event page's data (SPEC §9): one occurrence, read again whenever the provider or the calendars' colors change. */
class EventViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val source = app.calendars
    private val _state = MutableStateFlow<EventState>(EventState.Loading)
    val state: StateFlow<EventState> = _state
    private var watching: Job? = null
    private var shown: Triple<Long, Long, Long>? = null
    /** The occurrence the state is of, also while the page is away. */
    private var last: Triple<Long, Long, Long>? = null

    /** The answer to the invitation, saved at once (SPEC §9.3); the provider's change brings the page up to date. */
    fun answer(eventId: Long, answer: Answer, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                app.writer.setAnswer(eventId, answer)
                onSaved()
            } catch (_: Exception) {
                // Access gone or the provider refused: the buttons keep showing the answer that stands
            }
        }
    }

    /**
     * The page has left the screen. The view model is kept by the activity, one per event that was
     * opened, so without this each of them would go on reading its event at every change of the
     * calendar for as long as the app runs.
     */
    fun hide() {
        watching?.cancel()
        watching = null
        shown = null
    }

    /** The page shows the occurrence of [eventId] from [begin] to [end]. */
    fun show(eventId: Long, begin: Long, end: Long) {
        val key = Triple(eventId, begin, end)
        if (key == shown) return
        shown = key
        // Back on a page that was read before – from the editor, say –, what it showed stands until it is read again
        if (key != last) _state.value = EventState.Loading
        last = key
        watching?.cancel()
        if (!source.hasAccess()) {
            _state.value = EventState.Gone
            return
        }
        watching = viewModelScope.launch {
            try {
                combine(source.changes.onStart { emit(Unit) }, app.calendarChoices.choices) { _, chosen -> chosen }.collect { chosen ->
                    val event = source.event(eventId, begin, end)
                    _state.value = if (event == null) {
                        EventState.Gone
                    } else {
                        EventState.Ready(event, CalendarChoices.apply(source.calendars(), chosen).firstOrNull { it.id == event.calendarId })
                    }
                }
            } catch (_: SecurityException) {
                _state.value = EventState.Gone
            }
        }
    }
}
