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
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Month
import sh.hnet.calendar.model.Occurrence

/** The occurrences read for Month: everything touching the Julian days [firstDay]..[lastDay] in the calendars shown. */
data class MonthEvents(val firstDay: Int, val lastDay: Int, val occurrences: List<Occurrence>) {
    /** Whether the page of the month [number] can be drawn from this, whichever day its weeks start with. */
    fun covers(number: Int): Boolean {
        val first = JulianDay.of(Month.firstDate(number))
        return first - LEAD >= firstDay && first + SPAN <= lastDay
    }

    companion object {
        /** A page starts up to six days before the month's first day and shows six weeks. */
        const val LEAD = 6
        const val SPAN = Month.WEEKS * 7 - 1
        val NONE = MonthEvents(0, -1, emptyList())
    }
}

/**
 * Month's data (SPEC §6): the month the pager is at and its two neighbors, so a swipe finds its
 * page filled; read again whenever the provider or the user's choice of calendars changes.
 */
class MonthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val source = app.calendars
    private val choices = app.calendarChoices
    private val _events = MutableStateFlow(MonthEvents.NONE)
    val events: StateFlow<MonthEvents> = _events

    private val lock = Mutex()
    private var month = 0
    private var watching: Job? = null

    /** The pager is at the month [number]. Called only with calendar access, as the screen shows Month only then. */
    fun show(number: Int) {
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
        if (number == month && _events.value.covers(number)) return
        month = number
        viewModelScope.launch { load() }
    }

    private suspend fun load() = lock.withLock {
        val number = month
        val firstDay = JulianDay.of(Month.firstDate(number - 1)) - MonthEvents.LEAD
        val lastDay = JulianDay.of(Month.firstDate(number + 1)) + MonthEvents.SPAN
        try {
            val shown = CalendarChoices.apply(source.calendars(), choices.choices.first()).filter { it.shown }.mapTo(HashSet()) { it.id }
            _events.value = MonthEvents(firstDay, lastDay, source.occurrences(firstDay, lastDay, shown))
        } catch (_: SecurityException) {
            // Access was taken away; the main screen says so when it comes to the front
            _events.value = MonthEvents.NONE
        }
    }

    private companion object {
        const val SETTLE = 300L
    }
}
