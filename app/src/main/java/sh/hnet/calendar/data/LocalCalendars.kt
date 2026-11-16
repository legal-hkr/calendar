package sh.hnet.calendar.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.model.IcsEvent
import sh.hnet.calendar.model.CalendarInfo.Companion.LOCAL_ACCOUNT_TYPE
import sh.hnet.calendar.model.Recurrence

/**
 * Calendars that live only on the phone (SPEC §12.3–12.5; the logic agreed with the owner, 16 Nov
 * 2026): no app syncs them, so this one creates, renames and deletes them – any local calendar,
 * whoever made it. The provider lets calendars be made and deleted only in the name of their
 * account, which for a local one is nobody's but the phone's.
 */
class LocalCalendars(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** The provider's address for writing in the name of the local account [name]. */
    private fun asAccount(uri: Uri, name: String): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
        .build()

    /** A new local calendar called [name]; the key its choices are kept under ([CalendarInfo.key]), null when the provider refused. */
    suspend fun create(name: String, color: CalendarColor): String? = withContext(Dispatchers.IO) {
        // Its own name in the account never changes: the user's color stays with it through renames
        val own = "local-" + UUID.randomUUID()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
            put(Calendars.NAME, own)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            // What other calendar apps show it in; this one keeps the user's choice itself
            put(Calendars.CALENDAR_COLOR, color.reference or OPAQUE)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        resolver.insert(asAccount(Calendars.CONTENT_URI, ACCOUNT), values)?.let { CalendarInfo.key(LOCAL_ACCOUNT_TYPE, ACCOUNT, own) }
    }

    suspend fun rename(calendar: CalendarInfo, name: String) = withContext(Dispatchers.IO) {
        resolver.update(asAccount(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id), calendar.accountName), ContentValues().apply { put(Calendars.CALENDAR_DISPLAY_NAME, name) }, null, null)
    }

    /** Removes the calendar with all its events, at once and for good: there is no server to tell and nothing to undo it from. */
    suspend fun delete(calendar: CalendarInfo) = withContext(Dispatchers.IO) {
        resolver.delete(asAccount(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id), calendar.accountName), null, null)
    }

    /** How many events the calendar holds, a repeating one counted once. */
    suspend fun eventCount(calendarId: Long): Int = withContext(Dispatchers.IO) {
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.ORIGINAL_ID} IS NULL", arrayOf(calendarId.toString()), null)?.use { it.count } ?: 0
    }

    /** Everything the calendar holds, as its export writes it (SPEC §12.3). */
    suspend fun events(calendarId: Long): List<IcsEvent> = withContext(Dispatchers.IO) {
        resolver.storedEvents("${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(calendarId.toString())).map { it.event }
    }

    private companion object {
        /** The account the app's own local calendars are in; nothing shows it but other calendar apps. */
        const val ACCOUNT = "Local"
        const val OPAQUE = 0xFF000000.toInt()
    }
}

/** An event as the provider has it, in the shape files are written from and compared with. */
class StoredEvent(val id: Long, val calendarId: Long, val event: IcsEvent)

/**
 * The events matching [selection] as [IcsEvent]s, with their reminders. An occurrence changed on
 * its own is written under its series' id when the series is among them; one whose series is not
 * stands alone.
 */
internal fun android.content.ContentResolver.storedEvents(selection: String, arguments: Array<String>?): List<StoredEvent> {
    val columns = arrayOf(
        Events._ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
        Events.RRULE, Events.EXDATE, Events.STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.UID_2445, Events.CALENDAR_ID,
    )
    class Row(val id: Long, val calendarId: Long, val original: Long?, val event: IcsEvent)
    val rows = mutableListOf<Row>()
    query(Events.CONTENT_URI, columns, selection, arguments, "${Events.DTSTART} ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            rows += Row(
                id, cursor.getLong(17), if (cursor.isNull(14)) null else cursor.getLong(14),
                IcsEvent(
                    // The same in every export, so that a file read back is known again
                    uid = cursor.getString(16)?.takeIf { it.isNotBlank() } ?: "event-$id@sh.hnet.calendar",
                    title = cursor.getString(1).orEmpty().trim(),
                    location = cursor.getString(2).orEmpty().trim(),
                    description = cursor.getString(3).orEmpty().trim(),
                    start = cursor.getLong(4),
                    end = if (cursor.isNull(5)) null else cursor.getLong(5),
                    duration = cursor.getString(6)?.takeIf { it.isNotEmpty() },
                    allDay = cursor.getInt(7) != 0,
                    zone = cursor.getString(8)?.takeIf { it.isNotEmpty() },
                    rrule = cursor.getString(9)?.takeIf { it.isNotEmpty() },
                    exdates = Recurrence.excluded(cursor.getString(10)),
                    status = if (cursor.isNull(11)) EventStatus.CONFIRMED else when (cursor.getInt(11)) {
                        Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
                        Events.STATUS_CANCELED -> EventStatus.CANCELED
                        else -> EventStatus.CONFIRMED
                    },
                    busy = cursor.getInt(12) != Events.AVAILABILITY_FREE,
                    private = cursor.getInt(13).let { it == Events.ACCESS_PRIVATE || it == Events.ACCESS_CONFIDENTIAL },
                    originalStart = if (cursor.isNull(15)) null else cursor.getLong(15),
                ),
            )
        }
    }
    // The reminders of these events: asked for by their ids when they are few, else read whole
    val reminders = HashMap<Long, MutableList<Int>>()
    val few = rows.size <= REMINDER_IDS
    val own = if (few) "${Reminders.EVENT_ID} IN (${rows.joinToString(",") { it.id.toString() }})" else null
    if (rows.isNotEmpty()) {
        query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES), own, null, null)?.use { cursor ->
            while (cursor.moveToNext()) reminders.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getInt(1)
        }
    }
    val uids = rows.associate { it.id to it.event.uid }
    return rows.map { row ->
        val series = row.original?.let { uids[it] }
        val event = row.event.copy(reminders = reminders[row.id].orEmpty())
        StoredEvent(row.id, row.calendarId, if (series != null) event.copy(originalUid = series) else event.copy(originalStart = null))
    }
}

/** Up to so many events have their reminders asked for by id. */
private const val REMINDER_IDS = 200
