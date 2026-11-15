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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import sh.hnet.calendar.model.Answer
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarInfo
import sh.hnet.calendar.model.EventStatus
import sh.hnet.calendar.model.Occurrence

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
            Instances.ALL_DAY, Instances.START_DAY, Instances.END_DAY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS,
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
                )
            }
        }
        result
    }
}
