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

class DaysTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val nov18 = LocalDate.of(2026, 11, 18)
    private val day = JulianDay.of(nov18)

    /** A timed event as the provider reports it: one ending at midnight ends on the day before. */
    private fun timed(id: Long, start: LocalDateTime, end: LocalDateTime, answer: Answer = Answer.NONE): Occurrence {
        val endsAtMidnight = end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate() > start.toLocalDate()
        return Occurrence(
            id, 1, "Event $id", "", start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli(), false,
            JulianDay.of(start.toLocalDate()), JulianDay.of(end.toLocalDate()) - if (endsAtMidnight) 1 else 0, answer = answer,
        )
    }

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = nov18) = LocalDateTime.of(date, LocalTime.of(hour, minute))
    private fun today(id: Long, fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int) = timed(id, at(fromHour, fromMinute), at(toHour, toMinute))

    private fun allDay(id: Long, first: LocalDate, last: LocalDate = first) = Occurrence(
        id, 1, "Event $id", "", first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true,
        JulianDay.of(first), JulianDay.of(last),
    )

    private fun layout(events: List<Occurrence>, maxColumns: Int = Int.MAX_VALUE) = Days.layout(Days.segments(events, day, zone), 20f, maxColumns, 17f)

    @Test
    fun theWeekStartsOnItsFirstDay() {
        // November 18, 2026 is a Wednesday
        assertEquals(LocalDate.of(2026, 11, 16), JulianDay.toDate(Days.weekFirst(day, DayOfWeek.MONDAY)))
        assertEquals(LocalDate.of(2026, 11, 15), JulianDay.toDate(Days.weekFirst(day, DayOfWeek.SUNDAY)))
        assertEquals(nov18, JulianDay.toDate(Days.weekFirst(day, DayOfWeek.WEDNESDAY)))
    }

    @Test
    fun anEventIsCutAtMidnight() {
        val night = timed(1, at(22), at(6, 30, nov18.plusDays(1)))
        assertEquals(listOf(DaySegment(night, 22 * 60, Days.MINUTES, true)), Days.segments(listOf(night), day, zone))
        assertEquals(listOf(DaySegment(night, 0, 6 * 60 + 30, false)), Days.segments(listOf(night), day + 1, zone))
    }

    @Test
    fun anEventEndingAtMidnightIsNotOnTheNextDay() {
        val evening = timed(1, at(20), at(0, 0, nov18.plusDays(1)))
        assertEquals(Days.MINUTES, Days.segments(listOf(evening), day, zone).single().end)
        assertTrue(Days.segments(listOf(evening), day + 1, zone).isEmpty())
    }

    @Test
    fun longAndAllDayEventsGoToTheStrip() {
        val long = timed(1, at(10), at(10, 0, nov18.plusDays(1)))
        val whole = allDay(2, nov18)
        val short = today(3, 9, 0, 10, 0)
        val events = listOf(long, whole, short)
        assertEquals(listOf(3L), Days.segments(events, day, zone).map { it.occurrence.eventId })
        assertEquals(setOf(1L, 2L), Days.dayStrip(events, day).map { it.eventId }.toSet())
        assertEquals(listOf(1L), Days.dayStrip(events, day + 1).map { it.eventId })
    }

    @Test
    fun declinedEventsAreLeftOut() {
        val events = listOf(timed(1, at(9), at(10), Answer.DECLINED))
        assertTrue(Days.segments(events, day, zone).isEmpty())
        assertEquals(1, Days.segments(events, day, zone, showDeclined = true).size)
    }

    @Test
    fun theWeeksStripKeepsALinePerEvent() {
        val monday = LocalDate.of(2026, 11, 16)
        val week = Days.weekStrip(listOf(allDay(1, nov18), allDay(2, nov18.plusDays(2), nov18.plusDays(6)), allDay(3, nov18.plusDays(2)), today(4, 9, 0, 10, 0)), JulianDay.of(monday))
        assertEquals(mapOf(1L to 0, 2L to 0, 3L to 1), week.bars.associate { it.occurrence.eventId to it.lane })
        val trip = week.bars.single { it.occurrence.eventId == 2L }
        assertEquals(4, trip.firstColumn)
        assertEquals(6, trip.lastColumn)
        assertTrue(trip.endsAfter)
        // Two lines: everything fits
        assertEquals(3, Month.fill(week, 2).bars.size)
    }

    @Test
    fun aFullStripCountsTheRest() {
        val monday = JulianDay.of(LocalDate.of(2026, 11, 16))
        val week = Days.weekStrip(listOf(allDay(1, nov18), allDay(2, nov18), allDay(3, nov18)), monday)
        val fill = Month.fill(week, 2)
        assertEquals(listOf(1L), fill.bars.map { it.occurrence.eventId })
        assertEquals(2, fill.cells[2].more)
        assertEquals(0, fill.cells[1].more)
    }

    @Test
    fun eventsApartTakeTheWholeWidth() {
        val result = layout(listOf(today(1, 9, 0, 10, 0), today(2, 10, 0, 11, 0)))
        assertEquals(listOf(1, 1), result.tiles.map { it.columns })
        assertEquals(1, result.columns)
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun overlappingEventsStandSideBySide() {
        val result = layout(listOf(today(1, 10, 0, 11, 0), today(2, 10, 30, 11, 30), today(3, 11, 0, 12, 0)))
        val columns = result.tiles.associate { it.segment.occurrence.eventId to it.column }
        // The third starts when the first column is free again
        assertEquals(mapOf(1L to 0, 2L to 1, 3L to 0), columns)
        assertTrue(result.tiles.all { it.columns == 2 })
    }

    @Test
    fun aShortEventHasTheSmallestHeight() {
        val result = layout(listOf(today(1, 12, 15, 12, 30), today(2, 12, 30, 13, 0)))
        val short = result.tiles.single { it.segment.occurrence.eventId == 1L }
        assertEquals(12 * 60 + 15f, short.top)
        assertEquals(12 * 60 + 35f, short.bottom)
        // As drawn it reaches into the next event, so they stand side by side
        assertEquals(2, short.columns)
    }

    @Test
    fun anEventWithoutLengthLateAtNightStaysInTheDay() {
        val tile = layout(listOf(today(1, 23, 55, 23, 55))).tiles.single()
        assertEquals(Days.MINUTES - 20f, tile.top)
        assertEquals(Days.MINUTES.toFloat(), tile.bottom)
    }

    @Test
    fun threeEventsAtOneTimeInWeekAreTwoColumnsAndOneMore() {
        val result = layout(listOf(today(1, 10, 0, 11, 0), today(2, 10, 0, 11, 0), today(3, 10, 0, 11, 0)), maxColumns = 2)
        assertEquals(listOf(1L, 2L), result.tiles.map { it.segment.occurrence.eventId })
        assertTrue(result.tiles.all { it.columns == 2 })
        assertEquals(listOf(MoreMark(1, 660f - 17f, 660f, 1, 2)), result.more)
        // The second tile ends where the mark starts; the first keeps its length
        assertEquals(660f, result.tiles[0].bottom)
        assertEquals(643f, result.tiles[1].bottom)
        assertEquals(2, result.columns)
    }

    @Test
    fun aTileTooShortBesideTheMarkJoinsTheCount() {
        // The second column's tile is as short as tiles get: no room for it above the mark
        val result = layout(listOf(today(1, 10, 0, 11, 0), today(2, 10, 0, 10, 15), today(3, 10, 0, 10, 20)), maxColumns = 2)
        assertEquals(listOf(1L), result.tiles.map { it.segment.occurrence.eventId })
        assertEquals(2, result.more.single().count)
    }

    @Test
    fun fiveEventsAtOneTimeInDayAreFiveColumns() {
        val result = layout((1L..5L).map { today(it, 10, 0, 11, 0) })
        assertEquals(5, result.columns)
        assertEquals(listOf(0, 1, 2, 3, 4), result.tiles.map { it.column })
        assertFalse(result.more.isNotEmpty())
    }
}
