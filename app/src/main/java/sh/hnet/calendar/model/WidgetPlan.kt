package sh.hnet.calendar.model

import java.time.DayOfWeek

/** A piece of the Agenda widget, top to bottom. */
sealed interface AgendaPiece {
    /** A day's own row with its date badge; the [first] one also holds the new event pill. */
    data class Header(val day: Int, val first: Boolean) : AgendaPiece

    /** An event's strip on one of its days. */
    data class Strip(val row: AgendaRow) : AgendaPiece

    /** "Nothing planned" under today's badge when today has nothing left. */
    data class Nothing(val day: Int) : AgendaPiece
}

/** A day of the Month widget: its number, whether it is in the month shown, and the calendars of up to three of its events. */
data class MonthCell(val day: Int, val inMonth: Boolean, val dots: List<Long>)

/** How the Upcoming events widget names the day of an event's start before its time. */
enum class DayWord {
    /** Nothing before the time. */
    TODAY,

    /** "tomorrow 08:00". */
    TOMORROW,

    /** Within the coming week: the short weekday, "Thu 08:00". */
    WEEKDAY,

    /** Further, or a day already gone (an event running since then): the date, "Nov 27 08:00". */
    DATE,
}

/**
 * What the widgets show (SPEC §17, the logic agreed with the owner 16 Nov 2026). Widgets inform and
 * leave the work to the app: no scrolling, events over or canceled and declined invitations are
 * left out, and they follow the calendars the app shows.
 */
object WidgetPlan {
    /** How many days the Agenda widget lists, today first. */
    const val AGENDA_DAYS = 14

    /** How far ahead Next event and Upcoming events look. */
    const val AHEAD_DAYS = 31

    /** A day's own row and a strip, in dp (§17.2, §17.3), and the space between any two pieces. */
    const val HEADER_DP = 30
    const val STRIP_DP = 56
    const val GAP_DP = 6

    private const val MAX_DOTS = 3

    /** What a widget shows at all: neither a canceled event nor an invitation the user declined. */
    fun shown(occurrence: Occurrence): Boolean = !occurrence.canceled && !occurrence.declined

    /**
     * The next [count] timed events from [now] on, starting no later than [AHEAD_DAYS] from [today]
     * (§17.4, §17.6). One already running counts until it ends. All-day events are left to the
     * widgets that show days.
     */
    fun upcoming(occurrences: List<Occurrence>, now: Long, today: Int, count: Int): List<Occurrence> = occurrences
        .filter { shown(it) && !it.allDay && it.end > now && it.startDay < today + AHEAD_DAYS }
        .distinctBy { it.eventId to it.begin }
        .sortedWith(compareBy<Occurrence>({ it.begin }, { it.end }, { it.title.lowercase() }, { it.eventId }))
        .take(count)

    /**
     * The days of the Agenda widget (§17.3): today always, then the days with events up to
     * [AGENDA_DAYS] ahead, without the events that are over.
     */
    fun agendaDays(occurrences: List<Occurrence>, now: Long, today: Int): List<AgendaDay> =
        Agenda.build(occurrences.filter { shown(it) && !it.isPast(now, today) }, today, today + AGENDA_DAYS - 1, today)

    /**
     * The pieces of [days] that fit whole into [heightDp] (the owner's rule of 16 Nov 2026: no
     * scrolling, and no part of a further strip at the bottom). A day's row never stands at the
     * bottom without a strip under it. The first day's row and its first strip are there however
     * small the widget is.
     */
    fun fit(days: List<AgendaDay>, heightDp: Int): List<AgendaPiece> {
        val pieces = mutableListOf<AgendaPiece>()
        var used = 0
        for (day in days) {
            val header = (if (pieces.isEmpty()) 0 else GAP_DP) + HEADER_DP
            if (pieces.isNotEmpty() && used + header + GAP_DP + STRIP_DP > heightDp) break
            pieces += AgendaPiece.Header(day.day, pieces.isEmpty())
            used += header
            if (day.rows.isEmpty()) {
                pieces += AgendaPiece.Nothing(day.day)
                used += GAP_DP + STRIP_DP
                continue
            }
            for ((index, row) in day.rows.withIndex()) {
                if (index > 0 && used + GAP_DP + STRIP_DP > heightDp) return pieces
                pieces += AgendaPiece.Strip(row)
                used += GAP_DP + STRIP_DP
            }
        }
        return pieces
    }

    /**
     * The six weeks of the Month widget for the month [number] (§17.5), each day with the calendars
     * of its first three events: all-day ones first, then by their start. An event of several days
     * marks each of them.
     */
    fun month(occurrences: List<Occurrence>, number: Int, weekStart: DayOfWeek): List<MonthCell> {
        val first = Month.gridStart(number, weekStart)
        val month = Month.firstDate(number).monthValue
        val shown = occurrences.filter(::shown).sortedWith(compareBy<Occurrence>({ !it.allDay }, { if (it.allDay) 0L else it.begin }, { it.title.lowercase() }, { it.eventId }))
        return (first until first + Month.WEEKS * 7).map { day ->
            val dots = shown.filter { it.startDay <= day && maxOf(it.endDay, it.startDay) >= day }.take(MAX_DOTS).map { it.calendarId }
            MonthCell(day, JulianDay.toDate(day).monthValue == month, dots)
        }
    }

    /** How the day [day] is named before an event's time, seen from [today]. */
    fun dayWord(day: Int, today: Int): DayWord = when (day - today) {
        0 -> DayWord.TODAY
        1 -> DayWord.TOMORROW
        in 2..6 -> DayWord.WEEKDAY
        else -> DayWord.DATE
    }

    /**
     * When the widgets must be drawn again by themselves (§17.12): the nearest end of a timed event
     * they may show – it leaves them then –, or [nextMidnight], when the days move on.
     */
    fun nextChange(occurrences: List<Occurrence>, now: Long, nextMidnight: Long): Long =
        occurrences.filter { shown(it) && !it.allDay && it.end > now }.minOfOrNull { it.end }?.coerceAtMost(nextMidnight) ?: nextMidnight
}
