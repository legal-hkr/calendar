package sh.hnet.calendar.model

/** What an Agenda row shows in its time column. */
enum class RowTime {
    /** An all-day event, or a day a longer timed event covers whole. */
    ALL_DAY,
    /** Start over end: a timed event within one day. */
    START_END,
    /** The start only: the first day of a timed event that goes on past midnight. */
    START,
    /** "until" and the end: the last day of such an event. */
    END,
}

/** An occurrence on one of its days. [dayNumber] of [dayCount] is set for an event of several days. */
data class AgendaRow(val occurrence: Occurrence, val day: Int, val time: RowTime, val dayNumber: Int, val dayCount: Int) {
    val multiDay: Boolean get() = dayCount > 1
    /** Stable across reloads, so the list keeps its place when the provider changes. */
    val key: String get() = "${occurrence.eventId}/${occurrence.begin}/$day"
}

/** A day with its rows; without rows only today is listed. */
data class AgendaDay(val day: Int, val rows: List<AgendaRow>)

object Agenda {
    /**
     * The days from [firstDay] to [lastDay] that have events, each with its rows (SPEC §8): an
     * event of several days is listed on each of them, all-day rows first, then by time. Empty days
     * are left out, except [today] and [anchor] – the day the list was asked to go to –, which are
     * always there when in range. Declined events are left out unless [showDeclined].
     */
    fun build(occurrences: List<Occurrence>, firstDay: Int, lastDay: Int, today: Int, showDeclined: Boolean = false, anchor: Int = today): List<AgendaDay> {
        val days = sortedMapOf<Int, MutableList<AgendaRow>>()
        if (today in firstDay..lastDay) days[today] = mutableListOf()
        if (anchor in firstDay..lastDay) days[anchor] = mutableListOf()
        for (occurrence in occurrences) {
            if (occurrence.declined && !showDeclined) continue
            val start = occurrence.startDay
            val end = maxOf(occurrence.endDay, start)
            val count = end - start + 1
            for (day in maxOf(start, firstDay)..minOf(end, lastDay)) {
                val time = when {
                    occurrence.allDay -> RowTime.ALL_DAY
                    count == 1 -> RowTime.START_END
                    day == start -> RowTime.START
                    day == end -> RowTime.END
                    else -> RowTime.ALL_DAY
                }
                days.getOrPut(day) { mutableListOf() } += AgendaRow(occurrence, day, time, day - start + 1, count)
            }
        }
        return days.map { (day, rows) -> AgendaDay(day, rows.sortedWith(ORDER)) }
    }

    /** All-day rows, then events ending today that began earlier, then by start; the title settles ties. */
    private val ORDER = compareBy<AgendaRow>(
        { if (it.time == RowTime.ALL_DAY) 0 else if (it.time == RowTime.END) 1 else 2 },
        { if (it.time == RowTime.ALL_DAY) 0L else if (it.time == RowTime.END) it.occurrence.end else it.occurrence.begin },
        { it.occurrence.end },
        { it.occurrence.title.lowercase() },
        { it.occurrence.eventId },
    )
}
