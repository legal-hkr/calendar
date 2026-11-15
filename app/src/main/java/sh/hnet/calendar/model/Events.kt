package sh.hnet.calendar.model

import java.time.LocalDate

/** A calendar as the system's calendar provider lists it. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    /** The palette color the calendar is shown in. */
    val color: CalendarColor,
    val writable: Boolean,
)

/** The event's own status. */
enum class EventStatus { CONFIRMED, TENTATIVE, CANCELED }

/** The user's answer to an invitation; NONE for an event without guests. */
enum class Answer { NONE, ACCEPTED, TENTATIVE, DECLINED, INVITED }

/**
 * One occurrence of an event, as the provider expands it. [startDay] and [endDay] are Julian days
 * in the phone's time zone, both inclusive: an event ending at midnight ends on the day before.
 */
data class Occurrence(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    /** Milliseconds since the epoch; for an all-day event midnight UTC of its first day and of the day after its last. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val startDay: Int,
    val endDay: Int,
    val status: EventStatus = EventStatus.CONFIRMED,
    val answer: Answer = Answer.NONE,
) {
    val tentative: Boolean get() = status == EventStatus.TENTATIVE || answer == Answer.TENTATIVE
    val canceled: Boolean get() = status == EventStatus.CANCELED
    val declined: Boolean get() = answer == Answer.DECLINED

    /** Whether the occurrence is over: an all-day one after its last day, a timed one at its end. */
    fun isPast(now: Long, today: Int): Boolean = if (allDay) endDay < today else end <= now
}

/** Julian day numbers, the provider's way of naming a day. */
object JulianDay {
    private const val EPOCH = 2440588L

    fun of(date: LocalDate): Int = (date.toEpochDay() + EPOCH).toInt()
    fun toDate(day: Int): LocalDate = LocalDate.ofEpochDay(day - EPOCH)
}
