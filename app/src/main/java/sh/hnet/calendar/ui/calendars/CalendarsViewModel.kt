package sh.hnet.calendar.ui.calendars

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo

/** The calendars of one account, or the local ones. [app] names the app the account belongs to, when the system tells. */
data class CalendarGroup(val accountName: String, val app: String?, val local: Boolean, val calendars: List<CalendarInfo>)

sealed interface CalendarsState {
    data object Loading : CalendarsState
    data object NoAccess : CalendarsState
    /** Accounts in the provider's order, the local calendars last. Empty when the phone has no calendar. */
    data class Ready(val groups: List<CalendarGroup>) : CalendarsState
}

/** The Calendars page's data (SPEC §12.1): what the provider lists, with the user's choices, kept current while the page is open. */
class CalendarsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val source = app.calendars
    private val choices = app.calendarChoices
    private val _state = MutableStateFlow<CalendarsState>(CalendarsState.Loading)
    val state: StateFlow<CalendarsState> = _state
    private var watching: Job? = null

    /** Called whenever the page comes to the front: access may have changed in the system's settings. */
    fun refreshAccess() {
        if (!source.hasAccess()) {
            watching?.cancel()
            watching = null
            _state.value = CalendarsState.NoAccess
            return
        }
        if (watching != null) return
        watching = viewModelScope.launch {
            try {
                combine(source.changes.onStart { emit(Unit) }.map { source.calendars() }, choices.choices) { calendars, chosen ->
                    CalendarChoices.apply(calendars, chosen)
                }.collect { calendars -> _state.value = CalendarsState.Ready(groups(calendars)) }
            } catch (_: SecurityException) {
                // Access was taken away while the page was open
                watching = null
                _state.value = CalendarsState.NoAccess
            }
        }
    }

    /** Accounts in the provider's order; the local calendars as one group at the end, whatever names their accounts carry. */
    private fun groups(calendars: List<CalendarInfo>): List<CalendarGroup> {
        val (local, synced) = calendars.partition { it.local }
        val accounts = synced.groupBy { it.accountType to it.accountName }.map { (account, list) -> CalendarGroup(account.second, source.accountApp(account.first), false, list) }
        return if (local.isEmpty()) accounts else accounts + CalendarGroup("", null, true, local.sortedBy { it.name.lowercase() })
    }

    fun setShown(calendar: CalendarInfo, shown: Boolean) {
        viewModelScope.launch { choices.setShown(calendar.key, shown) }
    }

    fun setColor(calendar: CalendarInfo, color: CalendarColor) {
        viewModelScope.launch { choices.setColor(calendar.key, color) }
    }

    /** A new local calendar (SPEC §12.5), shown in the color chosen for it; the provider's change brings it onto the page. */
    fun create(name: String, color: CalendarColor, onCreated: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                app.localCalendars.create(name, color)?.let {
                    choices.setColor(it, color)
                    onCreated()
                }
            } catch (_: Exception) {
                // Access gone or the provider refused: the list shows that there is no such calendar
            }
        }
    }

    fun rename(calendar: CalendarInfo, name: String) {
        viewModelScope.launch { runCatching { app.localCalendars.rename(calendar, name) } }
    }

    /** Deletes a local calendar with its events (SPEC §12.4), after the user confirmed; [onDeleted] when it is gone. */
    fun delete(calendar: CalendarInfo, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (runCatching { app.localCalendars.delete(calendar) }.isSuccess) onDeleted()
        }
    }
}
