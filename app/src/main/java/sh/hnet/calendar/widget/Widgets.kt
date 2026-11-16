package sh.hnet.calendar.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.MainActivity
import sh.hnet.calendar.R
import sh.hnet.calendar.model.AgendaPiece
import sh.hnet.calendar.model.AgendaRow
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarView
import sh.hnet.calendar.model.DayWord
import sh.hnet.calendar.model.JulianDay
import sh.hnet.calendar.model.Month
import sh.hnet.calendar.model.MonthCell
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.RowTime
import sh.hnet.calendar.model.WidgetPlan
import sh.hnet.calendar.ui.calendar.CalendarFormats
import sh.hnet.calendar.ui.calendar.systemWeekStart
import sh.hnet.calendar.ui.theme.bareRes
import sh.hnet.calendar.ui.theme.fillRes
import sh.hnet.calendar.ui.theme.onRes

/** What a tap on a widget asks the app for, carried as the intent's address so that each tap's intent is its own. */
sealed interface WidgetRequest {
    /** An occurrence's page. */
    data class Event(val eventId: Long, val begin: Long, val end: Long) : WidgetRequest

    /** The main screen in [view] on the Julian [day]. */
    data class Day(val day: Int, val view: CalendarView) : WidgetRequest

    /** The editor for a new event. */
    data object NewEvent : WidgetRequest

    /** The app as it is: it shows its own state when access or calendars are missing (§17.10). */
    data object Open : WidgetRequest

    companion object {
        const val SCHEME = "calendar-widget"

        fun of(address: Uri?): WidgetRequest? {
            if (address?.scheme != SCHEME) return null
            fun long(name: String) = address.getQueryParameter(name)?.toLongOrNull()
            return when (address.host) {
                "event" -> long("id")?.let { Event(it, long("begin") ?: 0, long("end") ?: 0) }
                "day" -> long("day")?.let { Day(it.toInt(), CalendarView.of(address.getQueryParameter("view"))) }
                "new" -> NewEvent
                "open" -> Open
                else -> null
            }
        }

        fun address(request: WidgetRequest): Uri = Uri.Builder().scheme(SCHEME).apply {
            when (request) {
                is Event -> authority("event").appendQueryParameter("id", request.eventId.toString())
                    .appendQueryParameter("begin", request.begin.toString()).appendQueryParameter("end", request.end.toString())
                is Day -> authority("day").appendQueryParameter("day", request.day.toString()).appendQueryParameter("view", request.view.key)
                NewEvent -> authority("new")
                Open -> authority("open")
            }
        }.build()
    }
}

/**
 * The home-screen widgets (SPEC §17; stage 10: Agenda, Next event, Month, Upcoming events; stage 11:
 * Upcoming events and Agenda without a background), plain
 * remote views as News's and Clock's. They show the calendars the app shows, in their colors
 * (§17.11), leave out what is over, canceled or declined, and never scroll (the owner, 16 Nov 2026).
 * Colors are resources the launcher resolves – calendar fills too, set by id –, so a change of
 * theme needs no redraw. Texts are set here: a layout's own strings would come out in the
 * launcher's language rather than the app's.
 *
 * Redrawn (§17.12) after every look at the calendar the reminders take ([sh.hnet.calendar.reminders.ReminderScheduler.sync]:
 * a change in the provider – a sync with the app closed too –, the calendar choices, a restart of
 * the phone, the clock, the time zone, the language), when the app leaves the screen (settings
 * such as the week's first day), when the launcher asks or a widget is resized, and by an alarm of
 * their own at the nearest end of an event they may show or at midnight.
 */
object Widgets {
    /** Asked for a redraw; several requests waiting become one. */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val waiting = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean(false)

    /**
     * Redraws every placed widget. The redraws run one after another, never two at once, and
     * requests that come while one runs are met by a single redraw after it.
     *
     * @param done Called once a redraw that started after this request has finished: a broadcast receiver keeps its process until then
     */
    fun update(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as CalendarApplication
        done?.let { waiting += it }
        if (started.compareAndSet(false, true)) {
            app.appScope.launch {
                for (request in requests) {
                    // Those asked before this redraw starts are answered by it
                    val answered = generateSequence { waiting.poll() }.toList()
                    try {
                        draw(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Drawn again at the next request; a widget must never take the app down
                    } finally {
                        answered.forEach { runCatching(it) }
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    /** As [update], returning when the redraw is done: a receiver or a job that started it ends only then. */
    suspend fun updateAndWait(context: Context) = suspendCancellableCoroutine { continuation -> update(context) { continuation.resume(Unit) } }

    private suspend fun draw(app: CalendarApplication) {
        val manager = AppWidgetManager.getInstance(app)
        fun ids(provider: Class<*>) = manager.getAppWidgetIds(ComponentName(app, provider))
        val agenda = ids(AgendaWidget::class.java)
        val next = ids(NextEventWidget::class.java)
        val month = ids(MonthWidget::class.java)
        val upcoming = ids(UpcomingWidget::class.java)
        val upcomingBare = ids(UpcomingBareWidget::class.java)
        val agendaBare = ids(AgendaBareWidget::class.java)
        if (agenda.isEmpty() && next.isEmpty() && month.isEmpty() && upcoming.isEmpty() && upcomingBare.isEmpty() && agendaBare.isEmpty()) {
            scheduleChange(app, null)
            return
        }

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val todayDate = LocalDate.now(zone)
        val today = JulianDay.of(todayDate)
        val nextMidnight = todayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Set however the drawing ends: a failed redraw must not leave the widgets without their next one – at midnight at the latest
        var change = nextMidnight
        try {
            val words = Words(app, zone)
            val settings = app.preferences.viewSettings.first()
            val firstWeekday = settings.weekStart ?: systemWeekStart(app, words.locale)
            val monthNumber = Month.number(todayDate)
            val gridStart = Month.gridStart(monthNumber, firstWeekday)

            // One reading for all: the month's six weeks and the days the lists look ahead
            val access = app.calendars.hasAccess()
            val calendars = if (access) runCatching { CalendarChoices.apply(app.calendars.calendars(), app.calendarChoices.choices.first()) }.getOrDefault(emptyList()) else emptyList()
            val shown = calendars.filter { it.shown }
            val colors = shown.associate { it.id to it.color }
            val occurrences = if (shown.isEmpty()) emptyList() else runCatching {
                app.calendars.occurrences(minOf(gridStart, today), maxOf(gridStart + Month.WEEKS * 7 - 1, today + WidgetPlan.AHEAD_DAYS), colors.keys)
            }.getOrDefault(emptyList())
            // Without access or a calendar to show, every tap opens the app, which tells what is missing (§17.10)
            val ready = shown.isNotEmpty()
            val canWrite = calendars.any { it.writable }

            val drawn = HashMap<Int, RemoteViews>()
            val soon = WidgetPlan.upcoming(occurrences, now, today, 2)
            val days = if (agenda.isNotEmpty() || agendaBare.isNotEmpty()) WidgetPlan.agendaDays(occurrences, now, today) else emptyList()
            if (agenda.isNotEmpty()) {
                agenda.forEach { id -> drawn[id] = agenda(app, words, WidgetPlan.fit(days, manager.getAppWidgetOptions(id).height() - LIST_PADDING_DP), colors, today, ready, canWrite) }
            }
            next.forEach { id -> drawn[id] = next(app, words, soon.firstOrNull(), colors, today, ready) }
            if (month.isNotEmpty()) {
                val cells = WidgetPlan.month(occurrences, monthNumber, firstWeekday)
                val views = month(app, words, cells, colors, today, todayDate, firstWeekday, ready)
                month.forEach { id -> drawn[id] = views }
            }
            upcoming.forEach { id -> drawn[id] = upcoming(app, words, soon, colors, today, ready) }
            upcomingBare.forEach { id -> drawn[id] = upcomingBare(app, words, soon, colors, today, ready) }
            // Today is always the first of the days
            val todays = days.firstOrNull { it.day == today }?.rows.orEmpty()
            agendaBare.forEach { id -> drawn[id] = agendaBare(app, words, todays, WidgetPlan.bareLines(manager.getAppWidgetOptions(id).height()), colors, today, ready) }

            withContext(Dispatchers.Main) {
                drawn.forEach { (id, views) ->
                    try {
                        manager.updateAppWidget(id, views)
                    } catch (_: RuntimeException) {
                        // The system refused these views: the widget keeps what it shows
                    }
                }
            }
            change = WidgetPlan.nextChange(occurrences, now, nextMidnight)
        } finally {
            scheduleChange(app, change)
        }
    }

    /**
     * One alarm for the nearest moment the widgets change by themselves – an event they may show
     * ends, or a new day –; none while no widget is placed. It doesn't wake the phone: an asleep
     * screen shows no widget, and the alarm comes as it wakes.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleChange(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, REQUEST_CHANGE, Intent(context, AgendaWidget::class.java).setAction(ACTION_CHANGE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        when {
            at == null -> alarms.cancel(intent)
            // `USE_EXACT_ALARM` is the app's from install (see ReminderScheduler); a late redraw is better than none
            alarms.canScheduleExactAlarms() -> alarms.setExact(AlarmManager.RTC, at, intent)
            else -> alarms.set(AlarmManager.RTC, at, intent)
        }
    }

    /** The widget's height in dp as the launcher reports it (in portrait), or what three cells have when it doesn't. */
    private fun Bundle.height(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 300

    /** Agenda, 4 × 3 (§17.3): days with their strips, as many whole pieces as fit. */
    private fun agenda(context: Context, words: Words, pieces: List<AgendaPiece>, colors: Map<Long, CalendarColor>, today: Int, ready: Boolean, canWrite: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_agenda).apply {
            removeAllViews(R.id.list)
            for (piece in pieces) {
                when (piece) {
                    is AgendaPiece.Header -> addView(R.id.list, RemoteViews(context.packageName, R.layout.widget_agenda_header).apply {
                        if (piece.first) setViewPadding(R.id.header, 0, 0, 0, 0)
                        badge(context, words.agendaBadge(context, piece.day, today))
                        setContentDescription(R.id.badge, context.getString(R.string.content_description_widget_open_day, words.longDay(piece.day)))
                        setOnClickPendingIntent(R.id.badge, open(context, if (ready) WidgetRequest.Day(piece.day, CalendarView.DAY) else WidgetRequest.Open))
                        setViewVisibility(R.id.new_event, if (piece.first) View.VISIBLE else View.GONE)
                        if (piece.first) {
                            setContentDescription(R.id.new_event, context.getString(R.string.content_description_widget_new_event))
                            setOnClickPendingIntent(R.id.new_event, open(context, if (canWrite) WidgetRequest.NewEvent else WidgetRequest.Open))
                        }
                    })
                    is AgendaPiece.Nothing -> addView(R.id.list, RemoteViews(context.packageName, R.layout.widget_agenda_nothing).apply {
                        setTextViewText(R.id.title, context.getString(R.string.widget_nothing_planned))
                        setOnClickPendingIntent(R.id.strip, open(context, if (ready) WidgetRequest.Day(piece.day, CalendarView.DAY) else WidgetRequest.Open))
                    })
                    is AgendaPiece.Strip -> addView(R.id.list, strip(context, words, piece, colors))
                }
            }
        }

    private fun strip(context: Context, words: Words, piece: AgendaPiece.Strip, colors: Map<Long, CalendarColor>): RemoteViews {
        val row = piece.row
        val occurrence = row.occurrence
        val color = colors[occurrence.calendarId] ?: CalendarColor.GRAPHITE
        return RemoteViews(context.packageName, R.layout.widget_agenda_strip).apply {
            setColorStateList(R.id.tile_fill, "setImageTintList", color.fillRes())
            // Start over end; or words on two lines where there is no time of this day to give
            val tileWords = when (row.time) {
                RowTime.ALL_DAY -> context.getString(R.string.widget_all_day_short)
                RowTime.END -> context.getString(R.string.label_until_time, words.formats.time(occurrence.end))
                else -> null
            }
            setViewVisibility(R.id.tile_times, if (tileWords == null) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.tile_words, if (tileWords == null) View.GONE else View.VISIBLE)
            if (tileWords != null) {
                setTextViewText(R.id.tile_words, tileWords.replaceFirst(' ', '\n'))
                setColorStateList(R.id.tile_words, "setTextColor", color.onRes())
            } else {
                setTextViewText(R.id.tile_start, words.formats.tileTime(occurrence.begin))
                setColorStateList(R.id.tile_start, "setTextColor", color.onRes())
                // The first day of an event that goes on past midnight has only its start
                setViewVisibility(R.id.tile_end, if (row.time == RowTime.START_END) View.VISIBLE else View.GONE)
                if (row.time == RowTime.START_END) {
                    setTextViewText(R.id.tile_end, words.formats.tileTime(occurrence.end))
                    setColorStateList(R.id.tile_end, "setTextColor", color.onRes())
                }
            }
            val title = words.title(context, occurrence)
            setTextViewText(R.id.title, title)
            // Two lines of title, or one with the place under it
            val place = occurrence.location.isNotEmpty()
            setInt(R.id.title, "setMaxLines", if (place) 1 else 2)
            setViewVisibility(R.id.place, if (place) View.VISIBLE else View.GONE)
            if (place) setTextViewText(R.id.place, occurrence.location)
            val time = tileWords ?: words.range(occurrence)
            setContentDescription(R.id.strip, context.getString(R.string.content_description_widget_event, title, time))
            setOnClickPendingIntent(R.id.strip, open(context, WidgetRequest.Event(occurrence.eventId, occurrence.begin, occurrence.end)))
        }
    }

    /** Next event, 2 × 2 (§17.4): the nearest timed event, or "Nothing planned" under today's badge. */
    private fun next(context: Context, words: Words, event: Occurrence?, colors: Map<Long, CalendarColor>, today: Int, ready: Boolean): RemoteViews {
        if (event == null) {
            return RemoteViews(context.packageName, R.layout.widget_next_nothing).apply {
                badge(context, context.getString(R.string.label_today))
                setTextViewText(R.id.title, context.getString(R.string.widget_nothing_planned))
                setContentDescription(R.id.badge, context.getString(R.string.content_description_widget_open_day, words.longDay(today)))
                val request = if (ready) WidgetRequest.Day(today, CalendarView.DAY) else WidgetRequest.Open
                setOnClickPendingIntent(R.id.badge, open(context, request))
                setOnClickPendingIntent(R.id.frame, open(context, request))
            }
        }
        val color = colors[event.calendarId] ?: CalendarColor.GRAPHITE
        // An event running since an earlier day is today's
        val day = maxOf(event.startDay, today)
        return RemoteViews(context.packageName, R.layout.widget_next).apply {
            badge(context, words.nextBadge(context, day, today))
            setContentDescription(R.id.badge, context.getString(R.string.content_description_widget_open_day, words.longDay(day)))
            setOnClickPendingIntent(R.id.badge, open(context, WidgetRequest.Day(day, CalendarView.DAY)))
            setColorStateList(R.id.time_fill, "setImageTintList", color.fillRes())
            setTextViewText(R.id.time, words.range(event))
            setColorStateList(R.id.time, "setTextColor", color.onRes())
            val title = words.title(context, event)
            setTextViewText(R.id.title, title)
            setViewVisibility(R.id.place, if (event.location.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.place, event.location)
            setContentDescription(R.id.frame, context.getString(R.string.content_description_widget_event, title, words.range(event)))
            setOnClickPendingIntent(R.id.frame, open(context, WidgetRequest.Event(event.eventId, event.begin, event.end)))
        }
    }

    /** Month, 4 × 4 (§17.5): this month's six weeks with up to three dots a day. */
    private fun month(context: Context, words: Words, cells: List<MonthCell>, colors: Map<Long, CalendarColor>, today: Int, todayDate: LocalDate, firstWeekday: DayOfWeek, ready: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_month).apply {
            badge(context, words.formats.month(todayDate), R.drawable.ic_calendar_month)
            setContentDescription(R.id.badge, context.getString(R.string.content_description_widget_open_day, words.formats.month(todayDate)))
            setOnClickPendingIntent(R.id.badge, open(context, if (ready) WidgetRequest.Day(today, CalendarView.MONTH) else WidgetRequest.Open))
            WEEKDAYS.forEachIndexed { index, view -> setTextViewText(view, words.formats.weekday(firstWeekday.plus(index.toLong()))) }
            WEEKS.forEachIndexed { week, row ->
                removeAllViews(row)
                for (cell in cells.subList(week * 7, week * 7 + 7)) {
                    addView(row, RemoteViews(context.packageName, R.layout.widget_month_cell).apply {
                        val number = JulianDay.toDate(cell.day).dayOfMonth.toString()
                        val isToday = cell.day == today
                        setViewVisibility(R.id.today, if (isToday) View.VISIBLE else View.GONE)
                        setViewVisibility(R.id.number, if (isToday) View.GONE else View.VISIBLE)
                        setTextViewText(if (isToday) R.id.today else R.id.number, number)
                        if (!cell.inMonth) setColorStateList(R.id.number, "setTextColor", R.color.widget_outside_month)
                        DOTS.forEachIndexed { index, dot ->
                            val calendar = cell.dots.getOrNull(index)
                            setViewVisibility(dot, if (calendar == null) View.GONE else View.VISIBLE)
                            if (calendar != null) setColorStateList(dot, "setImageTintList", (colors[calendar] ?: CalendarColor.GRAPHITE).fillRes())
                        }
                        setContentDescription(R.id.cell, context.getString(R.string.content_description_widget_open_day, words.longDay(cell.day)))
                        setOnClickPendingIntent(R.id.cell, open(context, if (ready) WidgetRequest.Day(cell.day, CalendarView.DAY) else WidgetRequest.Open))
                    })
                }
            }
        }

    /** Upcoming events, 4 × 1 (§17.6): the nearest event in bold and the one after it. */
    private fun upcoming(context: Context, words: Words, events: List<Occurrence>, colors: Map<Long, CalendarColor>, today: Int, ready: Boolean): RemoteViews {
        if (events.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_upcoming_nothing).apply {
                setTextViewText(R.id.first_title, context.getString(R.string.widget_nothing_planned))
                setOnClickPendingIntent(R.id.first, open(context, if (ready) WidgetRequest.Day(today, CalendarView.DAY) else WidgetRequest.Open))
            }
        }
        return RemoteViews(context.packageName, R.layout.widget_upcoming).apply {
            listOf(
                Triple(R.id.first, R.id.first_dot, R.id.first_time to R.id.first_title),
                Triple(R.id.second, R.id.second_dot, R.id.second_time to R.id.second_title),
            ).forEachIndexed { index, (pill, dot, texts) ->
                val event = events.getOrNull(index)
                setViewVisibility(pill, if (event == null) View.GONE else View.VISIBLE)
                if (event == null) return@forEachIndexed
                setColorStateList(dot, "setImageTintList", (colors[event.calendarId] ?: CalendarColor.GRAPHITE).fillRes())
                val time = words.dayAndTime(context, event, today)
                val title = words.title(context, event)
                setTextViewText(texts.first, time)
                setTextViewText(texts.second, title)
                setContentDescription(pill, context.getString(R.string.content_description_widget_event, title, time))
                setOnClickPendingIntent(pill, open(context, WidgetRequest.Event(event.eventId, event.begin, event.end)))
            }
        }
    }

    /** Upcoming events without a background, 4 × 1 (§17.7): the nearest event in bold and the one after it, white on the wallpaper. */
    private fun upcomingBare(context: Context, words: Words, events: List<Occurrence>, colors: Map<Long, CalendarColor>, today: Int, ready: Boolean): RemoteViews {
        if (events.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_upcoming_bare_nothing).apply {
                setTextViewText(R.id.nothing, context.getString(R.string.widget_nothing_planned))
                setOnClickPendingIntent(R.id.nothing, open(context, if (ready) WidgetRequest.Day(today, CalendarView.DAY) else WidgetRequest.Open))
            }
        }
        return RemoteViews(context.packageName, R.layout.widget_upcoming_bare).apply {
            listOf(
                listOf(R.id.first_line, R.id.first_dot, R.id.first_time, R.id.first_title),
                listOf(R.id.second_line, R.id.second_dot, R.id.second_time, R.id.second_title),
            ).forEachIndexed { index, (line, dot, timeView, titleView) ->
                val event = events.getOrNull(index)
                setViewVisibility(line, if (event == null) View.GONE else View.VISIBLE)
                if (event == null) return@forEachIndexed
                setColorStateList(dot, "setImageTintList", (colors[event.calendarId] ?: CalendarColor.GRAPHITE).bareRes())
                val time = words.dayAndTime(context, event, today)
                val title = words.title(context, event)
                setTextViewText(timeView, time)
                setTextViewText(titleView, title)
                setContentDescription(line, context.getString(R.string.content_description_widget_event, title, time))
                setOnClickPendingIntent(line, open(context, WidgetRequest.Event(event.eventId, event.begin, event.end)))
            }
        }
    }

    /**
     * Agenda without a background, 3 × 2 (§17.8): today's date and one line per event of today
     * not over yet – all-day ones first, as in the Agenda –, as many as [lines] says fit; else
     * "Nothing planned" under the date.
     */
    private fun agendaBare(context: Context, words: Words, rows: List<AgendaRow>, lines: Int, colors: Map<Long, CalendarColor>, today: Int, ready: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_agenda_bare).apply {
            removeAllViews(R.id.list)
            addView(R.id.list, RemoteViews(context.packageName, R.layout.widget_agenda_bare_date).apply {
                setTextViewText(R.id.date, words.longDay(today))
                setOnClickPendingIntent(R.id.date, open(context, if (ready) WidgetRequest.Day(today, CalendarView.DAY) else WidgetRequest.Open))
            })
            if (rows.isEmpty()) {
                addView(R.id.list, RemoteViews(context.packageName, R.layout.widget_agenda_bare_line).apply {
                    setViewVisibility(R.id.dot, View.GONE)
                    setViewVisibility(R.id.time, View.GONE)
                    setTextViewText(R.id.title, context.getString(R.string.widget_nothing_planned))
                    setOnClickPendingIntent(R.id.line, open(context, if (ready) WidgetRequest.Day(today, CalendarView.DAY) else WidgetRequest.Open))
                })
            }
            for (row in rows.take(lines)) {
                val event = row.occurrence
                addView(R.id.list, RemoteViews(context.packageName, R.layout.widget_agenda_bare_line).apply {
                    setColorStateList(R.id.dot, "setImageTintList", (colors[event.calendarId] ?: CalendarColor.GRAPHITE).bareRes())
                    val time = when (row.time) {
                        RowTime.ALL_DAY -> context.getString(R.string.widget_all_day_short)
                        RowTime.END -> context.getString(R.string.label_until_time, words.formats.time(event.end))
                        else -> words.formats.time(event.begin)
                    }
                    val title = words.title(context, event)
                    setTextViewText(R.id.time, time)
                    setTextViewText(R.id.title, title)
                    setContentDescription(R.id.line, context.getString(R.string.content_description_widget_event, title, time))
                    setOnClickPendingIntent(R.id.line, open(context, WidgetRequest.Event(event.eventId, event.begin, event.end)))
                })
            }
        }

    /** The included badge's text and symbol. */
    private fun RemoteViews.badge(context: Context, text: String, icon: Int = R.drawable.ic_event) {
        setTextViewText(R.id.badge_text, text)
        setImageViewResource(R.id.badge_icon, icon)
    }

    /** Opens the app on what a tap asks for; the address tells the intents apart. */
    private fun open(context: Context, request: WidgetRequest): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, WidgetRequest.address(request), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Dates and times in the app's language and the system's clock (SPEC §2.8), as the app's views write them. */
    private class Words(context: Context, zone: ZoneId) {
        val locale: Locale = context.resources.configuration.locales[0]
        val formats = CalendarFormats(locale, DateFormat.is24HourFormat(context), zone)
        private fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        private val shortDay = pattern("EEEMMMd")
        private val weekday = pattern("EEE")
        private val date = pattern("MMMd")

        /** "Wed, Nov 18". */
        fun shortDay(day: Int): String = formats.capitalize(shortDay.format(JulianDay.toDate(day)))

        /** "Wednesday, November 18", for what is read aloud. */
        fun longDay(day: Int): String = formats.capitalize(formats.day(JulianDay.toDate(day), JulianDay.toDate(day).year))

        /** Agenda's rows (§17.3): today's date, then "Tomorrow", then the dates. */
        fun agendaBadge(context: Context, day: Int, today: Int): String = if (day == today + 1) context.getString(R.string.label_tomorrow) else shortDay(day)

        /** Next event's badge (§17.4): "Today", "Tomorrow" or the date. */
        fun nextBadge(context: Context, day: Int, today: Int): String = when (day - today) {
            0 -> context.getString(R.string.label_today)
            1 -> context.getString(R.string.label_tomorrow)
            else -> shortDay(day)
        }

        /** "10:00–11:00". */
        fun range(event: Occurrence): String = "${formats.time(event.begin)}–${formats.time(event.end)}"

        /** The start time with its day before it unless it is today (§17.6): "tomorrow 08:00", "Thu 08:00", "Nov 27 08:00". */
        fun dayAndTime(context: Context, event: Occurrence, today: Int): String {
            val time = formats.time(event.begin)
            val date = JulianDay.toDate(event.startDay)
            return when (WidgetPlan.dayWord(event.startDay, today)) {
                DayWord.TODAY -> time
                DayWord.TOMORROW -> context.getString(R.string.widget_day_and_time, context.getString(R.string.widget_tomorrow_lowercase), time)
                DayWord.WEEKDAY -> context.getString(R.string.widget_day_and_time, weekday.format(date), time)
                DayWord.DATE -> context.getString(R.string.widget_day_and_time, this.date.format(date), time)
            }
        }

        /** An event without a title is named as the app names it. */
        fun title(context: Context, event: Occurrence): String = event.title.ifEmpty { context.getString(R.string.label_no_title) }
    }

    private const val REQUEST_CHANGE = 1
    const val ACTION_CHANGE = "sh.hnet.calendar.widget.CHANGE"
    /** The Agenda list's padding above and below, which the pieces don't get. */
    private const val LIST_PADDING_DP = 4
    private val WEEKDAYS = listOf(R.id.weekday0, R.id.weekday1, R.id.weekday2, R.id.weekday3, R.id.weekday4, R.id.weekday5, R.id.weekday6)
    private val WEEKS = listOf(R.id.week0, R.id.week1, R.id.week2, R.id.week3, R.id.week4, R.id.week5)
    private val DOTS = listOf(R.id.dot0, R.id.dot1, R.id.dot2)
}

/**
 * A widget's provider: every widget is redrawn with all the others. The redraw runs off the
 * receiver's thread, so the receiver asks the system to keep the process until it is done.
 */
abstract class CalendarWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_CHANGE) redraw(context) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: the Agenda widget fits its pieces to the new height. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    /** The last one removed: the alarm goes with the next redraw, which finds no widget. */
    override fun onDisabled(context: Context) = redraw(context)

    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

/** Agenda, 4 × 3 (SPEC §17.3). */
class AgendaWidget : CalendarWidget()

/** Next event, 2 × 2 (SPEC §17.4). */
class NextEventWidget : CalendarWidget()

/** Month, 4 × 4 (SPEC §17.5). */
class MonthWidget : CalendarWidget()

/** Upcoming events, 4 × 1 (SPEC §17.6). */
class UpcomingWidget : CalendarWidget()

/** Upcoming events without a background, 4 × 1 (SPEC §17.7). */
class UpcomingBareWidget : CalendarWidget()

/** Agenda without a background, 3 × 2 (SPEC §17.8). */
class AgendaBareWidget : CalendarWidget()
