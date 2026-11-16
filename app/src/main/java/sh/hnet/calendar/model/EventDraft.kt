package sh.hnet.calendar.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Which occurrences of a repeating event a change or a delete is for (SPEC §10.7). */
enum class Scope { ONLY_THIS, FOLLOWING, ALL }

/**
 * An event as the editor holds it (SPEC §10). [start] and [end] are wall-clock times in [zone];
 * an all-day event runs from the date of [start] through the date of [end], and keeps its times
 * only for when "All day" is turned off again.
 */
data class EventDraft(
    val calendarId: Long,
    val title: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: String,
    val location: String = "",
    val description: String = "",
    val busy: Boolean = true,
    val private: Boolean = false,
) {
    /** Moving the start keeps the length (SPEC §10.4). */
    fun withStartDate(date: LocalDate): EventDraft = moveStart(LocalDateTime.of(date, start.toLocalTime()))

    fun withStartTime(time: LocalTime): EventDraft = moveStart(LocalDateTime.of(start.toLocalDate(), time))

    private fun moveStart(to: LocalDateTime): EventDraft = copy(start = to, end = to.plus(Duration.between(start, end)))

    /** The end is never before the start (SPEC §10.4): an earlier one becomes the start itself. */
    fun withEndDate(date: LocalDate): EventDraft {
        val day = maxOf(date, start.toLocalDate())
        val to = LocalDateTime.of(day, end.toLocalTime())
        return copy(end = if (allDay) to else maxOf(to, start))
    }

    fun withEndTime(time: LocalTime): EventDraft = copy(end = maxOf(LocalDateTime.of(end.toLocalDate(), time), start))

    /** Turning "All day" off brings the times back; an end that would lie before the start is put an hour after it. */
    fun withAllDay(on: Boolean): EventDraft = if (on || end >= start) copy(allDay = on) else copy(allDay = false, end = start.plusHours(1))

    /** Start and end as the provider keeps them: an all-day event from midnight UTC of its first day to midnight UTC after its last. */
    val begin: Long get() = if (allDay) start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    val finish: Long get() = if (allDay) end.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else maxOf(end.atZone(ZoneId.of(zone)).toInstant().toEpochMilli(), begin)

    /** The length as a repeating event stores it (RFC 5545 duration). */
    val duration: String get() = if (allDay) "P${ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1}D" else "PT${(finish - begin) / 1000}S"

    /** The zone the provider gets: all-day events are kept in UTC. */
    val storedZone: String get() = if (allDay) "UTC" else zone

    companion object {
        /**
         * A new event (SPEC §10.3): on [day], at [time] or else at the next full hour after [now],
         * [minutes] long.
         */
        fun new(calendarId: Long, day: LocalDate, now: LocalDateTime, zone: ZoneId, time: LocalTime? = null, minutes: Long = 60): EventDraft {
            val start = if (time != null) LocalDateTime.of(day, time) else LocalDateTime.of(day, now.toLocalTime().truncatedTo(ChronoUnit.HOURS)).plusHours(1)
            return EventDraft(calendarId = calendarId, start = start, end = start.plusMinutes(minutes), zone = zone.id)
        }

        /** The occurrence [event] as the editor opens it; times in the event's own zone, [phone]'s when it has none. */
        fun of(event: EventDetails, phone: ZoneId): EventDraft {
            val start: LocalDateTime
            val end: LocalDateTime
            val zone: String
            if (event.allDay) {
                val first = Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = maxOf(Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1), first)
                start = LocalDateTime.of(first, DAY_START)
                end = LocalDateTime.of(last, DAY_START.plusHours(1))
                zone = phone.id
            } else {
                val own = event.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: phone
                start = Instant.ofEpochMilli(event.begin).atZone(own).toLocalDateTime()
                end = Instant.ofEpochMilli(maxOf(event.end, event.begin)).atZone(own).toLocalDateTime()
                zone = own.id
            }
            return EventDraft(event.calendarId, event.title, event.allDay, start, end, zone, event.location, event.description, event.busy, event.private)
        }

        /** The time an all-day event gets when "All day" is turned off. */
        private val DAY_START: LocalTime = LocalTime.of(9, 0)
    }
}
