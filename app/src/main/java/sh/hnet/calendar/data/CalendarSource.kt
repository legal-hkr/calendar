package sh.hnet.calendar.data

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import sh.hnet.calendar.model.Answer
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventDetails
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.model.Guest
import sh.hnet.calendar.model.Occurrence
import sh.hnet.calendar.model.Reminder
import sh.hnet.calendar.model.Search

/**
 * The system's calendar provider, where the events live (SPEC §1.3). The app reads and writes it
 * and never talks to a server; accounts and syncing belong to other apps.
 */
class CalendarSource(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Read and write are one group to the system: one question, one answer. */
    fun hasAccess(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Emits whenever anything in the provider changes (a sync in the background, another app). Collect only with access. */
    val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private val accountApps = HashMap<String, String>()

    /**
     * The name of the app an account type belongs to ("DAVx⁵"), as its account provider names
     * itself; null when the system doesn't tell.
     */
    fun accountApp(accountType: String): String? = synchronized(accountApps) {
        accountApps.getOrPut(accountType) {
            val description = AccountManager.get(context).authenticatorTypes.firstOrNull { it.type == accountType }
            val packages = context.packageManager
            description?.let { found ->
                runCatching { packages.getText(found.packageName, found.labelId, null)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { packages.getApplicationLabel(packages.getApplicationInfo(found.packageName, 0)).toString() }.getOrNull()
            }.orEmpty()
        }.ifEmpty { null }
    }

    /** Every calendar on the phone, ordered by account and name, as the provider has them: without the user's choices of color and visibility. */
    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.NAME, Calendars.VISIBLE, Calendars.SYNC_EVENTS,
        )
        val order = "${Calendars.ACCOUNT_NAME} COLLATE LOCALIZED, ${Calendars.CALENDAR_DISPLAY_NAME} COLLATE LOCALIZED"
        val result = mutableListOf<CalendarInfo>()
        resolver.query(Calendars.CONTENT_URI, projection, "${Calendars.DELETED} = 0", null, order)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                val accountName = cursor.getString(2).orEmpty()
                val accountType = cursor.getString(3).orEmpty()
                result += CalendarInfo(
                    id = cursor.getLong(0),
                    // The account's own name for the calendar (its address, for DAVx⁵) stays when the calendar is renamed
                    key = CalendarInfo.key(accountType, accountName, cursor.getString(6)?.takeIf { it.isNotEmpty() } ?: name),
                    name = name,
                    accountName = accountName,
                    accountType = accountType,
                    color = CalendarColor.nearest(cursor.getInt(4)),
                    writable = cursor.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    shown = cursor.getInt(7) != 0,
                    synced = cursor.getInt(8) != 0,
                )
            }
        }
        result
    }

    /**
     * The occurrences touching the Julian days [firstDay]..[lastDay] in the calendars with the ids
     * [calendars], repeating events already expanded by the provider, ordered by their start.
     */
    suspend fun occurrences(firstDay: Int, lastDay: Int, calendars: Set<Long>): List<Occurrence> = withContext(Dispatchers.IO) {
        if (calendars.isEmpty()) return@withContext emptyList()
        val uri = Instances.CONTENT_BY_DAY_URI.buildUpon().also {
            ContentUris.appendId(it, firstDay.toLong())
            ContentUris.appendId(it, lastDay.toLong())
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.START_DAY, Instances.END_DAY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS, Instances.ORIGINAL_ID,
        )
        val result = mutableListOf<Occurrence>()
        resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(1) !in calendars) continue
                result += Occurrence(
                    eventId = cursor.getLong(0),
                    calendarId = cursor.getLong(1),
                    title = cursor.getString(2).orEmpty().trim(),
                    location = cursor.getString(3).orEmpty().trim(),
                    begin = cursor.getLong(4),
                    end = cursor.getLong(5),
                    allDay = cursor.getInt(6) != 0,
                    startDay = cursor.getInt(7),
                    endDay = cursor.getInt(8),
                    // No status reads as 0, which is "tentative"
                    status = if (cursor.isNull(9)) EventStatus.CONFIRMED else when (cursor.getInt(9)) {
                        Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
                        Events.STATUS_CANCELED -> EventStatus.CANCELED
                        else -> EventStatus.CONFIRMED
                    },
                    answer = when (cursor.getInt(10)) {
                        Attendees.ATTENDEE_STATUS_ACCEPTED -> Answer.ACCEPTED
                        Attendees.ATTENDEE_STATUS_DECLINED -> Answer.DECLINED
                        Attendees.ATTENDEE_STATUS_INVITED -> Answer.INVITED
                        Attendees.ATTENDEE_STATUS_TENTATIVE -> Answer.TENTATIVE
                        else -> Answer.NONE
                    },
                    originalId = if (cursor.isNull(11)) 0 else cursor.getLong(11),
                )
            }
        }
        result
    }

    /**
     * The ids of the events in the calendars with the ids [calendars] whose title, place or
     * description has every one of [words] (SPEC §11.2), as [Search] compares them. An occurrence
     * changed on its own is an event of its own here, with its own texts.
     */
    suspend fun matching(words: List<String>, calendars: Set<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (words.isEmpty() || calendars.isEmpty()) return@withContext emptySet()
        val result = HashSet<Long>()
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION), "${Events.DELETED} = 0", null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(1) in calendars && Search.matches(words, cursor.getString(2), cursor.getString(3), cursor.getString(4))) result += cursor.getLong(0)
            }
        }
        result
    }

    /**
     * The saved events that carry one of [uids] – the ids events have in files –, each under its id
     * (SPEC §13.5): how an import knows an event again. An occurrence changed on its own doesn't
     * count as its series; of several events with one id the first is taken.
     */
    suspend fun byUid(uids: Set<String>): Map<String, StoredEvent> = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext emptyMap()
        resolver.storedEvents("${Events.DELETED} = 0 AND ${Events.UID_2445} IS NOT NULL AND ${Events.ORIGINAL_ID} IS NULL", null)
            .filter { it.event.uid in uids }.groupBy { it.event.uid }.mapValues { it.value.first() }
    }

    /**
     * The reminders of the occurrences that are running at [now] or start in the time the longest
     * reminder reaches ahead, in the calendars with the ids [calendars]; canceled events and
     * invitations the user declined have none. Reminders by e-mail or text message are another
     * app's to send.
     */
    suspend fun reminders(now: Long, calendars: Set<Long>): List<Reminder> = withContext(Dispatchers.IO) {
        if (calendars.isEmpty()) return@withContext emptyList()
        val minutes = HashMap<Long, MutableSet<Int>>()
        val methods = "${Reminders.METHOD} != ${Reminders.METHOD_EMAIL} AND ${Reminders.METHOD} != ${Reminders.METHOD_SMS}"
        resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES), methods, null, null)?.use { cursor ->
            while (cursor.moveToNext()) minutes.getOrPut(cursor.getLong(0)) { HashSet() } += cursor.getInt(1)
        }
        if (minutes.isEmpty()) return@withContext emptyList()
        // As far ahead as the longest reminder reaches, at most a year; a day back for all-day events, whose midnights are UTC's
        val reach = minutes.values.maxOf { it.max() }.coerceIn(0, REMINDER_REACH) * 60_000L
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - DAY_MILLIS)
            ContentUris.appendId(it, now + reach + DAY_MILLIS)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS,
        )
        val result = mutableListOf<Reminder>()
        resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val own = minutes[eventId] ?: continue
                if (cursor.getLong(1) !in calendars) continue
                if (!cursor.isNull(7) && cursor.getInt(7) == Events.STATUS_CANCELED) continue
                if (cursor.getInt(8) == Attendees.ATTENDEE_STATUS_DECLINED) continue
                for (value in own) {
                    result += Reminder(eventId, cursor.getLong(4), cursor.getLong(5), cursor.getInt(6) != 0, value, cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty().trim())
                }
            }
        }
        result
    }

    /**
     * The event [eventId] as its page shows it, for the occurrence from [begin] to [end]; null when
     * it is gone (deleted here or by a sync).
     */
    suspend fun event(eventId: Long, begin: Long, end: Long): EventDetails? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE,
            Events.ORIGINAL_ID, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.STATUS, Events.ORGANIZER, Calendars.OWNER_ACCOUNT,
        )
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), projection, "${Events.DELETED} = 0", null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            val organizer = cursor.getString(11).orEmpty()
            val owner = cursor.getString(12).orEmpty()
            val guests = guests(eventId)
            val self = guests.firstOrNull { owner.isNotEmpty() && it.email.equals(owner, ignoreCase = true) }
            EventDetails(
                eventId = eventId,
                calendarId = cursor.getLong(0),
                title = cursor.getString(1).orEmpty().trim(),
                location = cursor.getString(2).orEmpty().trim(),
                description = cursor.getString(3).orEmpty().trim(),
                begin = begin,
                end = end,
                allDay = cursor.getInt(4) != 0,
                timeZone = cursor.getString(5)?.takeIf { it.isNotEmpty() },
                rule = cursor.getString(6)?.takeIf { it.isNotEmpty() },
                exception = !cursor.isNull(7),
                busy = cursor.getInt(8) != Events.AVAILABILITY_FREE,
                private = cursor.getInt(9).let { it == Events.ACCESS_PRIVATE || it == Events.ACCESS_CONFIDENTIAL },
                status = if (cursor.isNull(10)) EventStatus.CONFIRMED else when (cursor.getInt(10)) {
                    Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
                    Events.STATUS_CANCELED -> EventStatus.CANCELED
                    else -> EventStatus.CONFIRMED
                },
                organizer = organizer,
                guests = guests,
                reminders = reminders(eventId),
                // The organizer of an event has nothing to answer
                invited = self != null && !organizer.equals(owner, ignoreCase = true),
                answer = self?.answer ?: Answer.NONE,
            )
        }
    }

    private fun guests(eventId: Long): List<Guest> {
        val projection = arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP)
        val result = mutableListOf<Guest>()
        resolver.query(Attendees.CONTENT_URI, projection, "${Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Guest(
                    name = cursor.getString(0).orEmpty().trim(),
                    email = cursor.getString(1).orEmpty().trim(),
                    answer = when (cursor.getInt(2)) {
                        Attendees.ATTENDEE_STATUS_ACCEPTED -> Answer.ACCEPTED
                        Attendees.ATTENDEE_STATUS_DECLINED -> Answer.DECLINED
                        Attendees.ATTENDEE_STATUS_TENTATIVE -> Answer.TENTATIVE
                        else -> Answer.INVITED
                    },
                    organizer = cursor.getInt(3) == Attendees.RELATIONSHIP_ORGANIZER,
                )
            }
        }
        // The organizer first, then by name
        return result.sortedWith(compareBy({ !it.organizer }, { it.label.lowercase() }))
    }

    private fun reminders(eventId: Long): List<Int> {
        val result = sortedSetOf<Int>()
        resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.getInt(0)
        }
        return result.toList()
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        /** No reminder is looked for further ahead than a year, in minutes. */
        const val REMINDER_REACH = 366 * 24 * 60
    }
}
