package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPlanTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val nov18 = LocalDate.of(2026, 11, 18)
    private val today = JulianDay.of(nov18)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun timed(id: Long, start: LocalDateTime, end: LocalDateTime, calendar: Long = 1, status: EventStatus = EventStatus.CONFIRMED, answer: Answer = Answer.NONE): Occurrence {
        val endsAtMidnight = end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate() > start.toLocalDate()
        return Occurrence(
            id, calendar, "Event $id", "", start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli(), false,
            JulianDay.of(start.toLocalDate()), JulianDay.of(end.toLocalDate()) - if (endsAtMidnight) 1 else 0, status, answer,
        )
    }

    private fun allDay(id: Long, first: LocalDate, last: LocalDate = first, calendar: Long = 1) = Occurrence(
        id, calendar, "All day $id", "", first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true,
        JulianDay.of(first), JulianDay.of(last),
    )

    @Test
    fun upcomingKeepsARunningEventAndLeavesOutAllDayCanceledAndDeclined() {
        val now = millis(nov18, 10, 30)
        val events = listOf(
            timed(1, nov18.atTime(9, 0), nov18.atTime(10, 0)),
            timed(2, nov18.atTime(10, 0), nov18.atTime(11, 0)),
            allDay(3, nov18),
            timed(4, nov18.atTime(12, 0), nov18.atTime(13, 0), status = EventStatus.CANCELED),
            timed(5, nov18.atTime(13, 0), nov18.atTime(14, 0), answer = Answer.DECLINED),
            timed(6, nov18.plusDays(1).atTime(8, 0), nov18.plusDays(1).atTime(9, 0)),
            timed(7, nov18.plusDays(2).atTime(8, 0), nov18.plusDays(2).atTime(9, 0)),
        )
        assertEquals(listOf(2L, 6L), WidgetPlan.upcoming(events, now, today, 2).map { it.eventId })
    }

    @Test
    fun upcomingLooksNoFurtherThanAMonth() {
        val far = nov18.plusDays(WidgetPlan.AHEAD_DAYS.toLong())
        assertTrue(WidgetPlan.upcoming(listOf(timed(1, far.atTime(8, 0), far.atTime(9, 0))), millis(nov18, 8), today, 2).isEmpty())
    }

    @Test
    fun agendaDropsWhatIsOverAndKeepsAnEmptyToday() {
        val now = millis(nov18, 12)
        val days = WidgetPlan.agendaDays(listOf(timed(1, nov18.atTime(9, 0), nov18.atTime(10, 0)), timed(2, nov18.plusDays(3).atTime(9, 0), nov18.plusDays(3).atTime(10, 0))), now, today)
        assertEquals(listOf(today, today + 3), days.map { it.day })
        assertTrue(days[0].rows.isEmpty())
    }

    @Test
    fun agendaEndsWithinTwoWeeks() {
        val late = nov18.plusDays(WidgetPlan.AGENDA_DAYS.toLong())
        val days = WidgetPlan.agendaDays(listOf(timed(1, late.atTime(9, 0), late.atTime(10, 0))), millis(nov18, 8), today)
        assertEquals(listOf(today), days.map { it.day })
    }

    private fun rows(day: Int, count: Int) = AgendaDay(day, List(count) { AgendaRow(timed(day * 10L + it, nov18.atTime(9, 0), nov18.atTime(10, 0)), day, RowTime.START_END, 1, 1) })

    @Test
    fun fitTakesWholePiecesOnly() {
        val days = listOf(rows(today, 4), rows(today + 1, 2))
        // The first row (30) and strips of 6 + 56: three strips need 216 dp, a fourth 278
        assertEquals(4, WidgetPlan.fit(days, 277).size)
        assertEquals(5, WidgetPlan.fit(days, 278).size)
    }

    @Test
    fun fitNeverLeavesADayRowWithoutAStrip() {
        val days = listOf(rows(today, 1), rows(today + 1, 2))
        // 30 + 62 = 92; the next day's row and a strip need 36 + 62 more
        val pieces = WidgetPlan.fit(days, 150)
        assertEquals(2, pieces.size)
        assertTrue(pieces.last() is AgendaPiece.Strip)
        assertEquals(4, WidgetPlan.fit(days, 190).size)
    }

    @Test
    fun fitShowsTheFirstDayAndAStripHoweverSmall() {
        val pieces = WidgetPlan.fit(listOf(rows(today, 3)), 40)
        assertEquals(listOf(AgendaPiece.Header(today, true), AgendaPiece.Strip(rows(today, 3).rows[0])), pieces)
    }

    @Test
    fun fitSaysNothingPlannedForAnEmptyToday() {
        val pieces = WidgetPlan.fit(listOf(AgendaDay(today, emptyList()), rows(today + 2, 1)), 400)
        assertEquals(listOf(AgendaPiece.Header(today, true), AgendaPiece.Nothing(today), AgendaPiece.Header(today + 2, false)), pieces.take(3))
        assertTrue(pieces[3] is AgendaPiece.Strip)
    }

    @Test
    fun monthHasSixWeeksAndUpToThreeDotsAllDayFirst() {
        val cells = WidgetPlan.month(
            listOf(
                timed(1, nov18.atTime(9, 0), nov18.atTime(10, 0), calendar = 1),
                timed(2, nov18.atTime(8, 0), nov18.atTime(9, 0), calendar = 2),
                allDay(3, nov18.minusDays(1), nov18.plusDays(1), calendar = 3),
                timed(4, nov18.atTime(11, 0), nov18.atTime(12, 0), calendar = 4),
                timed(5, nov18.atTime(7, 0), nov18.atTime(8, 0), calendar = 5, answer = Answer.DECLINED),
            ),
            Month.number(nov18), DayOfWeek.MONDAY,
        )
        assertEquals(42, cells.size)
        // November 2026 begins on a Sunday: the grid starts on Monday, October 26
        assertEquals(JulianDay.of(LocalDate.of(2026, 10, 26)), cells.first().day)
        assertEquals(false, cells.first().inMonth)
        assertEquals(listOf(3L, 2L, 1L), cells.first { it.day == today }.dots)
        assertEquals(listOf(3L), cells.first { it.day == today + 1 }.dots)
    }

    @Test
    fun bareLinesCountWholeLinesUnderTheDate() {
        // The date 22 dp, then 24 a line (6 above, 18 high)
        assertEquals(4, WidgetPlan.bareLines(22 + 4 * 24))
        assertEquals(3, WidgetPlan.bareLines(22 + 4 * 24 - 1))
        assertEquals(1, WidgetPlan.bareLines(30))
    }

    @Test
    fun dayWords() {
        assertEquals(DayWord.TODAY, WidgetPlan.dayWord(today, today))
        assertEquals(DayWord.TOMORROW, WidgetPlan.dayWord(today + 1, today))
        assertEquals(DayWord.WEEKDAY, WidgetPlan.dayWord(today + 6, today))
        assertEquals(DayWord.DATE, WidgetPlan.dayWord(today + 7, today))
        assertEquals(DayWord.DATE, WidgetPlan.dayWord(today - 1, today))
    }

    @Test
    fun nextChangeIsTheNearestEndOrMidnight() {
        val now = millis(nov18, 10, 30)
        val midnight = millis(nov18.plusDays(1), 0)
        val events = listOf(timed(1, nov18.atTime(10, 0), nov18.atTime(11, 0)), timed(2, nov18.atTime(9, 0), nov18.atTime(10, 0)), allDay(3, nov18))
        assertEquals(millis(nov18, 11), WidgetPlan.nextChange(events, now, midnight))
        assertEquals(midnight, WidgetPlan.nextChange(listOf(allDay(3, nov18)), now, midnight))
    }
}
