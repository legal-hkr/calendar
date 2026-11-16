package sh.hnet.calendar.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderPlanTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    // 14:00–16:00 in Warsaw (UTC+1 in November)
    private fun timed(minutes: Int, id: Long = 1) = Reminder(id, at("2026-11-18T13:00:00Z"), at("2026-11-18T15:00:00Z"), false, minutes)
    private fun allDay(minutes: Int) = Reminder(2, at("2026-11-18T00:00:00Z"), at("2026-11-19T00:00:00Z"), true, minutes)

    @Test
    fun aTimedReminderCountsFromTheStart() {
        assertEquals(at("2026-11-18T12:45:00Z"), ReminderPlan.fireAt(timed(15), warsaw))
        assertEquals(at("2026-11-18T13:00:00Z"), ReminderPlan.fireAt(timed(0), warsaw))
    }

    @Test
    fun anAllDayReminderCountsFromMidnightHere() {
        // On the day at 9:00 in Warsaw is 08:00 UTC; the day before at 18:00 is 17:00 UTC
        assertEquals(at("2026-11-18T08:00:00Z"), ReminderPlan.fireAt(allDay(-540), warsaw))
        assertEquals(at("2026-11-17T17:00:00Z"), ReminderPlan.fireAt(allDay(360), warsaw))
        // The same event in New York starts five hours later than midnight UTC
        assertEquals(at("2026-11-18T14:00:00Z"), ReminderPlan.fireAt(allDay(-540), ZoneId.of("America/New_York")))
    }

    @Test
    fun whatIsDueIsShownOnce() {
        val reminders = listOf(timed(15), timed(60))
        assertEquals(emptyList<Reminder>(), ReminderPlan.due(reminders, at("2026-11-18T11:59:00Z"), warsaw, emptySet()))
        assertEquals(listOf(timed(60)), ReminderPlan.due(reminders, at("2026-11-18T12:00:00Z"), warsaw, emptySet()))
        assertEquals(listOf(timed(15)), ReminderPlan.due(reminders, at("2026-11-18T12:50:00Z"), warsaw, setOf(timed(60).key)))
        assertEquals(emptyList<Reminder>(), ReminderPlan.due(reminders, at("2026-11-18T12:50:00Z"), warsaw, setOf(timed(60).key, timed(15).key)))
    }

    @Test
    fun aLateReminderIsShownWhileItsEventLasts() {
        // The phone was off: at 15:30 the event still runs, at 16:00 it is over
        assertEquals(listOf(timed(15)), ReminderPlan.due(listOf(timed(15)), at("2026-11-18T14:30:00Z"), warsaw, emptySet()))
        assertEquals(emptyList<Reminder>(), ReminderPlan.due(listOf(timed(15)), at("2026-11-18T15:00:00Z"), warsaw, emptySet()))
        assertEquals(listOf(timed(15)), ReminderPlan.missed(listOf(timed(15)), at("2026-11-18T15:00:00Z"), warsaw, emptySet()))
        // An all-day event lasts until midnight here, an hour before midnight UTC
        assertEquals(listOf(allDay(-540)), ReminderPlan.due(listOf(allDay(-540)), at("2026-11-18T22:59:00Z"), warsaw, emptySet()))
        assertEquals(emptyList<Reminder>(), ReminderPlan.due(listOf(allDay(-540)), at("2026-11-18T23:00:00Z"), warsaw, emptySet()))
    }

    @Test
    fun theNextAlarmIsTheNearestReminderNotShown() {
        val reminders = listOf(timed(15), timed(60), allDay(-540))
        val now = at("2026-11-18T07:00:00Z")
        assertEquals(at("2026-11-18T08:00:00Z"), ReminderPlan.next(reminders, now, warsaw, emptySet()))
        assertEquals(at("2026-11-18T12:00:00Z"), ReminderPlan.next(reminders, now, warsaw, setOf(allDay(-540).key)))
        assertNull(ReminderPlan.next(reminders, at("2026-11-18T13:00:00Z"), warsaw, emptySet()))
    }

    @Test
    fun aMovedEventRemindsAgain() {
        val moved = timed(15).copy(begin = at("2026-11-18T14:00:00Z"), end = at("2026-11-18T16:00:00Z"))
        assertEquals(listOf(moved), ReminderPlan.due(listOf(moved), at("2026-11-18T13:50:00Z"), warsaw, setOf(timed(15).key)))
        assertEquals("1|${timed(15).begin}", timed(15).occurrence)
    }
}
