package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * An event of several days within one week row: one bar across the columns [firstColumn]..[lastColumn],
 * on the line [lane] (0 is the top one), which it keeps across the week.
 */
data class MonthBar(val occurrence: Occurrence, val firstColumn: Int, val lastColumn: Int, val lane: Int, val startsBefore: Boolean, val endsAfter: Boolean) {
    fun covers(column: Int): Boolean = column in firstColumn..lastColumn
}

/** A week row: the bars of longer events, and each day's own events of that one day, in the order they are shown. */
data class MonthWeek(val firstDay: Int, val bars: List<MonthBar>, val days: List<List<Occurrence>>) {
    /** How many events touch the day in [column]. */
    fun count(column: Int): Int = days[column].size + bars.count { it.covers(column) }
}

/** The weeks a month's page shows, from the Julian day [firstDay]. */
data class MonthGrid(val firstDay: Int, val weeks: List<MonthWeek>) {
    val lastDay: Int get() = firstDay + weeks.size * 7 - 1
}

/**
 * What a cell of the full grid shows: its own tiles start on the line [base], under the bars that
 * cover it; [shown] of them are drawn; [more] events are left to "+N", which takes the row's last line.
 */
data class CellFill(val base: Int, val shown: Int, val more: Int)

/** A week row fitted into its lines: the bars that are drawn and each cell's share. */
data class WeekFill(val bars: List<MonthBar>, val cells: List<CellFill>)

/** The lines a tile gets: the start time on a line of its own, and lines of title. */
data class TileLines(val time: Boolean, val title: Int) {
    val count: Int get() = title + if (time) 1 else 0
}

/** A dot under a day's number in the compact grid: a calendar with events that day; [past] when all of them are over. */
data class DayDot(val calendarId: Long, val past: Boolean)

/** The marks of a day in the compact grid; [more] stands for the "+" after the dots. */
data class DayMarks(val dots: List<DayDot>, val more: Boolean)

object Month {
    const val WEEKS = 6

    /** Months are numbered through the years, so the pages of the pager can count them. */
    fun number(date: LocalDate): Int = date.year * 12 + date.monthValue - 1

    fun firstDate(number: Int): LocalDate = LocalDate.of(Math.floorDiv(number, 12), Math.floorMod(number, 12) + 1, 1)

    /** The Julian day the page of the month [number] starts with: the first day of the week its first day is in. */
    fun gridStart(number: Int, weekStart: DayOfWeek): Int {
        val first = firstDate(number)
        return JulianDay.of(first) - (first.dayOfWeek.value - weekStart.value + 7) % 7
    }

    /**
     * The six weeks from [firstDay] (SPEC §6.1). An event of several days – all-day or timed –
     * becomes a bar in each week it touches, longer and earlier bars on the upper lines. Events of one day are
     * listed on it, all-day ones first, then by their start. Declined events are left out unless [showDeclined].
     */
    fun build(occurrences: List<Occurrence>, firstDay: Int, showDeclined: Boolean = false): MonthGrid =
        MonthGrid(firstDay, (0 until WEEKS).map { week(occurrences, firstDay + it * 7, showDeclined) })

    /** The week row from [weekFirst]: Month draws its weeks one by one, each of them once, whichever months it lies in. */
    fun week(occurrences: List<Occurrence>, weekFirst: Int, showDeclined: Boolean = false): MonthWeek {
        val shown = occurrences.filter { (showDeclined || !it.declined) && it.startDay <= weekFirst + 6 && maxOf(it.endDay, it.startDay) >= weekFirst }
        val (long, single) = shown.partition { it.endDay > it.startDay }
        return MonthWeek(weekFirst, bars(long, weekFirst), (0 until 7).map { column -> single.filter { it.startDay == weekFirst + column }.sortedWith(DAY_ORDER) })
    }

    /**
     * The bars of the events among [occurrences] that touch the week from [weekFirst]: each on the first
     * line free over its days, earlier and longer events first.
     */
    fun bars(occurrences: List<Occurrence>, weekFirst: Int): List<MonthBar> {
        val weekLast = weekFirst + 6
        val taken = mutableListOf<BooleanArray>()
        return occurrences.filter { it.startDay <= weekLast && maxOf(it.endDay, it.startDay) >= weekFirst }.sortedWith(BAR_ORDER).map { occurrence ->
            val first = maxOf(occurrence.startDay, weekFirst) - weekFirst
            val last = minOf(maxOf(occurrence.endDay, occurrence.startDay), weekLast) - weekFirst
            var lane = taken.indexOfFirst { line -> (first..last).none { line[it] } }
            if (lane < 0) {
                taken += BooleanArray(7)
                lane = taken.lastIndex
            }
            for (column in first..last) taken[lane][column] = true
            MonthBar(occurrence, first, last, lane, startsBefore = occurrence.startDay < weekFirst, endsAfter = occurrence.endDay > weekLast)
        }
    }

    /**
     * Fits a week row into [slots] one-line places per cell (SPEC §6.1: four at the board's size).
     * A cell whose events don't all fit shows as many as fit less one, and "+N" on the last line. A
     * bar on the last line gives way wherever one of its cells needs that line for "+N", and is then counted there.
     */
    fun fill(week: MonthWeek, slots: Int): WeekFill {
        val base = IntArray(7)
        for (bar in week.bars) for (column in bar.firstColumn..bar.lastColumn) base[column] = maxOf(base[column], bar.lane + 1)
        val overflows = BooleanArray(7) { base[it] + week.days[it].size > slots }
        val visible = week.bars.filter { bar -> bar.lane < slots - 1 || (bar.lane == slots - 1 && (bar.firstColumn..bar.lastColumn).none { overflows[it] }) }
        val cells = (0 until 7).map { column ->
            val hidden = week.bars.count { it.covers(column) && it !in visible }
            val own = week.days[column].size
            if (!overflows[column] && hidden == 0) {
                CellFill(base[column], own, 0)
            } else {
                val shown = (slots - 1 - base[column]).coerceIn(0, own)
                CellFill(base[column], shown, own - shown + hidden)
            }
        }
        return WeekFill(visible, cells)
    }

    /**
     * Spends a cell's spare lines on its tiles (SPEC §6.1, the owner's rule: the time comes before
     * wrapping). Every tile has one line of title already. Of the [extra] lines, first each timed
     * event gets its time line, top to bottom; then the titles get a line each in turn, until each
     * has the lines it wants ([wanted], at most three) or the lines run out.
     */
    fun lines(extra: Int, timed: List<Boolean>, wanted: List<Int>): List<TileLines> {
        var left = extra
        val time = BooleanArray(timed.size)
        val title = IntArray(timed.size) { 1 }
        for (index in timed.indices) {
            if (left <= 0) break
            if (timed[index]) {
                time[index] = true
                left--
            }
        }
        var given = true
        while (left > 0 && given) {
            given = false
            for (index in title.indices) {
                if (left > 0 && title[index] < minOf(wanted[index], MAX_TITLE_LINES)) {
                    title[index]++
                    left--
                    given = true
                }
            }
        }
        return timed.indices.map { TileLines(time[it], title[it]) }
    }

    /**
     * The marks of the day in [column] in the compact grid (SPEC §6.3): a dot per calendar with an
     * event that day which has no bar – up to four, or three and a "+" when the day has more than four events.
     * Only the bars of the top line are drawn there, so longer events below it count as dots.
     */
    fun marks(week: MonthWeek, column: Int, now: Long, today: Int): DayMarks {
        val events = week.bars.filter { it.lane > 0 && it.covers(column) }.map { it.occurrence } + week.days[column]
        val dots = events.groupBy { it.calendarId }.map { (calendar, own) -> DayDot(calendar, own.all { it.isPast(now, today) }) }
        val many = week.count(column) > MAX_DOTS
        return DayMarks(dots.take(if (many) MAX_DOTS - 1 else MAX_DOTS), many)
    }

    const val MAX_TITLE_LINES = 3
    private const val MAX_DOTS = 4

    internal val BAR_ORDER = compareBy<Occurrence>({ it.startDay }, { it.startDay - it.endDay }, { !it.allDay }, { it.begin }, { it.title.lowercase() }, { it.eventId })
    private val DAY_ORDER = compareBy<Occurrence>({ !it.allDay }, { if (it.allDay) 0L else it.begin }, { it.end }, { it.title.lowercase() }, { it.eventId })
}
