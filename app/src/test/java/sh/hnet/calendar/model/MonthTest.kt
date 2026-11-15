package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val november = Month.number(LocalDate.of(2026, 11, 1))
    private val start = Month.gridStart(november, DayOfWeek.MONDAY)

    private fun date(day: Int) = LocalDate.of(2026, 11, day)
    private fun julian(day: Int) = JulianDay.of(date(day))

    private fun timed(id: Long, day: Int, hour: Int, calendar: Long = 1, answer: Answer = Answer.NONE): Occurrence {
        val begin = LocalDateTime.of(date(day), LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
        return Occurrence(id, calendar, "Event $id", "", begin, begin + 3_600_000, false, julian(day), julian(day), answer = answer)
    }

    private fun allDay(id: Long, first: Int, last: Int = first, calendar: Long = 1) = Occurrence(
        id, calendar, "Event $id", "", date(first).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        date(last).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, julian(first), julian(last),
    )

    @Test
    fun monthsAreNumberedThroughTheYears() {
        assertEquals(LocalDate.of(2026, 11, 1), Month.firstDate(november))
        assertEquals(LocalDate.of(2027, 1, 1), Month.firstDate(november + 2))
        assertEquals(november + 2, Month.number(LocalDate.of(2027, 1, 31)))
    }

    @Test
    fun thePageStartsWithTheWeekOfTheFirst() {
        // November 2026 starts on a Sunday
        assertEquals(LocalDate.of(2026, 10, 26), JulianDay.toDate(start))
        assertEquals(date(1), JulianDay.toDate(Month.gridStart(november, DayOfWeek.SUNDAY)))
        assertEquals(LocalDate.of(2026, 10, 31), JulianDay.toDate(Month.gridStart(november, DayOfWeek.SATURDAY)))
    }

    @Test
    fun aDaysEventsAreAllDayFirstThenByStart() {
        val grid = Month.build(listOf(timed(1, 18, 15), timed(2, 18, 9), allDay(3, 18)), start)
        assertEquals(6, grid.weeks.size)
        assertEquals(listOf(3L, 2L, 1L), grid.weeks[3].days[2].map { it.eventId })
        assertTrue(grid.weeks[3].bars.isEmpty())
    }

    @Test
    fun declinedEventsAreLeftOut() {
        val events = listOf(timed(1, 18, 9, answer = Answer.DECLINED))
        assertTrue(Month.build(events, start).weeks[3].days[2].isEmpty())
        assertEquals(1, Month.build(events, start, showDeclined = true).weeks[3].days[2].size)
    }

    @Test
    fun anEventOfSeveralDaysIsABarThatGoesOnInTheNextWeek() {
        // Friday the 20th to Tuesday the 24th
        val grid = Month.build(listOf(allDay(1, 20, 24)), start)
        val first = grid.weeks[3].bars.single()
        assertEquals(4, first.firstColumn)
        assertEquals(6, first.lastColumn)
        assertFalse(first.startsBefore)
        assertTrue(first.endsAfter)
        val second = grid.weeks[4].bars.single()
        assertEquals(0, second.firstColumn)
        assertEquals(1, second.lastColumn)
        assertTrue(second.startsBefore)
        assertFalse(second.endsAfter)
        assertTrue(grid.weeks[3].days.all { it.isEmpty() })
    }

    @Test
    fun barsThatMeetTakeLinesOfTheirOwn() {
        val grid = Month.build(listOf(allDay(1, 17, 19), allDay(2, 18, 20), allDay(3, 20, 21)), start)
        val lanes = grid.weeks[3].bars.associate { it.occurrence.eventId to it.lane }
        assertEquals(mapOf(1L to 0, 2L to 1, 3L to 0), lanes)
    }

    @Test
    fun eightEventsShowThreeTilesAndFiveMore() {
        val grid = Month.build((1L..8L).map { timed(it, 18, 8 + it.toInt()) }, start)
        val cell = Month.fill(grid.weeks[3], 4).cells[2]
        assertEquals(CellFill(0, 3, 5), cell)
    }

    @Test
    fun fourEventsAllShow() {
        val grid = Month.build((1L..4L).map { timed(it, 18, 8 + it.toInt()) }, start)
        assertEquals(CellFill(0, 4, 0), Month.fill(grid.weeks[3], 4).cells[2])
    }

    @Test
    fun tilesStartUnderTheBars() {
        val grid = Month.build(listOf(allDay(1, 20, 22), timed(2, 20, 9), timed(3, 19, 18)), start)
        val fill = Month.fill(grid.weeks[3], 4)
        assertEquals(CellFill(1, 1, 0), fill.cells[4])
        assertEquals(CellFill(0, 1, 0), fill.cells[3])
        assertEquals(1, fill.bars.size)
    }

    @Test
    fun aBarOnTheLastLineGivesWayToTheCount() {
        // Four bars over Wednesday; Thursday, under the last of them, has an event of its own too
        val bars = listOf(allDay(1, 16, 18), allDay(2, 17, 18), allDay(3, 18, 18 + 1), allDay(4, 18, 19))
        val grid = Month.build(bars + timed(5, 19, 9), start)
        val week = grid.weeks[3]
        assertEquals(3, week.bars.single { it.occurrence.eventId == 4L }.lane)
        val fill = Month.fill(week, 4)
        assertEquals(listOf(1L, 2L, 3L), fill.bars.map { it.occurrence.eventId })
        // Wednesday: the hidden bar alone; Thursday: the hidden bar and its own event
        assertEquals(CellFill(4, 0, 1), fill.cells[2])
        assertEquals(CellFill(4, 0, 2), fill.cells[3])
        // Without Thursday's event everything fits
        assertEquals(4, Month.fill(Month.build(bars, start).weeks[3], 4).bars.size)
    }

    @Test
    fun theTimeComesBeforeWrapping() {
        // One timed event with room: the time and three lines of title
        assertEquals(listOf(TileLines(true, 3)), Month.lines(4, listOf(true), listOf(3)))
        // Two timed events and two spare lines: both get their time, no title wraps
        assertEquals(listOf(TileLines(true, 1), TileLines(true, 1)), Month.lines(2, listOf(true, true), listOf(3, 3)))
        // An all-day event never has a time line; the spare lines wrap the titles in turn
        assertEquals(listOf(TileLines(false, 2), TileLines(true, 2)), Month.lines(3, listOf(false, true), listOf(3, 2)))
        // A title that fits takes no more lines
        assertEquals(listOf(TileLines(true, 1), TileLines(true, 3)), Month.lines(5, listOf(true, true), listOf(1, 3)))
        assertEquals(listOf(TileLines(false, 1)), Month.lines(0, listOf(true), listOf(3)))
    }

    @Test
    fun aDayIsMarkedWithADotPerCalendar() {
        val noon = LocalDateTime.of(date(18), LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val grid = Month.build(listOf(timed(1, 18, 9, calendar = 1), timed(2, 18, 15, calendar = 1), timed(3, 18, 10, calendar = 2), allDay(4, 17, 19, calendar = 3)), start)
        val marks = Month.marks(grid.weeks[3], 2, noon, julian(18))
        // Calendar 1 still has an event to come; calendar 2's is over; the bar is no dot
        assertEquals(listOf(DayDot(1, false), DayDot(2, true)), marks.dots)
        assertFalse(marks.more)
    }

    @Test
    fun aBusyDayShowsThreeDotsAndMore() {
        val grid = Month.build((1L..5L).map { timed(it, 18, 8 + it.toInt(), calendar = it) }, start)
        val marks = Month.marks(grid.weeks[3], 2, 0, julian(18))
        assertEquals(3, marks.dots.size)
        assertTrue(marks.more)
    }

    @Test
    fun aBarUnderTheFirstLineIsADot() {
        val grid = Month.build(listOf(allDay(1, 16, 20, calendar = 1), allDay(2, 18, 19, calendar = 2)), start)
        assertEquals(listOf(DayDot(2, false)), Month.marks(grid.weeks[3], 2, 0, julian(18)).dots)
    }
}
