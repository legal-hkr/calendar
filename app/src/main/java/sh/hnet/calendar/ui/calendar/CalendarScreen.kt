@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.calendar.ui.calendar

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import sh.hnet.calendar.ui.calendars.CalendarsViewModel
import sh.hnet.calendar.ui.calendars.CreateCalendarDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.R
import sh.hnet.calendar.ViewSettings
import sh.hnet.calendar.model.CalendarView
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Month
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.ui.components.CalendarAccess
import sh.hnet.calendar.ui.components.EmptyState
import sh.hnet.calendar.ui.components.MenuItem
import sh.hnet.calendar.ui.components.TooltipIconButton
import sh.hnet.calendar.ui.components.rememberCalendarAccessAsk

/**
 * The app's root screen (SPEC §3, §18): the views of time under one top bar. Without access or
 * without any calendar it says so instead, with Settings in the top bar (SPEC §15.2, §12.6).
 * Search and the new-event button come with their stages.
 */
@Composable
fun CalendarScreen(onOpenEvent: (Occurrence) -> Unit, onNewEvent: (day: Int, minute: Int) -> Unit, onOpenSearch: () -> Unit, onOpenCalendars: () -> Unit, onOpenSettings: () -> Unit, viewModel: AgendaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from the system's settings or its permission dialog: the answer may have changed
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    val preferences = (LocalContext.current.applicationContext as CalendarApplication).preferences
    // Nothing is drawn before the saved view is known, so the app doesn't open on one view and change to another
    val settings by preferences.viewSettings.collectAsStateWithLifecycle(null)
    val ready = state as? CalendarState.Ready
    val current = settings
    if (ready != null && current != null) {
        CalendarViews(ready, current, onOpenEvent, onNewEvent, onOpenSearch, onOpenCalendars, onOpenSettings, viewModel)
        return
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        when (state) {
            CalendarState.NoAccess -> NoAccess(onAnswer = viewModel::refreshAccess)
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size
            CalendarState.NoCalendars -> NoCalendars()
            else -> Unit
        }
    }
}

/**
 * A phone without any calendar (SPEC §12.6, board 22): the app's mark, the sentence, "Create
 * calendar" and what such a calendar is. Once one is there the provider's change brings the views up.
 */
@Composable
private fun NoCalendars(calendars: CalendarsViewModel = viewModel()) {
    var creating by rememberSaveable { mutableStateOf(false) }
    // The app's mark is drawn on the launcher icon's canvas, hence the larger size
    EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_no_calendars), iconSize = 108.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { creating = true }) { Text(stringResource(R.string.button_create_calendar)) }
            Text(stringResource(R.string.msg_no_calendars_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    if (creating) CreateCalendarDialog(emptySet(), onCreate = { name, color -> creating = false; calendars.create(name, color) }, onDismiss = { creating = false })
}

/** A step back in the views' history: the view and the day it was at. */
private fun step(view: CalendarView, day: Int): Long = day.toLong() * STEP_VIEWS + view.ordinal

private const val STEP_VIEWS = 16
private const val HISTORY_LIMIT = 10

/**
 * The views (SPEC §3): the top bar with the period – a tap goes to a date –, Today and the view
 * menu, and under it the view chosen there. [focus] is the day the screen is at, which the views
 * share: Month shows its month, Week its week, Day the day, Agenda scrolls to it. Every change of
 * view, and a day opened from Month or Week, is a step that Back retraces (SPEC §3.9).
 */
@Composable
private fun CalendarViews(state: CalendarState.Ready, settings: ViewSettings, onOpenEvent: (Occurrence) -> Unit, onNewEvent: (day: Int, minute: Int) -> Unit, onOpenSearch: () -> Unit, onOpenCalendars: () -> Unit, onOpenSettings: () -> Unit, viewModel: AgendaViewModel) {
    val eventList = settings.eventList
    val preferences = (LocalContext.current.applicationContext as CalendarApplication).preferences
    val scope = rememberCoroutineScope()
    val formats = rememberCalendarFormats(state.zone)
    val weekStart = rememberWeekStart()

    var view by rememberSaveable { mutableStateOf(settings.view) }
    var focus by rememberSaveable { mutableIntStateOf(state.today) }
    // Counts the moves made on purpose (Today, Go to date, another view), which Agenda answers by scrolling
    var jump by rememberSaveable { mutableIntStateOf(0) }
    val history: SnapshotStateList<Long> = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableListOf<Long>().toMutableStateList() }
    val monthScroll = remember(weekStart) { MonthScroll(MonthScroll.monthOf(focus), weekStart) }
    val weeks = remember(weekStart) { WeekPages(weekStart) }
    val weekPager = rememberPagerState(initialPage = weeks.of(focus)) { weeks.count }
    val dayPager = rememberPagerState(initialPage = DayPages.of(focus)) { DayPages.COUNT }
    // Week and Day share the hour's height and how far the hours are scrolled
    val hours = remember { HoursState(settings.hourHeight) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Asked from elsewhere to show a day – after an import of several events (SPEC §13.9), from a widget
    val app = LocalContext.current.applicationContext as CalendarApplication
    val asked by app.goTo.collectAsStateWithLifecycle()

    /** [place]: Week and Day choose where their hours open; without it they stay as they are when one changes into the other. */
    fun show(next: CalendarView, day: Int, place: Boolean = true) {
        if (next.hours && (place || !view.hours)) hours.scroll.intValue = -1
        if (next != view) {
            // The view comes up already at its page, not a frame later
            when (next) {
                CalendarView.MONTH -> monthScroll.jumpTo(MonthScroll.monthOf(day))
                CalendarView.WEEK -> weekPager.requestScrollToPage(weeks.of(day))
                CalendarView.DAY -> dayPager.requestScrollToPage(DayPages.of(day))
                CalendarView.AGENDA -> Unit
            }
            view = next
            scope.launch { preferences.setView(next) }
        }
        focus = day
        jump++
        if (next == CalendarView.AGENDA) viewModel.moveTo(day)
    }

    fun open(next: CalendarView, day: Int = focus, place: Boolean = true) {
        history += step(view, focus)
        if (history.size > HISTORY_LIMIT) history.removeAt(0)
        show(next, day, place)
    }

    BackHandler(enabled = history.isNotEmpty()) {
        val last = history.removeAt(history.lastIndex)
        show(CalendarView.entries[(last % STEP_VIEWS).toInt()], (last / STEP_VIEWS).toInt(), place = false)
    }

    LaunchedEffect(asked) {
        asked?.let {
            app.goTo.value = null
            open(it.view, it.day)
        }
    }

    val period = when (view) {
        CalendarView.MONTH -> formats.month(Month.firstDate(monthScroll.month))
        // A week over two months is named by its first day's (SPEC §3.2)
        CalendarView.WEEK -> formats.month(JulianDay.toDate(weeks.first(weekPager.currentPage)))
        CalendarView.DAY -> formats.dayOfMonth(JulianDay.toDate(DayPages.day(dayPager.currentPage)))
        CalendarView.AGENDA -> formats.month(JulianDay.toDate(focus))
    }
    val onZoomed: (Float) -> Unit = { scope.launch { preferences.setHourHeight(it) } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { PeriodTitle(period, onClick = { picking = true }) },
            actions = {
                TooltipIconButton(R.drawable.ic_search, stringResource(R.string.content_description_search), onOpenSearch)
                TooltipIconButton(R.drawable.ic_today, stringResource(R.string.content_description_today), { show(view, state.today) })
                Box {
                    TooltipIconButton(view.icon(filled = false), stringResource(R.string.content_description_view_menu), { menu = true })
                    ViewMenu(
                        expanded = menu,
                        view = view,
                        eventList = eventList,
                        onDismiss = { menu = false },
                        onView = { if (it != view) open(it, place = false) },
                        onEventList = { scope.launch { preferences.setEventList(it) } },
                        onOpenCalendars = onOpenCalendars,
                        onOpenSettings = onOpenSettings,
                    )
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
        // What Settings › View says about the looks reaches the tiles this way
        CompositionLocalProvider(LocalViewOptions provides remember(settings.dimPast, settings.firstHour, settings.weekNumbers) { ViewOptions(settings.dimPast, settings.firstHour, settings.weekNumbers) }) {
        Crossfade(view, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), modifier = Modifier.fillMaxSize()) { shown ->
            when (shown) {
                CalendarView.MONTH -> MonthView(
                    state = state,
                    formats = formats,
                    weekStart = weekStart,
                    scroll = monthScroll,
                    eventList = eventList,
                    focus = focus,
                    onFocus = { focus = it },
                    onOpenDay = { open(CalendarView.DAY, it) },
                    onOpenEvent = onOpenEvent,
                )
                CalendarView.WEEK -> WeekView(
                    state = state,
                    formats = formats,
                    weeks = weeks,
                    pager = weekPager,
                    hours = hours,
                    focus = focus,
                    onFocus = { focus = it },
                    // The hours stay where they are
                    onOpenDay = { open(CalendarView.DAY, it, place = false) },
                    onOpenEvent = onOpenEvent,
                    onNewEvent = onNewEvent,
                    onZoomed = onZoomed,
                )
                CalendarView.DAY -> DayView(state, formats, weekStart, dayPager, hours, focus, onFocus = { focus = it }, onOpenEvent = onOpenEvent, onNewEvent = onNewEvent, onZoomed = onZoomed)
                CalendarView.AGENDA -> AgendaList(state, formats, target = focus, jump = jump, onDay = { if (view == CalendarView.AGENDA) focus = it }, onNearEnd = viewModel::extend, onOpenEvent = onOpenEvent)
            }
        }
        }
        // New event (SPEC §3.5), where there is a calendar to write to. On the day shown in Day and chosen in Month's list, else today
        if (state.calendars.values.any { it.writable }) {
            FloatingActionButton(
                onClick = { onNewEvent(if (view == CalendarView.DAY || (view == CalendarView.MONTH && eventList)) focus else 0, -1) },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.content_description_new_event))
            }
        }
        }
    }

    if (picking) {
        // The picker counts in UTC days
        val picker = rememberDatePickerState(initialSelectedDateMillis = JulianDay.toDate(focus).toEpochDay() * DAY_MILLIS)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        picking = false
                        picker.selectedDateMillis?.let { show(view, JulianDay.of(LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MILLIS)))) }
                    },
                ) { Text(stringResource(R.string.button_go)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.button_cancel)) } },
        ) {
            DatePicker(picker, title = { Text(stringResource(R.string.title_go_to_date), modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
        }
    }
}

private const val DAY_MILLIS = 86_400_000L

/** The period in the top bar (SPEC §3.2): one line, with the arrow that says it opens something. */
@Composable
private fun PeriodTitle(text: String, onClick: () -> Unit) {
    Row(
        // The touch shape reaches past the text, which keeps its place in the bar
        modifier = Modifier.offset(x = (-12).dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.content_description_go_to_date), role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
        Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun CalendarView.icon(filled: Boolean): Int = when (this) {
    CalendarView.MONTH -> if (filled) R.drawable.ic_calendar_view_month_filled else R.drawable.ic_calendar_view_month
    CalendarView.WEEK -> if (filled) R.drawable.ic_calendar_view_week_filled else R.drawable.ic_calendar_view_week
    CalendarView.DAY -> if (filled) R.drawable.ic_calendar_view_day_filled else R.drawable.ic_calendar_view_day
    CalendarView.AGENDA -> if (filled) R.drawable.ic_view_agenda_filled else R.drawable.ic_view_agenda
}

private fun CalendarView.label(): Int = when (this) {
    CalendarView.MONTH -> R.string.menu_view_month
    CalendarView.WEEK -> R.string.menu_view_week
    CalendarView.DAY -> R.string.menu_view_day
    CalendarView.AGENDA -> R.string.menu_view_agenda
}

/**
 * The view menu (SPEC §3.4, boards 06 and 07): the views as a single choice; "Event list", only in
 * Month; Calendars and Settings. A tap acts and closes the menu.
 */
@Composable
private fun ViewMenu(
    expanded: Boolean,
    view: CalendarView,
    eventList: Boolean,
    onDismiss: () -> Unit,
    onView: (CalendarView) -> Unit,
    onEventList: (Boolean) -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        val groups = if (view == CalendarView.MONTH) 3 else 2
        val views = CalendarView.entries
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, groups)) {
            views.forEachIndexed { index, item ->
                SelectableDropdownMenuItem(
                    selected = item == view,
                    onClick = { onDismiss(); onView(item) },
                    text = { Text(stringResource(item.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shapes = MenuDefaults.itemShape(index, views.size),
                    leadingIcon = { MenuIcon(item.icon(filled = false)) },
                    selectedLeadingIcon = { MenuIcon(item.icon(filled = true)) },
                    trailingContent = { if (item == view) MenuIcon(R.drawable.ic_check) },
                )
            }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        if (view == CalendarView.MONTH) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, groups)) {
                CheckableDropdownMenuItem(
                    checked = eventList,
                    onCheckedChange = { onDismiss(); onEventList(it) },
                    text = { Text(stringResource(R.string.menu_event_list), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shapes = MenuDefaults.itemShape(0, 1),
                    leadingIcon = { MenuIcon(R.drawable.ic_event_list) },
                    checkedLeadingIcon = { MenuIcon(R.drawable.ic_event_list_filled) },
                    trailingContent = { if (eventList) MenuIcon(R.drawable.ic_check) },
                )
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        }
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(groups - 1, groups)) {
            MenuItem(stringResource(R.string.title_calendars), R.drawable.ic_calendar_month, 0, 2) { onDismiss(); onOpenCalendars() }
            MenuItem(stringResource(R.string.title_settings), R.drawable.ic_settings, 1, 2) { onDismiss(); onOpenSettings() }
        }
    }
}

@Composable
private fun MenuIcon(icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
}

/**
 * Access refused (SPEC §15.2, board 30). While the system still shows its dialog the button asks
 * again; after that only the system's settings can turn the access on, and the note says where.
 */
@Composable
private fun NoAccess(onAnswer: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ask = rememberCalendarAccessAsk(onAnswer)
    // Read on every composition: it changes with each answer, after which the screen is composed again
    val mayAsk = CalendarAccess.mayAsk(context, activity)
    EmptyState(R.drawable.ic_lock, stringResource(R.string.msg_no_access)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (mayAsk) {
                Button(onClick = ask) { Text(stringResource(R.string.button_allow_access)) }
            } else {
                Button(onClick = { CalendarAccess.openAppSettings(context) }) { Text(stringResource(R.string.button_open_app_settings)) }
                Text(
                    stringResource(R.string.msg_no_access_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
