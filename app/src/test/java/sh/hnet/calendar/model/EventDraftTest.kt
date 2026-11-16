package sh.hnet.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDraftTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val nov18 = LocalDate.of(2026, 11, 18)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = nov18) = LocalDateTime.of(date, LocalTime.of(hour, minute))
    private val draft = EventDraft(calendarId = 1, start = at(13), end = at(14, 30), zone = zone.id)

    private fun details(begin: Long, end: Long, allDay: Boolean, timeZone: String?) = EventDetails(
        1, 1, "Title", "", "", begin, end, allDay, timeZone, null, false, true, false, EventStatus.CONFIRMED, "", emptyList(), emptyList(), false, Answer.NONE,
    )

    @Test
    fun aNewEventStartsAtTheNextFullHour() {
        val new = EventDraft.new(7, nov18, at(10, 20), zone)
        assertEquals(at(11), new.start)
        assertEquals(at(12), new.end)
        assertEquals(7, new.calendarId)
        // Late in the evening it reaches into the next day
        assertEquals(at(0, 0, nov18.plusDays(1)), EventDraft.new(7, nov18, at(23, 40), zone).start)
        // A slot that was pressed names its own time
        assertEquals(at(15), EventDraft.new(7, nov18, at(10, 20), zone, LocalTime.of(15, 0)).start)
    }

    @Test
    fun movingTheStartKeepsTheLength() {
        val later = draft.withStartTime(LocalTime.of(16, 0))
        assertEquals(at(16), later.start)
        assertEquals(at(17, 30), later.end)
        val tomorrow = draft.withStartDate(nov18.plusDays(1))
        assertEquals(at(14, 30, nov18.plusDays(1)), tomorrow.end)
    }

    @Test
    fun theEndIsNeverBeforeTheStart() {
        assertEquals(at(13), draft.withEndTime(LocalTime.of(9, 0)).end)
        assertEquals(nov18, draft.withEndDate(nov18.minusDays(3)).end.toLocalDate())
        assertEquals(at(14, 30, nov18.plusDays(2)), draft.withEndDate(nov18.plusDays(2)).end)
    }

    @Test
    fun timesGoToTheProviderInTheEventsZone() {
        assertEquals(at(13).atZone(zone).toInstant().toEpochMilli(), draft.begin)
        assertEquals(at(14, 30).atZone(zone).toInstant().toEpochMilli(), draft.finish)
        assertEquals("PT5400S", draft.duration)
        assertEquals("Europe/Warsaw", draft.storedZone)
        val london = draft.copy(zone = "Europe/London")
        assertEquals(draft.begin + 3_600_000, london.begin)
    }

    @Test
    fun anAllDayEventRunsFromMidnightToMidnightInUtc() {
        val days = draft.withAllDay(true).withEndDate(nov18.plusDays(2))
        assertEquals(nov18.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), days.begin)
        assertEquals(nov18.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), days.finish)
        assertEquals("P3D", days.duration)
        assertEquals("UTC", days.storedZone)
    }

    @Test
    fun turningAllDayOffBringsTheTimesBack() {
        val back = draft.withAllDay(true).withAllDay(false)
        // The times are back; the reminders are the usual ones of a timed event
        assertEquals(draft.copy(reminders = listOf(15)), back)
        // The hidden end time may lie before the start's: then the event gets an hour
        val odd = draft.copy(allDay = true, start = at(14), end = at(10)).withAllDay(false)
        assertFalse(odd.allDay)
        assertEquals(at(15), odd.end)
    }

    @Test
    fun anEventOpensInItsOwnZone() {
        val begin = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
        val london = EventDraft.of(details(begin, begin + 3_600_000, false, "Europe/London"), zone)
        assertEquals(at(12), london.start)
        assertEquals("Europe/London", london.zone)
        val own = EventDraft.of(details(begin, begin + 3_600_000, false, null), zone)
        assertEquals(at(13), own.start)
        assertEquals(zone.id, own.zone)
        // What was read is what would be written
        assertEquals(begin, london.begin)
        assertEquals(begin + 3_600_000, london.finish)
    }

    @Test
    fun anAllDayEventOpensByItsDates() {
        val begin = nov18.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = nov18.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val opened = EventDraft.of(details(begin, end, true, "UTC"), zone)
        assertTrue(opened.allDay)
        assertEquals(nov18, opened.start.toLocalDate())
        assertEquals(nov18.plusDays(1), opened.end.toLocalDate())
        assertEquals(begin, opened.begin)
        assertEquals(end, opened.finish)
        assertEquals(zone.id, opened.zone)
    }

    @Test
    fun theRepetitionFollowsTheStart() {
        val wednesday = LocalDateTime.of(2026, 11, 18, 13, 0)
        val draft = EventDraft(calendarId = 1, start = wednesday, end = wednesday.plusHours(1), zone = "Europe/Warsaw", repetition = Repetition.Simple(Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY))))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", draft.rrule)
        val moved = draft.withStartDate(LocalDate.of(2026, 11, 19))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", moved.rrule)
        // A change of the time alone leaves it
        assertEquals(draft.repetition, draft.withStartTime(LocalTime.of(9, 0)).repetition)
        assertEquals(null, draft.copy(repetition = Repetition.None).rrule)
        assertEquals("FREQ=HOURLY", draft.copy(repetition = Repetition.Other("FREQ=HOURLY")).withStartDate(LocalDate.of(2026, 11, 19)).rrule)
    }

    @Test
    fun aRuleThatLeavesTheStartOutMovesIt() {
        val wednesday = LocalDateTime.of(2026, 11, 18, 13, 0)
        val draft = EventDraft(calendarId = 1, start = wednesday, end = wednesday.plusHours(26), zone = "Europe/Warsaw")
        val fridays = draft.withRepetition(Repetition.Simple(Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))))
        // The first day the rule names, with the length kept
        assertEquals(LocalDateTime.of(2026, 11, 20, 13, 0), fridays.start)
        assertEquals(LocalDateTime.of(2026, 11, 21, 15, 0), fridays.end)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", fridays.rrule)
        // Any other rule takes the start as it is
        assertEquals(wednesday, draft.withRepetition(Repetition.Simple(Rule(Frequency.MONTHLY, nth = 3))).start)
        assertEquals(wednesday, draft.withRepetition(Repetition.None).start)
    }

    @Test
    fun remindersAreThoseOfTheEventsKind() {
        val start = LocalDateTime.of(2026, 11, 18, 13, 0)
        val draft = EventDraft.new(1, start.toLocalDate(), start, ZoneId.of("Europe/Warsaw"))
        assertEquals(listOf(15), draft.reminders)
        val allDay = draft.copy(reminders = listOf(5, 60)).withAllDay(true)
        assertEquals(emptyList<Int>(), allDay.reminders)
        assertEquals(listOf(15), allDay.withAllDay(false).reminders)
        // Nothing changes when the switch stays where it is
        assertEquals(listOf(5, 60), draft.copy(reminders = listOf(5, 60)).withAllDay(false).reminders)
    }
}
