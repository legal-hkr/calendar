package sh.hnet.calendar.model

/** Someone invited to an event, as the provider lists them. */
data class Guest(val name: String, val email: String, val answer: Answer, val organizer: Boolean) {
    /** The name when there is one, else the address. */
    val label: String get() = name.ifBlank { email }
}

/**
 * One occurrence of an event with everything its page shows (SPEC §9). [begin] and [end] are the
 * occurrence's, as in [Occurrence]; the rest is the event's.
 *
 * @param timeZone The zone the event was saved in; null when the provider has none
 * @param rule The repetition rule (RRULE) of a repeating event, else null
 * @param exception Whether this is one occurrence of a repeating event that was changed on its own
 * @param reminders Minutes before the start, each once, in rising order
 * @param invited Whether the calendar's owner is among the guests of someone else's event, so there is an answer to give
 */
data class EventDetails(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    val description: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String?,
    val rule: String?,
    val exception: Boolean,
    val busy: Boolean,
    val private: Boolean,
    val status: EventStatus,
    val organizer: String,
    val guests: List<Guest>,
    val reminders: List<Int>,
    val invited: Boolean,
    val answer: Answer,
) {
    val repeating: Boolean get() = rule != null

    /** Who organizes it, by name when the guest list has one. */
    val organizerLabel: String get() = guests.firstOrNull { it.organizer }?.label ?: organizer

    val accepted: Int get() = guests.count { it.answer == Answer.ACCEPTED }
    val unanswered: Int get() = guests.count { it.answer == Answer.INVITED || it.answer == Answer.NONE }
}
