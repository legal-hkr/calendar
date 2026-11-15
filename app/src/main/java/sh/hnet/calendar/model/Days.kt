package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * A timed event's part within one day, cut at midnight: from the minute [start] to the minute [end]
 * of the day. [startsHere] is false on the days an event that began earlier goes on.
 */
data class DaySegment(val occurrence: Occurrence, val start: Int, val end: Int, val startsHere: Boolean)

/**
 * A segment placed in the time grid: from [top] to [bottom], in minutes of the day – at least the
 * smallest tile high –, in the column [column] of the [columns] its group of overlapping events shares.
 */
data class PlacedSegment(val segment: DaySegment, val top: Float, val bottom: Float, val column: Int, val columns: Int)

/** "+N" for the events at one time that got no column (SPEC §7.7), at the foot of the last column. */
data class MoreMark(val count: Int, val top: Float, val bottom: Float, val column: Int, val columns: Int)

/** A day's time grid. [columns] is the most columns any of its groups needs. */
data class DayLayout(val tiles: List<PlacedSegment>, val more: List<MoreMark>, val columns: Int)

object Days {
    const val MINUTES = 24 * 60
    private const val DAY_MILLIS = 86_400_000L

    /** The first day of the week [day] is in. */
    fun weekFirst(day: Int, weekStart: DayOfWeek): Int = day - (JulianDay.toDate(day).dayOfWeek.value - weekStart.value + 7) % 7

    /** All-day events and those of 24 hours and more go to the strip above the time grid (SPEC §7.8). */
    fun inStrip(occurrence: Occurrence): Boolean = occurrence.allDay || occurrence.end - occurrence.begin >= DAY_MILLIS

    /** The strip of the week from [weekFirst]: its events as bars, each on a line it keeps across the days. */
    fun weekStrip(occurrences: List<Occurrence>, weekFirst: Int, showDeclined: Boolean = false): MonthWeek =
        MonthWeek(weekFirst, Month.bars(occurrences.filter { inStrip(it) && (showDeclined || !it.declined) }, weekFirst), List(7) { emptyList() })

    /** The strip of one day: its events in the order of their lines. */
    fun dayStrip(occurrences: List<Occurrence>, day: Int, showDeclined: Boolean = false): List<Occurrence> =
        occurrences.filter { inStrip(it) && (showDeclined || !it.declined) && it.startDay <= day && maxOf(it.endDay, it.startDay) >= day }.sortedWith(Month.BAR_ORDER)

    /** The timed events of the Julian [day] in [zone], each cut to the day (SPEC §7.8). */
    fun segments(occurrences: List<Occurrence>, day: Int, zone: ZoneId, showDeclined: Boolean = false): List<DaySegment> {
        val date = JulianDay.toDate(day)
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun minute(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        return occurrences
            // An event that ended at midnight is not on the new day; one without length that starts then is
            .filter { !inStrip(it) && (showDeclined || !it.declined) && it.begin < dayEnd && (it.end > dayStart || it.begin >= dayStart) }
            .map {
                val start = if (it.begin <= dayStart) 0 else minute(it.begin)
                val end = if (it.end >= dayEnd) MINUTES else maxOf(start, minute(it.end))
                DaySegment(it, start, end, it.begin >= dayStart)
            }
    }

    /**
     * Places a day's segments (SPEC §7.6, §7.7). A tile is at least [minMinutes] high. Events that
     * overlap – as drawn, so with that smallest height – form a group and stand side by side in
     * columns of equal width. With more than [maxColumns] columns (two in Week) the further events
     * are left out and counted by a mark of [moreMinutes] at the foot of the last column, where they
     * end; a tile there gives the mark its room, or joins the count when too little of it would be left.
     */
    fun layout(segments: List<DaySegment>, minMinutes: Float, maxColumns: Int = Int.MAX_VALUE, moreMinutes: Float = 0f): DayLayout {
        class Item(val segment: DaySegment, val top: Float, var bottom: Float, var column: Int = 0)

        val items = segments.sortedWith(ORDER).map {
            val top = minOf(it.start.toFloat(), MINUTES - minMinutes)
            Item(it, top, maxOf(it.end.toFloat(), top + minMinutes))
        }
        val tiles = mutableListOf<PlacedSegment>()
        val marks = mutableListOf<MoreMark>()
        var widest = 1
        var from = 0
        while (from < items.size) {
            var end = items[from].bottom
            var to = from + 1
            while (to < items.size && items[to].top < end) {
                end = maxOf(end, items[to].bottom)
                to++
            }
            val group = items.subList(from, to)
            from = to
            // Each event in the first column that is free again when it starts
            val ends = mutableListOf<Float>()
            for (item in group) {
                val free = ends.indexOfFirst { it <= item.top }
                if (free < 0) {
                    ends += item.bottom
                    item.column = ends.lastIndex
                } else {
                    ends[free] = item.bottom
                    item.column = free
                }
            }
            val columns = minOf(ends.size, maxColumns)
            widest = maxOf(widest, columns)
            val shown = group.filterTo(mutableListOf()) { it.column < maxColumns }
            val hidden = group.filter { it.column >= maxColumns }
            var index = 0
            while (index < hidden.size) {
                // The hidden events that overlap each other share a mark
                var bottom = hidden[index].bottom
                var count = 1
                index++
                while (index < hidden.size && hidden[index].top < bottom) {
                    bottom = maxOf(bottom, hidden[index].bottom)
                    count++
                    index++
                }
                val top = bottom - moreMinutes
                val beside = shown.filter { it.column == maxColumns - 1 && it.top < bottom && it.bottom > top }
                for (tile in beside) {
                    if (top - tile.top >= moreMinutes) {
                        tile.bottom = top
                    } else {
                        shown -= tile
                        count++
                    }
                }
                marks += MoreMark(count, top, bottom, maxColumns - 1, maxColumns)
            }
            shown.mapTo(tiles) { PlacedSegment(it.segment, it.top, it.bottom, it.column, columns) }
        }
        return DayLayout(tiles, marks, widest)
    }

    private val ORDER = compareBy<DaySegment>({ it.start }, { -it.end }, { it.occurrence.title.lowercase() }, { it.occurrence.eventId })
}
