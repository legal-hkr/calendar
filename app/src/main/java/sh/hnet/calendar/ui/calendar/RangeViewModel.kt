package sh.hnet.calendar.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.Occurrence

/** The occurrences read for a view: everything touching the Julian days [firstDay]..[lastDay] in the calendars shown. */
data class LoadedEvents(val firstDay: Int, val lastDay: Int, val occurrences: List<Occurrence>) {
    fun covers(first: Int, last: Int): Boolean = first >= firstDay && last <= lastDay

    companion object {
        val NONE = LoadedEvents(0, -1, emptyList())
    }
}

/**
 * The events of the days a paged view is at (Month, Week, Day; each has its own instance): the
 * pages on screen and their neighbors, so a swipe finds its page filled; read again whenever the
 * provider or the user's choice of calendars changes.
 */
class RangeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val source = app.calendars
    private val choices = app.calendarChoices
    private val _events = MutableStateFlow(LoadedEvents.NONE)
    val events: StateFlow<LoadedEvents> = _events

    private val lock = Mutex()
    private var firstDay = 0
    private var lastDay = -1
    private var watching: Job? = null

    /**
     * The view needs the days [first]..[last] – its page and the neighbors. When they are not all
     * read yet, they are read with [margin] days more on each side, so the next swipes need nothing.
     * Called only with calendar access, as the screen shows the views only then.
     */
    fun show(first: Int, last: Int, margin: Int = 0) {
        if (watching == null) {
            watching = viewModelScope.launch {
                launch {
                    // A sync writes many rows: wait for it to settle
                    source.changes.collectLatest {
                        delay(SETTLE)
                        load()
                    }
                }
                // The first value is what the first load reads anyway
                choices.choices.drop(1).collectLatest { load() }
            }
        }
        if (first >= firstDay && last <= lastDay) return
        firstDay = first - margin
        lastDay = last + margin
        viewModelScope.launch { load() }
    }

    private suspend fun load() = lock.withLock {
        val first = firstDay
        val last = lastDay
        if (last < first) return@withLock
        try {
            val shown = CalendarChoices.apply(source.calendars(), choices.choices.first()).filter { it.shown }.mapTo(HashSet()) { it.id }
            _events.value = LoadedEvents(first, last, source.occurrences(first, last, shown))
        } catch (_: SecurityException) {
            // Access was taken away; the main screen says so when it comes to the front
            _events.value = LoadedEvents.NONE
        }
    }

    private companion object {
        const val SETTLE = 300L
    }
}
