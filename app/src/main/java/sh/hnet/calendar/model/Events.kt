package sh.hnet.calendar.model

import java.time.LocalDate

/**
 * A calendar as the system's calendar provider lists it. [color] and [shown] come from the
 * provider (the palette color nearest to the account's, the provider's "visible") until the
 * user's own choices are laid over them ([CalendarChoices.apply]).
 */
data class CalendarInfo(
    val id: Long,
    /** What the user's choices are kept under: account and the calendar's own name there, which outlive the provider's row id. */
    val key: String,
    val name: String,
    val accountName: String,
    val accountType: String,
    /** The palette color the calendar is shown in. */
    val color: CalendarColor,
    val writable: Boolean,
    /** Whether its events are shown in the app. */
    val shown: Boolean = true,
    /** False for a calendar its account does not sync. */
    val synced: Boolean = true,
) {
    /** A calendar that lives only on the phone. */
    val local: Boolean get() = accountType == LOCAL_ACCOUNT_TYPE

    companion object {
        /** `CalendarContract.ACCOUNT_TYPE_LOCAL`. */
        const val LOCAL_ACCOUNT_TYPE = "LOCAL"

        fun key(accountType: String, accountName: String, name: String): String = "$accountType|$accountName|$name"
    }
}

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
