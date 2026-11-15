package sh.hnet.calendar.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.model.Agenda
import sh.hnet.calendar.model.AgendaDay
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.JulianDay

/** What the main screen shows (SPEC §18). */
sealed interface CalendarState {
    /** Before the first answer from the provider: nothing is drawn. */
    data object Loading : CalendarState
    /** The calendar permission is refused (SPEC §15.2). */
    data object NoAccess : CalendarState
    /** Access, but no calendar on the phone (SPEC §12.6). */
    data object NoCalendars : CalendarState

    /**
     * @param days The loaded days that have events, and today
     * @param today Julian day of today in [zone]
     * @param now When the list was built or last ticked; decides which events are over
     */
    data class Ready(val days: List<AgendaDay>, val calendars: Map<Long, CalendarInfo>, val today: Int, val now: Long, val zone: ZoneId) : CalendarState
}

/**
 * Agenda's data (SPEC §8): a window of days around today read from the provider, widened as the
 * list nears an end, and read again whenever the provider changes, the minute or the day turns.
 */
class AgendaViewModel(application: Application) : AndroidViewModel(application) {
    private val source = (application as CalendarApplication).calendars
    private val _state = MutableStateFlow<CalendarState>(CalendarState.Loading)
    val state: StateFlow<CalendarState> = _state

    /** One load at a time: the window's bounds belong to whoever holds the lock. */
    private val lock = Mutex()
    private var firstDay = 0
    private var lastDay = 0
    private var nothingEarlier = false
    private var nothingLater = false
    private var watching: Job? = null

    init {
        // The minute turns: events end, and at midnight today moves on
        viewModelScope.launch {
            while (true) {
                delay(60_000 - System.currentTimeMillis() % 60_000)
                val ready = _state.value as? CalendarState.Ready ?: continue
                // Within a day only "now" moves; a new day (or another time zone) needs the days built again
                val zone = ZoneId.systemDefault()
                if (zone == ready.zone && JulianDay.of(LocalDate.now(zone)) == ready.today) {
                    _state.compareAndSet(ready, ready.copy(now = System.currentTimeMillis()))
                } else {
                    reload()
                }
            }
        }
    }

    /** Called whenever the screen comes to the front: the permission may have changed in the system's settings. */
    fun refreshAccess() {
        val access = source.hasAccess()
        if (!access) {
            watching?.cancel()
            watching = null
            _state.value = CalendarState.NoAccess
            return
        }
        if (watching == null) {
            watching = viewModelScope.launch {
                // A sync writes many rows: wait for it to settle, and let a newer change replace a load under way
                source.changes.collectLatest {
                    delay(SETTLE)
                    nothingEarlier = false
                    nothingLater = false
                    reload()
                }
            }
        }
        if (_state.value !is CalendarState.Ready) viewModelScope.launch { reload() }
    }

    /** The list is near its start or its end: load further days, until some have events or [SEARCH_LIMIT] days were looked through. */
    fun extend(later: Boolean) {
        viewModelScope.launch {
            lock.withLock {
                if (_state.value !is CalendarState.Ready || (if (later) nothingLater else nothingEarlier)) return@withLock
                var step = STEP
                var looked = 0
                while (true) {
                    val from = if (later) lastDay + 1 else firstDay - step
                    val to = if (later) lastDay + step else firstDay - 1
                    val found = try {
                        source.occurrences(from, to)
                    } catch (_: SecurityException) {
                        return@withLock
                    }
                    if (later) lastDay = to else firstDay = from
                    looked += step
                    if (found.isNotEmpty()) break
                    if (looked >= SEARCH_LIMIT) {
                        if (later) nothingLater = true else nothingEarlier = true
                        break
                    }
                    step *= 2
                }
                load()
            }
        }
    }

    private suspend fun reload() = lock.withLock { load() }

    private suspend fun load() {
        if (!source.hasAccess()) {
            _state.value = CalendarState.NoAccess
            return
        }
        val zone = ZoneId.systemDefault()
        val today = JulianDay.of(LocalDate.now(zone))
        if (firstDay == 0) {
            firstDay = today - STEP
            lastDay = today + STEP
        }
        // Today is always listed, also after the window was left behind by the calendar
        firstDay = minOf(firstDay, today)
        lastDay = maxOf(lastDay, today)
        try {
            val calendars = source.calendars()
            if (calendars.isEmpty()) {
                _state.value = CalendarState.NoCalendars
                return
            }
            val days = Agenda.build(source.occurrences(firstDay, lastDay), firstDay, lastDay, today)
            _state.value = CalendarState.Ready(days, calendars.associateBy { it.id }, today, System.currentTimeMillis(), zone)
        } catch (_: SecurityException) {
            // Access was taken away between the check and the query
            _state.value = CalendarState.NoAccess
        }
    }

    private companion object {
        /** Days loaded before and after today at first, and the first step of widening (SPEC §8.1). */
        const val STEP = 30
        /** How far one widening looks for a day with events before it gives up. */
        const val SEARCH_LIMIT = 450
        const val SETTLE = 300L
    }
}
