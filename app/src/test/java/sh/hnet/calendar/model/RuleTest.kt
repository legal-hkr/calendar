package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    // The third Wednesday of November 2026
    private val wednesday = LocalDate.of(2026, 11, 18)
    private val start = LocalDateTime.of(2026, 11, 18, 13, 0)

    @Test
    fun theSpecsRuleIsWrittenAndReadBack() {
        // "Every 2 weeks on Monday and Wednesday, until March 31, 2027" (SPEC §22, stage 6)
        val rule = Rule(Frequency.WEEKLY, 2, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), end = RuleEnd.Until(LocalDate.of(2027, 3, 31)))
        val stored = rule.toRRule(start, warsaw, allDay = false)
        // The end of March 31 in Warsaw, then in summer time
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270331T215959Z", stored)
        assertEquals(rule, Rule.parse(stored, wednesday, warsaw))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270331", rule.toRRule(start, warsaw, allDay = true))
        assertEquals(rule, Rule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270331", wednesday, warsaw))
    }

    @Test
    fun presetsAreWrittenFromTheStart() {
        val (daily, weekly, monthly, yearly) = Rule.presets(wednesday)
        assertEquals("FREQ=DAILY", daily.toRRule(start, warsaw, false))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", weekly.toRRule(start, warsaw, false))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=18", monthly.toRRule(start, warsaw, false))
        assertEquals("FREQ=YEARLY", yearly.toRRule(start, warsaw, false))
        assertEquals("FREQ=MONTHLY;BYDAY=3WE;COUNT=10", Rule(Frequency.MONTHLY, nth = 3, end = RuleEnd.Count(10)).toRRule(start, warsaw, false))
    }

    @Test
    fun rulesAreRead() {
        assertEquals(Rule(Frequency.DAILY, end = RuleEnd.Count(10)), Rule.parse("FREQ=DAILY;COUNT=10", wednesday, warsaw))
        // A weekly rule without its day repeats on the start's
        assertEquals(Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY)), Rule.parse("RRULE:FREQ=WEEKLY", wednesday, warsaw))
        assertEquals(Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), Rule.parse("FREQ=WEEKLY;WKST=SU;BYDAY=MO,WE,FR", wednesday, warsaw))
        assertEquals(Rule(Frequency.MONTHLY), Rule.parse("FREQ=MONTHLY", wednesday, warsaw))
        assertEquals(Rule(Frequency.MONTHLY, 3), Rule.parse("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=18", wednesday, warsaw))
        assertEquals(Rule(Frequency.MONTHLY, nth = 3), Rule.parse("FREQ=MONTHLY;BYDAY=3WE", wednesday, warsaw))
        assertEquals(Rule(Frequency.MONTHLY, nth = 3), Rule.parse("FREQ=MONTHLY;BYDAY=WE;BYSETPOS=3", wednesday, warsaw))
        assertEquals(Rule(Frequency.MONTHLY, nth = -1), Rule.parse("FREQ=MONTHLY;BYDAY=-1WE", LocalDate.of(2026, 11, 25), warsaw))
        assertEquals(Rule(Frequency.YEARLY), Rule.parse("FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=18", wednesday, warsaw))
        // An end at midnight UTC is still the day before in New York
        assertEquals(RuleEnd.Until(LocalDate.of(2027, 3, 30)), Rule.parse("FREQ=DAILY;UNTIL=20270331T000000Z", wednesday, ZoneId.of("America/New_York"))?.end)
    }

    @Test
    fun aRuleThatSaysMoreIsNotRead() {
        for (rule in listOf(
            "FREQ=HOURLY", "FREQ=WEEKLY;BYDAY=MO;BYHOUR=9", "FREQ=MONTHLY;BYMONTHDAY=1,15", "FREQ=MONTHLY;BYMONTHDAY=20", "FREQ=MONTHLY;BYDAY=2WE", "FREQ=MONTHLY;BYDAY=3TH",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "FREQ=YEARLY;BYMONTH=12", "FREQ=YEARLY;BYDAY=1SU;BYMONTH=4", "FREQ=DAILY;COUNT=5;UNTIL=20270331", "FREQ=DAILY;INTERVAL=0",
            "FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=SA,SU", "FREQ=WEEKLY;BYDAY=1MO", "nonsense",
        )) assertNull(rule, Rule.parse(rule, wednesday, warsaw))
    }

    @Test
    fun aRuleFollowsItsStart() {
        val thursday = wednesday.plusDays(1)
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), Rule(Frequency.WEEKLY, weekdays = days).movedTo(wednesday, thursday).weekdays)
        // A weekday the rule doesn't name leaves it alone
        assertEquals(days, Rule(Frequency.WEEKLY, weekdays = days).movedTo(thursday, thursday.plusDays(1)).weekdays)
        // The third Wednesday put on the fourth Thursday, and "the last" kept on a last weekday
        assertEquals(4, Rule(Frequency.MONTHLY, nth = 3).movedTo(wednesday, LocalDate.of(2026, 11, 26)).nth)
        assertEquals(-1, Rule(Frequency.MONTHLY, nth = -1).movedTo(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26)).nth)
        assertEquals(1, Rule(Frequency.MONTHLY, nth = -1).movedTo(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 2)).nth)
        assertEquals(Rule(Frequency.DAILY), Rule(Frequency.DAILY).movedTo(wednesday, thursday))
        assertEquals(Repetition.Other("FREQ=HOURLY"), Repetition.Other("FREQ=HOURLY").movedTo(wednesday, thursday))
    }

    @Test
    fun aSeriesTakingOnARuleStartsOnItsFirstFittingDay() {
        // A daily series from Monday the 2nd becomes weekly on Wednesday, opened on the 18th: it starts on the first Wednesday
        assertEquals(LocalDate.of(2026, 11, 4), Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY)).firstFrom(LocalDate.of(2026, 11, 2), wednesday))
        // Every other week: counted back from the 18th, so the 4th – not the 11th
        assertEquals(LocalDate.of(2026, 11, 4), Rule(Frequency.WEEKLY, 2, setOf(DayOfWeek.WEDNESDAY)).firstFrom(LocalDate.of(2026, 11, 2), wednesday))
        assertEquals(wednesday, Rule(Frequency.WEEKLY, 2, setOf(DayOfWeek.WEDNESDAY)).firstFrom(LocalDate.of(2026, 11, 5), wednesday))
        // Monday and Wednesday from a Tuesday: the Wednesday of that week
        assertEquals(LocalDate.of(2026, 11, 4), Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)).firstFrom(LocalDate.of(2026, 11, 3), wednesday))
        assertEquals(LocalDate.of(2026, 11, 3), Rule(Frequency.DAILY, 3).firstFrom(LocalDate.of(2026, 11, 1), wednesday))
        // Monthly on the 18th from September 20: October 18; on the third Wednesday: October 21
        assertEquals(LocalDate.of(2026, 10, 18), Rule(Frequency.MONTHLY).firstFrom(LocalDate.of(2026, 9, 20), wednesday))
        assertEquals(LocalDate.of(2026, 10, 21), Rule(Frequency.MONTHLY, nth = 3).firstFrom(LocalDate.of(2026, 9, 20), wednesday))
        assertEquals(LocalDate.of(2025, 11, 18), Rule(Frequency.YEARLY).firstFrom(LocalDate.of(2024, 12, 1), wednesday))
        // Every 2 years: 2024 is in step with 2026, 2025 is not
        assertEquals(LocalDate.of(2024, 11, 18), Rule(Frequency.YEARLY, 2).firstFrom(LocalDate.of(2023, 12, 1), wednesday))
        // Nothing earlier to take on
        assertEquals(wednesday, Rule(Frequency.DAILY).firstFrom(wednesday.plusDays(3), wednesday))
    }

    @Test
    fun aRuleNamesItsDays() {
        assertEquals(true, Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY)).names(wednesday.plusWeeks(1), wednesday))
        assertEquals(false, Rule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY)).names(wednesday.plusDays(1), wednesday))
        assertEquals(true, Rule(Frequency.MONTHLY, nth = 3).names(LocalDate.of(2026, 12, 16), wednesday))
        assertEquals(false, Rule(Frequency.MONTHLY).names(LocalDate.of(2026, 12, 16), wednesday))
        assertEquals(true, Rule(Frequency.YEARLY).names(LocalDate.of(2027, 11, 18), wednesday))
    }

    @Test
    fun remindersAreKeptInOrderAndLimited() {
        assertEquals(listOf(5, 15, 60), ReminderTimes.add(listOf(15, 60), 5))
        assertEquals(listOf(15, 60), ReminderTimes.add(listOf(15, 60), 15))
        assertEquals(5, ReminderTimes.add(listOf(1, 2, 3, 4, 5), 0).size)
        // On the day at 9:00, the day before at 18:00, 2 days before at 9:00
        assertEquals(0 to 540, ReminderTimes.allDayParts(-540))
        assertEquals(1 to 1080, ReminderTimes.allDayParts(360))
        assertEquals(2 to 540, ReminderTimes.allDayParts(2340))
        assertEquals(1 to 0, ReminderTimes.allDayParts(1440))
        assertEquals(2340, ReminderTimes.allDay(2))
    }

    @Test
    fun aFifthWeekdayIsTheLast() {
        // The third Tuesday put on December 29, 2026, the fifth Tuesday: no month is asked for a fifth one
        val moved = Rule(Frequency.MONTHLY, nth = 3).movedTo(LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 29))
        assertEquals(-1, moved.nth)
        assertEquals("FREQ=MONTHLY;BYDAY=-1TU", moved.toRRule(LocalDateTime.of(2026, 12, 29, 9, 0), warsaw, false))
        assertEquals(moved, Rule.parse("FREQ=MONTHLY;BYDAY=-1TU", LocalDate.of(2026, 12, 29), warsaw))
    }
}
