package sh.hnet.calendar.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {
    private val split = Instant.parse("2026-12-02T13:00:00Z").toEpochMilli()

    @Test
    fun aSeriesEndsJustBeforeTheOccurrence() {
        assertEquals("FREQ=WEEKLY;BYDAY=WE;UNTIL=20261202T125959Z", Recurrence.endingBefore("FREQ=WEEKLY;BYDAY=WE", split))
        // An earlier end and a count give way to the new end
        assertEquals("FREQ=DAILY;UNTIL=20261202T125959Z", Recurrence.endingBefore("FREQ=DAILY;UNTIL=20270331T000000Z", split))
        assertEquals("FREQ=DAILY;INTERVAL=2;UNTIL=20261202T125959Z", Recurrence.endingBefore("FREQ=DAILY;COUNT=10;INTERVAL=2", split))
    }

    @Test
    fun theSeriesThatGoesOnCountsWhatIsLeft() {
        assertEquals("FREQ=DAILY;COUNT=7", Recurrence.continuing("FREQ=DAILY;COUNT=10", 3))
        assertEquals("FREQ=DAILY;COUNT=1", Recurrence.continuing("FREQ=DAILY;COUNT=10", 12))
        assertEquals("FREQ=WEEKLY;UNTIL=20270331T000000Z", Recurrence.continuing("FREQ=WEEKLY;UNTIL=20270331T000000Z", 3))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", Recurrence.continuing("RRULE:FREQ=WEEKLY;BYDAY=WE", 3))
    }

    @Test
    fun aRuleOfOneDayFollowsTheMove() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val thursday = wednesday.plusDays(1)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", Recurrence.moved("FREQ=WEEKLY;BYDAY=WE", wednesday, thursday))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=19", Recurrence.moved("FREQ=MONTHLY;BYMONTHDAY=18", wednesday, thursday))
        assertEquals("FREQ=YEARLY;BYMONTHDAY=1;BYMONTH=12", Recurrence.moved("FREQ=YEARLY;BYMONTHDAY=18;BYMONTH=11", wednesday, LocalDate.of(2026, 12, 1)))
        assertEquals("FREQ=DAILY", Recurrence.moved("FREQ=DAILY", wednesday, thursday))
    }

    @Test
    fun aRicherRuleStands() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val thursday = wednesday.plusDays(1)
        assertEquals("FREQ=MONTHLY;BYDAY=3WE", Recurrence.moved("FREQ=MONTHLY;BYDAY=3WE", wednesday, thursday))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", Recurrence.moved("FREQ=WEEKLY;BYDAY=WE", wednesday, wednesday))
    }

    @Test
    fun ofSeveralWeekdaysOnlyTheMovedOneChanges() {
        val wednesday = LocalDate.of(2026, 11, 18)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH,FR", Recurrence.moved("FREQ=WEEKLY;BYDAY=MO,WE,FR", wednesday, wednesday.plusDays(1)))
        // Onto a day the rule already names: the two become one
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", Recurrence.moved("FREQ=WEEKLY;BYDAY=MO,WE,FR", wednesday, wednesday.plusDays(2)))
        // Earlier in the week: the days stay in order
        assertEquals("FREQ=WEEKLY;BYDAY=TU,FR", Recurrence.moved("FREQ=WEEKLY;BYDAY=WE,FR", wednesday, wednesday.minusDays(1)))
        // A day the rule doesn't name, and a move by whole weeks, change nothing
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", Recurrence.moved("FREQ=WEEKLY;BYDAY=MO,FR", wednesday, wednesday.plusDays(1)))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", Recurrence.moved("FREQ=WEEKLY;BYDAY=MO,WE,FR", wednesday, wednesday.plusDays(7)))
        assertTrue(Recurrence.severalWeekdays("FREQ=WEEKLY;BYDAY=MO,WE,FR"))
        assertFalse(Recurrence.severalWeekdays("FREQ=WEEKLY;BYDAY=WE"))
        assertFalse(Recurrence.severalWeekdays("FREQ=WEEKLY"))
    }

    @Test
    fun excludedOccurrencesAreReadAndWritten() {
        val first = Instant.parse("2026-11-20T07:00:00Z").toEpochMilli()
        val second = Instant.parse("2026-11-22T07:00:00Z").toEpochMilli()
        assertEquals(listOf(first, second), Recurrence.excluded("20261120T070000Z,20261122T070000Z"))
        assertEquals(listOf(first), Recurrence.excluded("Europe/Warsaw;20261120T080000"))
        assertEquals(listOf(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()), Recurrence.excluded("20261120"))
        assertEquals(emptyList<Long>(), Recurrence.excluded(null))
        assertEquals(listOf(first), Recurrence.excluded("nonsense,20261120T070000Z"))
        // In order, each once
        assertEquals("20261120T070000Z,20261122T070000Z", Recurrence.exdate(listOf(second, first, second), allDay = false))
        assertEquals("20261120", Recurrence.exdate(listOf(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()), allDay = true))
        assertEquals(null, Recurrence.exdate(emptyList(), allDay = false))
    }
}
