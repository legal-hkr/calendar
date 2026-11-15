package sh.hnet.calendar.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val today = day(2026, 11, 18)

    private fun day(year: Int, month: Int, day: Int) = JulianDay.of(LocalDate.of(year, month, day))
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** A timed event as the provider reports it: one ending at midnight ends on the day before. */
    private fun timed(id: Long, title: String, start: LocalDateTime, end: LocalDateTime, answer: Answer = Answer.NONE): Occurrence {
        val endsAtMidnight = end.toLocalTime() == java.time.LocalTime.MIDNIGHT && end.toLocalDate() > start.toLocalDate()
        return Occurrence(
            id, 1, title, "", start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli(), false,
            JulianDay.of(start.toLocalDate()), JulianDay.of(end.toLocalDate()) - if (endsAtMidnight) 1 else 0, answer = answer,
        )
    }

    private fun allDay(id: Long, title: String, first: LocalDate, last: LocalDate = first) = Occurrence(
        id, 1, title, "", first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true,
        JulianDay.of(first), JulianDay.of(last),
    )

    private val nov18 = LocalDate.of(2026, 11, 18)

    @Test
    fun julianDaysMatchTheProviders() {
        // android.text.format.Time.EPOCH_JULIAN_DAY
        assertEquals(2440588, JulianDay.of(LocalDate.of(1970, 1, 1)))
        assertEquals(nov18, JulianDay.toDate(JulianDay.of(nov18)))
    }

    @Test
    fun todayIsListedEvenWhenEmptyAndOtherEmptyDaysAreNot() {
        val days = Agenda.build(listOf(timed(1, "Pool", nov18.plusDays(2).atTime(8, 0), nov18.plusDays(2).atTime(9, 0))), today - 30, today + 30, today)
        assertEquals(listOf(today, today + 2), days.map { it.day })
        assertTrue(days[0].rows.isEmpty())
        assertEquals(RowTime.START_END, days[1].rows.single().time)
    }

    @Test
    fun todayOutsideTheWindowIsNotAdded() {
        assertTrue(Agenda.build(emptyList(), today + 1, today + 30, today).isEmpty())
    }

    @Test
    fun allDayRowsComeFirstThenByStart() {
        val days = Agenda.build(
            listOf(
                timed(1, "Workshop", nov18.atTime(14, 0), nov18.atTime(16, 0)),
                timed(2, "Review", nov18.atTime(9, 0), nov18.atTime(9, 30)),
                allDay(3, "Expenses", nov18),
                timed(4, "Call", nov18.atTime(9, 0), nov18.atTime(9, 15)),
            ),
            today, today, today,
        )
        assertEquals(listOf("Expenses", "Call", "Review", "Workshop"), days.single().rows.map { it.occurrence.title })
        assertEquals(RowTime.ALL_DAY, days.single().rows.first().time)
        assertFalse(days.single().rows.first().multiDay)
    }

    @Test
    fun allDayEventOfSeveralDaysIsListedOnEachDay() {
        val days = Agenda.build(listOf(allDay(1, "Conference", nov18, nov18.plusDays(2))), today - 30, today + 30, today)
        assertEquals(listOf(today, today + 1, today + 2), days.map { it.day })
        assertEquals(listOf(1, 2, 3), days.map { it.rows.single().dayNumber })
        assertTrue(days.all { it.rows.single().dayCount == 3 && it.rows.single().time == RowTime.ALL_DAY })
        assertEquals(3, days.map { it.rows.single().key }.toSet().size)
    }

    @Test
    fun timedEventOverMidnightShowsItsStartThenItsEnd() {
        val days = Agenda.build(listOf(timed(1, "Night train", nov18.atTime(22, 0), nov18.plusDays(2).atTime(6, 30))), today - 30, today + 30, today)
        assertEquals(listOf(RowTime.START, RowTime.ALL_DAY, RowTime.END), days.map { it.rows.single().time })
    }

    @Test
    fun eventEndingAtMidnightStaysOnItsDay() {
        val days = Agenda.build(listOf(timed(1, "Party", nov18.atTime(20, 0), nov18.plusDays(1).atTime(0, 0))), today - 30, today + 30, today)
        assertEquals(listOf(today), days.map { it.day })
        assertEquals(RowTime.START_END, days.single().rows.single().time)
    }

    @Test
    fun eventReachingIntoTheWindowIsCutToIt() {
        val days = Agenda.build(listOf(allDay(1, "Trip", nov18.minusDays(3), nov18.plusDays(1))), today, today + 30, today)
        assertEquals(listOf(today, today + 1), days.map { it.day })
        assertEquals(listOf(4, 5), days.map { it.rows.single().dayNumber })
        assertEquals(5, days[0].rows.single().dayCount)
    }

    @Test
    fun eventThatEndedEarlierTodayComesBeforeTimedOnes() {
        val days = Agenda.build(
            listOf(
                timed(1, "Morning", nov18.atTime(8, 0), nov18.atTime(9, 0)),
                timed(2, "Night shift", nov18.minusDays(1).atTime(22, 0), nov18.atTime(6, 0)),
                allDay(3, "Holiday", nov18),
            ),
            today, today, today,
        )
        assertEquals(listOf("Holiday", "Night shift", "Morning"), days.single().rows.map { it.occurrence.title })
    }

    @Test
    fun declinedEventsAreHiddenUnlessAsked() {
        val declined = timed(1, "Meeting", nov18.atTime(10, 0), nov18.atTime(11, 0), Answer.DECLINED)
        assertTrue(Agenda.build(listOf(declined), today, today, today).single().rows.isEmpty())
        assertEquals(1, Agenda.build(listOf(declined), today, today, today, showDeclined = true).single().rows.size)
    }

    @Test
    fun pastIsByEndForTimedAndByLastDayForAllDay() {
        val meeting = timed(1, "Meeting", nov18.atTime(10, 0), nov18.atTime(11, 0))
        assertFalse(meeting.isPast(at(nov18, 10, 59), today))
        assertTrue(meeting.isPast(at(nov18, 11, 0), today))
        val trip = allDay(2, "Trip", nov18.minusDays(1), nov18)
        // Stored in UTC, an all-day event must not end an hour early or late with the zone
        assertFalse(trip.isPast(at(nov18, 23, 59), today))
        assertTrue(trip.isPast(at(nov18.plusDays(1), 0, 0), today + 1))
    }

    @Test
    fun tentativeIsTheEventsStatusOrTheUsersAnswer() {
        val base = timed(1, "Call", nov18.atTime(10, 0), nov18.atTime(11, 0))
        assertFalse(base.tentative)
        assertTrue(base.copy(status = EventStatus.TENTATIVE).tentative)
        assertTrue(base.copy(answer = Answer.TENTATIVE).tentative)
        assertTrue(base.copy(status = EventStatus.CANCELED).canceled)
    }
}
