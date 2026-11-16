package sh.hnet.calendar.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One reminder of one occurrence, as the provider has it: [minutes] before the occurrence starts
 * (below zero: after). For an all-day event [begin] and [end] are midnights in UTC and the
 * reminder counts from the midnight its first day begins with on this phone.
 */
data class Reminder(val eventId: Long, val begin: Long, val end: Long, val allDay: Boolean, val minutes: Int, val title: String = "", val location: String = "") {
    /** What tells this reminder from every other; a changed start makes it a new one. */
    val key: String get() = "$eventId|$begin|$minutes"

    /** The occurrence it belongs to: one notification stands for all the reminders of one. */
    val occurrence: String get() = "$eventId|$begin"
}

/**
 * When reminders appear (the logic agreed with the owner, 16 Nov 2026): the app works the times
 * out itself and sets one alarm for the nearest. A reminder is shown once; one whose time passed
 * while the phone was off is still shown as long as its event has not ended.
 */
object ReminderPlan {
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L
    private const val SHORTEST = 15 * MINUTE

    /** An instant of an event on this phone: an all-day event's midnight in UTC is the midnight of [zone]. */
    fun local(millis: Long, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() else millis

    /**
     * When the reminder's event is over on this phone. An all-day event lasts at least the day it
     * starts on, whatever end it was stored with: one without a proper end must not count as over
     * at the midnight it begins with.
     */
    fun endsAt(reminder: Reminder, zone: ZoneId): Long =
        // A timed event counts as going on for a quarter of an hour at least: one without a length – a ticket with a start alone –
        // would be over at the very moment its reminder "at the start" is due
        if (reminder.allDay) local(maxOf(reminder.end, reminder.begin + DAY), true, zone) else maxOf(reminder.end, reminder.begin + SHORTEST)

    fun fireAt(reminder: Reminder, zone: ZoneId): Long = local(reminder.begin, reminder.allDay, zone) - reminder.minutes * MINUTE

    /** The reminders to show at [now]: their time has come, they were not [shown] yet, and their event has not ended. */
    fun due(reminders: List<Reminder>, now: Long, zone: ZoneId, shown: Set<String>): List<Reminder> =
        reminders.filter { it.key !in shown && fireAt(it, zone) <= now && endsAt(it, zone) > now }

    /** Reminders whose time has come for an event that is over: never shown, only remembered as done. */
    fun missed(reminders: List<Reminder>, now: Long, zone: ZoneId, shown: Set<String>): List<Reminder> =
        reminders.filter { it.key !in shown && fireAt(it, zone) <= now && endsAt(it, zone) <= now }

    /** When the next reminder is due after [now]; null when none is in sight. */
    fun next(reminders: List<Reminder>, now: Long, zone: ZoneId, shown: Set<String>): Long? =
        reminders.filter { it.key !in shown }.map { fireAt(it, zone) }.filter { it > now }.minOrNull()
}
