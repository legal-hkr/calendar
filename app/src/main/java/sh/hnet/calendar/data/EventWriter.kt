package sh.hnet.calendar.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.calendar.model.Answer
import sh.hnet.calendar.model.EventDraft
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.model.IcsEvent
import sh.hnet.calendar.model.ReminderTimes
import sh.hnet.calendar.model.Recurrence
import sh.hnet.calendar.model.Repetition
import sh.hnet.calendar.model.RuleEnd
import sh.hnet.calendar.model.Scope

/**
 * Everything the app writes to the system's calendar provider. It writes as an ordinary app, not
 * as a sync adapter: the provider marks the rows as changed and the account's app (DAVx⁵) sends
 * them on at its next sync.
 */
class EventWriter(private val context: Context) {
    private val resolver get() = context.contentResolver

    companion object {
        /**
         * Whether the user changed how the event repeats – apart from what a move of its start does
         * to the rule by itself ("weekly on Wednesday" put on a Thursday). A change of the rule is
         * not for one occurrence alone, so then "Only this event" is not offered.
         */
        /** Ends the ids the app gives its own events. */
        const val UID_SUFFIX = "@sh.hnet.calendar"

        fun repetitionEdited(original: EventDraft, draft: EventDraft): Boolean =
            draft.repetition != original.repetition.movedTo(original.start.toLocalDate(), draft.start.toLocalDate())
    }

    /** What a stored event is, as far as writing needs to know. */
    private class Row(
        val calendarId: Long, val start: Long, val allDay: Boolean, val zone: String?, val rule: String?, val status: Int?, val exception: Boolean,
        /** The occurrences taken out of the series (EXDATE). */
        val exdate: String?,
        /** Whether the account's app has sent the event to its server, which gives it its id there. */
        val synced: Boolean,
    )

    private fun row(eventId: Long): Row? {
        val projection = arrayOf(Events.CALENDAR_ID, Events.DTSTART, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.STATUS, Events.ORIGINAL_ID, Events.EXDATE, Events._SYNC_ID)
        return resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), projection, "${Events.DELETED} = 0", null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            Row(
                cursor.getLong(0), cursor.getLong(1), cursor.getInt(2) != 0, cursor.getString(3), cursor.getString(4)?.takeIf { it.isNotEmpty() },
                if (cursor.isNull(5)) null else cursor.getInt(5), !cursor.isNull(6), cursor.getString(7)?.takeIf { it.isNotEmpty() }, !cursor.getString(8).isNullOrEmpty(),
            )
        }
    }

    /**
     * The columns in which [draft] differs from [from] – all of them without [from]. A repeating
     * event keeps its length as a duration and has no end; any other has an end and no duration.
     */
    private fun values(draft: EventDraft, from: EventDraft?, repeating: Boolean): ContentValues = ContentValues().apply {
        if (from == null || draft.title != from.title) put(Events.TITLE, draft.title.trim())
        if (from == null || draft.location != from.location) put(Events.EVENT_LOCATION, draft.location.trim())
        if (from == null || draft.description != from.description) put(Events.DESCRIPTION, draft.description.trim())
        if (from == null || draft.busy != from.busy) put(Events.AVAILABILITY, if (draft.busy) Events.AVAILABILITY_BUSY else Events.AVAILABILITY_FREE)
        if (from == null || draft.private != from.private) put(Events.ACCESS_LEVEL, if (draft.private) Events.ACCESS_PRIVATE else Events.ACCESS_DEFAULT)
        if (from == null || timeChanged(draft, from)) {
            put(Events.DTSTART, draft.begin)
            put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, draft.storedZone)
            if (repeating) {
                put(Events.DURATION, draft.duration)
                putNull(Events.DTEND)
            } else {
                put(Events.DTEND, draft.finish)
                putNull(Events.DURATION)
            }
        }
    }

    private fun timeChanged(draft: EventDraft, from: EventDraft): Boolean =
        draft.allDay != from.allDay || draft.begin != from.begin || draft.finish != from.finish || draft.storedZone != from.storedZone

    /** A new event in the draft's calendar, with its reminders and its repetition. Its id. */
    suspend fun insert(draft: EventDraft): Long = withContext(Dispatchers.IO) {
        val rule = draft.rrule
        val values = values(draft, null, repeating = rule != null)
        values.put(Events.CALENDAR_ID, draft.calendarId)
        if (rule != null) values.put(Events.RRULE, rule)
        // Its id in files: an export writes it, and an import knows the event again by it
        values.put(Events.UID_2445, UUID.randomUUID().toString() + UID_SUFFIX)
        val id = ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI, values)) { "The provider refused the event" })
        setReminders(id, draft.reminders, replace = false)
        id
    }

    /**
     * Writes a file's events ([sh.hnet.calendar.model.IcsImport.prepare]d) to the calendar
     * [calendarId] (SPEC §13), each with the reminders [reminders] gives it. An event the provider
     * refuses – a rule it can't read – is left out. The ids of the events written, in the order given.
     */
    suspend fun import(calendarId: Long, events: List<IcsEvent>, phone: ZoneId, reminders: (IcsEvent) -> List<Int>): List<Long> = withContext(Dispatchers.IO) {
        val ids = ArrayList<Long>()
        for (event in events) {
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, event.title)
                put(Events.EVENT_LOCATION, event.location)
                put(Events.DESCRIPTION, event.description)
                put(Events.DTSTART, event.start)
                put(Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else event.zone ?: phone.id)
                if (event.rrule != null) {
                    put(Events.RRULE, event.rrule)
                    put(Events.DURATION, event.duration ?: if (event.allDay) "P1D" else "PT0S")
                    Recurrence.exdate(event.exdates, event.allDay)?.let { put(Events.EXDATE, it) }
                } else {
                    put(Events.DTEND, event.end ?: event.start)
                }
                put(Events.AVAILABILITY, if (event.busy) Events.AVAILABILITY_BUSY else Events.AVAILABILITY_FREE)
                if (event.private) put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
                when (event.status) {
                    EventStatus.CANCELED -> put(Events.STATUS, Events.STATUS_CANCELED)
                    EventStatus.TENTATIVE -> put(Events.STATUS, Events.STATUS_TENTATIVE)
                    EventStatus.CONFIRMED -> Unit
                }
                if (event.uid.isNotEmpty()) put(Events.UID_2445, event.uid)
            }
            val id = try {
                resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId)
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            setReminders(id, reminders(event).distinct().sorted().take(ReminderTimes.MAX), replace = false)
            ids += id
        }
        ids
    }

    /**
     * A file's newer version of the saved event [eventId] (SPEC §13.5): what the event says and
     * when it is become the file's; its reminders stay as they are. The start and the rule go
     * together, as every change of a series has to name both.
     */
    suspend fun updateFrom(eventId: Long, event: IcsEvent, phone: ZoneId) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Events.TITLE, event.title)
            put(Events.EVENT_LOCATION, event.location)
            put(Events.DESCRIPTION, event.description)
            put(Events.DTSTART, event.start)
            put(Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else event.zone ?: phone.id)
            if (event.rrule != null) {
                put(Events.RRULE, event.rrule)
                put(Events.DURATION, event.duration ?: if (event.allDay) "P1D" else "PT0S")
                putNull(Events.DTEND)
                Recurrence.exdate(event.exdates, event.allDay)?.let { put(Events.EXDATE, it) } ?: putNull(Events.EXDATE)
            } else {
                put(Events.DTEND, event.end ?: event.start)
                putNull(Events.RRULE)
                putNull(Events.DURATION)
                putNull(Events.EXDATE)
            }
            put(Events.AVAILABILITY, if (event.busy) Events.AVAILABILITY_BUSY else Events.AVAILABILITY_FREE)
            put(Events.ACCESS_LEVEL, if (event.private) Events.ACCESS_PRIVATE else Events.ACCESS_DEFAULT)
            put(Events.STATUS, when (event.status) {
                EventStatus.CANCELED -> Events.STATUS_CANCELED
                EventStatus.TENTATIVE -> Events.STATUS_TENTATIVE
                EventStatus.CONFIRMED -> Events.STATUS_CONFIRMED
            })
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
        if (event.status == EventStatus.CANCELED) showAgain(eventId)
    }

    /** A cancellation from a file (SPEC §13.6): the event stays in its calendar, marked as canceled. */
    suspend fun markCanceled(eventId: Long) = withContext(Dispatchers.IO) {
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null)
        showAgain(eventId)
    }

    /**
     * The provider takes an event out of its list of occurrences the moment an update marks it
     * canceled (CalendarProvider2.handleUpdateEvents), though it lists one that was written
     * canceled. An update of the times alone, to what they are, puts it back – for an event that
     * doesn't repeat; a canceled series the provider never lays out, so that one stays out of sight.
     */
    private fun showAgain(eventId: Long) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        resolver.query(uri, arrayOf(Events.DTSTART, Events.DTEND, Events.RRULE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(1) || !cursor.getString(2).isNullOrEmpty()) return
            resolver.update(uri, ContentValues().apply { put(Events.DTSTART, cursor.getLong(0)); put(Events.DTEND, cursor.getLong(1)) }, null, null)
        }
    }

    /** Takes a whole import back (SPEC §13.7): the events with [ids] are deleted. */
    suspend fun deleteAll(ids: List<Long>) = withContext(Dispatchers.IO) {
        for (id in ids) resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
    }

    /** The reminders of [eventId] are [minutes] before the start, as alerts. */
    private fun setReminders(eventId: Long, minutes: List<Int>, replace: Boolean = true) {
        if (replace) resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        for (value in minutes) {
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, value)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    /**
     * Saves [draft] over the event [eventId], whose occurrence starting at [instanceBegin] was
     * opened as [original]. [scope] says which occurrences of a repeating event are meant; an event
     * that doesn't repeat, or an occurrence that was already changed on its own, is simply changed.
     * False when the event is gone.
     */
    suspend fun update(eventId: Long, instanceBegin: Long, original: EventDraft, draft: EventDraft, scope: Scope): Boolean = withContext(Dispatchers.IO) {
        val row = row(eventId) ?: return@withContext false
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val reminders = draft.reminders != original.reminders
        // The copy of an occurrence or a series takes the reminders as they are stored, unless the form changed them
        fun remind(id: Long) = if (reminders) setReminders(id, draft.reminders, replace = false) else copyReminders(eventId, id)
        when {
            row.rule == null -> {
                val rule = draft.rrule
                // An event that starts to repeat is written whole: its length becomes a duration
                val values = if (rule != null) values(draft, null, repeating = true).apply { put(Events.RRULE, rule) } else values(draft, original, repeating = false)
                if (values.size() > 0) resolver.update(uri, values, null, null)
                if (reminders) setReminders(eventId, draft.reminders)
            }
            repetitionEdited(original, draft) -> rewrite(eventId, row, instanceBegin, original, draft, scope)
            scope == Scope.ONLY_THIS && !row.synced -> {
                // See [exclude]: the occurrence becomes an event of its own, then the series leaves it out. In this order a failure leaves too much, never too little
                val copy = copyOf(eventId)
                copy.putAll(values(draft.copy(repetition = Repetition.None), null, repeating = false))
                val id = ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI, copy)) { "The provider refused the event" })
                remind(id)
                copyGuests(eventId, id)
                exclude(eventId, row, instanceBegin)
            }
            scope == Scope.ONLY_THIS -> {
                // The provider copies the series' row, reminders and guests into an exception and lays these values over it.
                // It computes the end from the duration, and would mark the exception tentative if no status were given
                val values = values(draft, original, repeating = true)
                values.remove(Events.DTEND)
                values.put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
                values.put(Events.STATUS, row.status ?: Events.STATUS_CONFIRMED)
                val made = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values)
                if (reminders && made != null) setReminders(ContentUris.parseId(made), draft.reminders)
            }
            scope == Scope.FOLLOWING && instanceBegin > row.start -> {
                // A new series from this occurrence on, then the old one ends before it. In this order a failure leaves too much, never too little
                val passed = occurrencesBefore(eventId, row, instanceBegin)
                val rule = Recurrence.moved(Recurrence.continuing(row.rule, passed), original.start.toLocalDate(), draft.start.toLocalDate())
                val copy = copyOf(eventId)
                copy.putAll(values(draft, null, repeating = true))
                copy.put(Events.RRULE, rule)
                // The later occurrences that were taken out stay out of the new series
                val gone = Recurrence.excluded(row.exdate).filter { it >= instanceBegin }
                if (gone.isNotEmpty()) copy.put(Events.EXDATE, Recurrence.exdate(gone.map(mover(row, row.rule, original, draft)), draft.allDay))
                val id = ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI, copy)) { "The provider refused the event" })
                remind(id)
                copyGuests(eventId, id)
                endBefore(eventId, row, instanceBegin)
            }
            else -> {
                // The whole series: a change of the day or the time moves every occurrence by as much
                val values = values(draft, original, repeating = true)
                if (values.containsKey(Events.DTSTART)) {
                    val from = original.start.toLocalDate()
                    val to = draft.start.toLocalDate()
                    val rule = Recurrence.moved(row.rule, from, to)
                    val moved = mover(row, row.rule, original, draft)
                    values.put(Events.DTSTART, moved(row.start))
                    // Always with the rule, changed or not: see [endBefore]
                    values.put(Events.RRULE, rule)
                    // The occurrences taken out move along, or they would come back
                    val gone = Recurrence.excluded(row.exdate)
                    if (gone.isNotEmpty()) values.put(Events.EXDATE, Recurrence.exdate(gone.map(moved), draft.allDay))
                }
                if (values.size() > 0) resolver.update(uri, values, null, null)
                if (reminders) setReminders(eventId, draft.reminders)
            }
        }
        true
    }

    /**
     * A series whose repetition the user changed (the logic agreed with the owner, 16 Nov 2026).
     * "This and following" ends the old series before the occurrence and writes what the form
     * holds from there on. "All" – also when the occurrence is the first – changes the series
     * itself: a new rule holds from its beginning, the first occurrence being put on the first day
     * the rule names; "Does not repeat" leaves the one event the form shows.
     */
    private fun rewrite(eventId: Long, row: Row, instanceBegin: Long, original: EventDraft, draft: EventDraft, scope: Scope) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val reminders = draft.reminders != original.reminders
        val simple = (draft.repetition as? Repetition.Simple)?.rule
        val before = (original.repetition as? Repetition.Simple)?.rule
        if (scope == Scope.FOLLOWING && instanceBegin > row.start) {
            val copy = copyOf(eventId)
            copy.putAll(values(draft, null, repeating = simple != null))
            if (simple != null) {
                // A count the user left alone goes on counting: the occurrences that stay behind are taken off it
                val end = simple.end
                val rule = if (end is RuleEnd.Count && before?.end == end) simple.copy(end = RuleEnd.Count((end.times - occurrencesBefore(eventId, row, instanceBegin)).coerceAtLeast(1))) else simple
                copy.put(Events.RRULE, rule.toRRule(draft.start, ZoneId.of(draft.zone), draft.allDay))
            }
            val id = ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI, copy)) { "The provider refused the event" })
            if (reminders) setReminders(id, draft.reminders, replace = false) else copyReminders(eventId, id)
            copyGuests(eventId, id)
            endBefore(eventId, row, instanceBegin)
            return
        }
        val from = original.start.toLocalDate()
        val to = draft.start.toLocalDate()
        // Only the end was changed: the occurrences stay where they are, with what was taken out of them
        val samePattern = simple != null && before?.movedTo(from, to)?.pattern == simple.pattern
        val values = values(draft, original, repeating = simple != null)
        values.put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
        values.put(Events.EVENT_TIMEZONE, draft.storedZone)
        if (simple == null) {
            values.put(Events.DTSTART, draft.begin)
            values.put(Events.DTEND, draft.finish)
            values.putNull(Events.DURATION)
            values.putNull(Events.RRULE)
            values.putNull(Events.EXDATE)
        } else {
            val stored = if (row.allDay) ZoneOffset.UTC else zone(row.zone, original.zone)
            val first = simple.firstFrom(Instant.ofEpochMilli(row.start).atZone(stored).toLocalDate(), to)
            val start = LocalDateTime.of(first, draft.start.toLocalTime())
            values.put(Events.DTSTART, (if (draft.allDay) first.atStartOfDay(ZoneOffset.UTC) else start.atZone(ZoneId.of(draft.zone))).toInstant().toEpochMilli())
            values.put(Events.DURATION, draft.duration)
            values.putNull(Events.DTEND)
            values.put(Events.RRULE, simple.toRRule(start, ZoneId.of(draft.zone), draft.allDay))
            val gone = Recurrence.excluded(row.exdate)
            if (gone.isNotEmpty()) {
                if (samePattern) values.put(Events.EXDATE, Recurrence.exdate(gone.map(mover(row, requireNotNull(row.rule), original, draft)), draft.allDay)) else values.putNull(Events.EXDATE)
            }
        }
        resolver.update(uri, values, null, null)
        // Occurrences changed on their own belonged to the old pattern
        if (!samePattern) resolver.delete(Events.CONTENT_URI, "${Events.ORIGINAL_ID} = ?", arrayOf(eventId.toString()))
        if (reminders) setReminders(eventId, draft.reminders)
    }

    /**
     * Where an occurrence of the series [row] starts once the one opened as [original] was put
     * where [draft] has it: at the draft's time, and as many days on. Of a rule with several
     * weekdays only the moved one changes, so there a day moves only when it is that weekday.
     */
    private fun mover(row: Row, rule: String, original: EventDraft, draft: EventDraft): (Long) -> Long {
        val from = original.start.toLocalDate()
        val to = draft.start.toLocalDate()
        val stored = if (row.allDay) ZoneOffset.UTC else zone(row.zone, original.zone)
        val whole = !Recurrence.severalWeekdays(rule) || from.dayOfWeek == to.dayOfWeek
        val days = ChronoUnit.DAYS.between(from, to)
        return { millis ->
            var day = Instant.ofEpochMilli(millis).atZone(stored).toLocalDate()
            if (whole || day.dayOfWeek == from.dayOfWeek) day = day.plusDays(days)
            val start = if (draft.allDay) day.atStartOfDay(ZoneOffset.UTC) else LocalDateTime.of(day, draft.start.toLocalTime()).atZone(ZoneId.of(draft.zone))
            start.toInstant().toEpochMilli()
        }
    }

    private fun zone(stored: String?, fallback: String): ZoneId = stored?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of(fallback)

    /** Deletes the event [eventId], or of a repeating one the occurrences [scope] names, counted from the one starting at [instanceBegin]. */
    suspend fun delete(eventId: Long, instanceBegin: Long, scope: Scope) = withContext(Dispatchers.IO) {
        val row = row(eventId) ?: return@withContext
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        when {
            row.rule == null || scope == Scope.ALL -> resolver.delete(uri, null, null)
            scope == Scope.ONLY_THIS && !row.synced -> exclude(eventId, row, instanceBegin)
            scope == Scope.ONLY_THIS -> {
                // One occurrence is taken out by an exception that is canceled
                resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), ContentValues().apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
                    put(Events.STATUS, Events.STATUS_CANCELED)
                })
            }
            instanceBegin > row.start -> endBefore(eventId, row, instanceBegin)
            // "This and following" from the first occurrence is all of them
            else -> resolver.delete(uri, null, null)
        }
    }

    /**
     * The series [eventId] ends before the occurrence at [instanceBegin]; occurrences changed on
     * their own from then on go with it. The start is written along with the rule, unchanged: the
     * provider lays a series' occurrences out anew only when an update names both – without the
     * start it leaves the old ones standing, without the rule it takes the event for one that
     * doesn't repeat (CalendarInstancesHelper.updateInstancesLocked reads the update's values, not the row).
     */
    private fun endBefore(eventId: Long, row: Row, instanceBegin: Long) {
        val rule = requireNotNull(row.rule)
        val values = ContentValues().apply {
            put(Events.RRULE, Recurrence.endingBefore(rule, instanceBegin))
            put(Events.DTSTART, row.start)
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
        resolver.delete(Events.CONTENT_URI, "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} >= ?", arrayOf(eventId.toString(), instanceBegin.toString()))
    }

    /**
     * Takes the occurrence at [instanceBegin] out of the series [eventId] by naming it in EXDATE.
     * This is how one occurrence of a series the account's app has not sent on yet – in a calendar
     * kept on the phone, every series – is changed or deleted: the provider ties an exception to its
     * series by the id the server gave (ORIGINAL_SYNC_ID), and without one it loses the series'
     * other occurrences when the exception is made, and would show the changed occurrence twice
     * once it lays them out again (CalendarInstancesHelper: updateRecurrenceInstancesLocked's
     * "passing rowId is wrong" and performInstanceExpansion's originalEventColumn).
     */
    private fun exclude(eventId: Long, row: Row, instanceBegin: Long) {
        val values = ContentValues().apply {
            put(Events.EXDATE, Recurrence.exdate(Recurrence.excluded(row.exdate) + instanceBegin, row.allDay))
            put(Events.DTSTART, row.start)
            put(Events.RRULE, row.rule)
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
    }

    /**
     * How many occurrences of the series [eventId] come before [instanceBegin], as a COUNT counts
     * them: those changed on their own, canceled or taken out included.
     */
    private fun occurrencesBefore(eventId: Long, row: Row, instanceBegin: Long): Int {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, row.start)
            ContentUris.appendId(it, instanceBegin - 1)
        }.build()
        val id = eventId.toString()
        val before = instanceBegin.toString()
        val standing = resolver.query(uri, arrayOf(Instances.BEGIN), "${Instances.EVENT_ID} = ? AND ${Instances.BEGIN} < ?", arrayOf(id, before), null)?.use { it.count } ?: 0
        val changed = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} < ?", arrayOf(id, before), null)?.use { it.count } ?: 0
        return standing + changed + Recurrence.excluded(row.exdate).count { it < instanceBegin }
    }

    /** The columns of [eventId] a new series takes over as they are; what the editor holds is laid over them. */
    private fun copyOf(eventId: Long): ContentValues {
        val columns = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.EVENT_COLOR_KEY,
            Events.ORGANIZER, Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.STATUS,
        )
        val values = ContentValues()
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                columns.forEachIndexed { index, column ->
                    when {
                        cursor.isNull(index) -> Unit
                        cursor.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
                        else -> values.put(column, cursor.getString(index))
                    }
                }
            }
        }
        return values
    }

    private fun copyReminders(from: Long, to: Long) {
        resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID} = ?", arrayOf(from.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, to)
                    put(Reminders.MINUTES, cursor.getInt(0))
                    put(Reminders.METHOD, cursor.getInt(1))
                })
            }
        }
    }

    private fun copyGuests(from: Long, to: Long) {
        val columns = arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS)
        resolver.query(Attendees.CONTENT_URI, columns, "${Attendees.EVENT_ID} = ?", arrayOf(from.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.insert(Attendees.CONTENT_URI, ContentValues().apply {
                    put(Attendees.EVENT_ID, to)
                    put(Attendees.ATTENDEE_NAME, cursor.getString(0))
                    put(Attendees.ATTENDEE_EMAIL, cursor.getString(1))
                    put(Attendees.ATTENDEE_RELATIONSHIP, cursor.getInt(2))
                    put(Attendees.ATTENDEE_TYPE, cursor.getInt(3))
                    put(Attendees.ATTENDEE_STATUS, cursor.getInt(4))
                })
            }
        }
    }

    /**
     * Moves the event [eventId] to the draft's calendar. The provider can't move a row between
     * calendars, so the event is written anew there and deleted here; only offered for an event
     * without repetition and guests, which loses nothing by it.
     */
    suspend fun move(eventId: Long, draft: EventDraft): Long = withContext(Dispatchers.IO) {
        val id = insert(draft)
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
        id
    }

    /** The calendar owner's answer to the invitation [eventId] (SPEC §9.3). It reaches the organizer with the account's next sync. */
    suspend fun setAnswer(eventId: Long, answer: Answer) = withContext(Dispatchers.IO) {
        val owner = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Calendars.OWNER_ACCOUNT), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@withContext
        val status = when (answer) {
            Answer.ACCEPTED -> Attendees.ATTENDEE_STATUS_ACCEPTED
            Answer.TENTATIVE -> Attendees.ATTENDEE_STATUS_TENTATIVE
            Answer.DECLINED -> Attendees.ATTENDEE_STATUS_DECLINED
            else -> return@withContext
        }
        resolver.update(
            Attendees.CONTENT_URI, ContentValues().apply { put(Attendees.ATTENDEE_STATUS, status) },
            "${Attendees.EVENT_ID} = ? AND ${Attendees.ATTENDEE_EMAIL} = ? COLLATE NOCASE", arrayOf(eventId.toString(), owner),
        )
    }

    /**
     * What the event [eventId] holds now, to tell on saving whether a sync changed it while it was
     * being edited (SPEC §10.11); null when it is gone.
     */
    suspend fun fingerprint(eventId: Long): String? = withContext(Dispatchers.IO) {
        val columns = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.RRULE, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.STATUS,
        )
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), columns, "${Events.DELETED} = 0", null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            columns.indices.joinToString("\u0000") { cursor.getString(it) ?: "\u0001" }
        }
    }
}
