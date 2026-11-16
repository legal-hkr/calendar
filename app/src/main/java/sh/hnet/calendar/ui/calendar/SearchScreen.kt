@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.calendar

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.R
import sh.hnet.calendar.model.Agenda
import sh.hnet.calendar.model.AgendaDay
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.Search
import sh.hnet.calendar.ui.components.EmptyState
import sh.hnet.calendar.ui.components.TooltipIconButton

sealed interface SearchState {
    /** Nothing typed yet. */
    data object Idle : SearchState
    data object Searching : SearchState

    /**
     * @param days The days with events found, in the order they are listed: soonest first for the upcoming, newest first for the past
     * @param years How many years from today were looked through
     * @param more Whether there are further years to look through
     */
    data class Results(val days: List<AgendaDay>, val calendars: Map<Long, CalendarInfo>, val today: Int, val now: Long, val years: Int, val more: Boolean) : SearchState
}

/**
 * Search (SPEC §11): the events of the shown calendars whose title, place or description has
 * every word typed – the upcoming ones from today on, or the past ones. A year from today is
 * looked through at a time: the next one when the list nears its end, and at once while nothing
 * was found, up to ten.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalendarApplication
    private val source = app.calendars
    val field = TextFieldState()
    private val _past = MutableStateFlow(false)
    val past: StateFlow<Boolean> = _past
    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state
    private var years = 1
    private var running: Job? = null

    init {
        viewModelScope.launch {
            combine(snapshotFlow { field.text.toString() }, _past) { query, past -> query to past }.collectLatest {
                // Typing: wait for a pause
                delay(TYPING)
                years = 1
                run()
            }
        }
        // The calendar changed under the results, a calendar was hidden, an event deleted here
        viewModelScope.launch { source.changes.collectLatest { delay(SETTLE); run() } }
        viewModelScope.launch { app.calendarChoices.choices.drop(1).collectLatest { run() } }
        viewModelScope.launch { app.deletes.hidden.drop(1).collectLatest { run() } }
    }

    fun setPast(past: Boolean) {
        _past.value = past
    }

    /** The list nears its end: a year more. */
    fun extend() {
        val results = _state.value as? SearchState.Results ?: return
        if (!results.more || running?.isActive == true) return
        years = results.years + 1
        run()
    }

    /** Search is left: the next opening starts empty. */
    fun clear() {
        field.clearText()
        _past.value = false
        _state.value = SearchState.Idle
    }

    private fun run() {
        running?.cancel()
        running = viewModelScope.launch {
            val words = Search.words(field.text.toString())
            if (words.isEmpty() || !source.hasAccess()) {
                _state.value = SearchState.Idle
                return@launch
            }
            if (_state.value !is SearchState.Results) _state.value = SearchState.Searching
            try {
                val calendars = CalendarChoices.apply(source.calendars(), app.calendarChoices.choices.first())
                val shown = calendars.filter { it.shown }.mapTo(HashSet()) { it.id }
                val found = source.matching(words, shown)
                val declined = app.preferences.viewSettings.first().showDeclined
                val past = _past.value
                val today = JulianDay.of(LocalDate.now(ZoneId.systemDefault()))
                var days: List<AgendaDay>
                while (true) {
                    // Upcoming: from today on; past: up to yesterday
                    val first = if (past) today - years * YEAR else today
                    val last = if (past) today - 1 else today + years * YEAR
                    val occurrences = if (found.isEmpty()) emptyList() else source.occurrences(first, last, shown).filter { it.eventId in found && !app.deletes.hides(it) && (declined || !it.declined) }
                    // No day is listed for being today: only days with something found
                    days = Agenda.build(occurrences, first, last, today = Int.MIN_VALUE, showDeclined = true)
                    if (days.isNotEmpty() || found.isEmpty() || years >= MAX_YEARS) break
                    years++
                }
                _state.value = SearchState.Results(if (past) days.reversed() else days, calendars.associateBy { it.id }, today, System.currentTimeMillis(), years, more = found.isNotEmpty() && years < MAX_YEARS)
            } catch (_: SecurityException) {
                _state.value = SearchState.Idle
            }
        }
    }

    private companion object {
        const val TYPING = 250L
        const val SETTLE = 300L
        const val YEAR = 366
        const val MAX_YEARS = 10
    }
}

/**
 * Search (SPEC §11, board 18), opened from the top bar as Music's: the bar with Back, the query
 * and Clear; under it Upcoming or Past; the results as Agenda's rows under their days. A tap opens
 * the event's page, from which Back returns here.
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenEvent: (Occurrence) -> Unit, viewModel: SearchViewModel = viewModel(key = "search")) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val past by viewModel.past.collectAsStateWithLifecycle()
    val formats = rememberCalendarFormats(remember { ZoneId.systemDefault() })
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val leave = {
        viewModel.clear()
        onBack()
    }
    BackHandler(onBack = leave)
    // Typing starts at once; back from an event's page the results stay as they were
    LaunchedEffect(Unit) { if (viewModel.field.text.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The input alone, as in Music: a collapsed SearchBar takes no typing, so the field carries the bar's container itself
        SearchBarDefaults.InputField(
            textFieldState = viewModel.field,
            searchBarState = rememberSearchBarState(),
            onSearch = { keyboard?.hide() },
            placeholder = { Text(stringResource(R.string.hint_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), leave) },
            trailingIcon = if (viewModel.field.text.isNotEmpty()) {
                { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear), { viewModel.field.clearText() }) }
            } else null,
            colors = SearchBarDefaults.inputFieldColors(
                focusedContainerColor = SearchBarDefaults.colors().containerColor,
                unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                disabledContainerColor = SearchBarDefaults.colors().containerColor,
            ),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).focusRequester(focus),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !past, onClick = { viewModel.setPast(false) }, label = { Text(stringResource(R.string.label_upcoming)) })
            FilterChip(selected = past, onClick = { viewModel.setPast(true) }, label = { Text(stringResource(R.string.label_past)) })
        }

        when (val current = state) {
            SearchState.Idle, SearchState.Searching -> Unit
            is SearchState.Results -> if (current.days.isEmpty()) {
                EmptyState(R.drawable.ic_search_off, stringResource(R.string.msg_no_events_found), Modifier.imePadding())
            } else {
                // Search lies outside the views that are handed the options, so it reads the setting itself
                val dimPast = (LocalContext.current.applicationContext as CalendarApplication).preferences.latest?.dimPast ?: true
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    ) {
                        for (day in current.days) {
                            item(key = "day/${day.day}", contentType = "header") { DayHeader(day.day, current.today, formats, Modifier.padding(top = 12.dp)) }
                            itemsIndexed(day.rows, key = { _, row -> row.key }, contentType = { _, _ -> "event" }) { index, row ->
                                EventRow(
                                    row = row,
                                    color = current.calendars[row.occurrence.calendarId]?.color ?: CalendarColor.GRAPHITE,
                                    past = dimPast && row.occurrence.isPast(current.now, current.today),
                                    formats = formats,
                                    shape = groupShape(index == 0, index == day.rows.lastIndex),
                                    onClick = { keyboard?.hide(); onOpenEvent(row.occurrence) },
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 2.dp),
                                )
                            }
                        }
                        // Reached: the next year is looked through
                        if (current.more) item(key = "more", contentType = "more") { LaunchedEffect(current.years) { viewModel.extend() } }
                    }
                }
            }
        }
    }
}
