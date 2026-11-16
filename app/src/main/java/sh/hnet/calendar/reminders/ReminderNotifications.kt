package sh.hnet.calendar.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.net.toUri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import sh.hnet.calendar.MainActivity
import sh.hnet.calendar.R
import sh.hnet.calendar.ui.calendar.CalendarFormats

/**
 * The reminder notification (SPEC §14, board 31): the event's title, when and where; Snooze and –
 * with a place and a maps app – Directions; a tap opens the event's page. One notification stands
 * for an occurrence, several are gathered under "Reminders: 3". The system's look, the channel's sound.
 */
object ReminderNotifications {
    const val CHANNEL = "reminders"
    private const val GROUP = "reminders"
    private const val ID_SUMMARY = 1

    const val ACTION_OPEN_EVENT = "sh.hnet.calendar.action.OPEN_EVENT"
    const val EXTRA_EVENT_ID = "sh.hnet.calendar.extra.EVENT_ID"
    const val EXTRA_BEGIN = "sh.hnet.calendar.extra.BEGIN"
    const val EXTRA_END = "sh.hnet.calendar.extra.END"
    const val EXTRA_NOTIFICATION = "sh.hnet.calendar.extra.NOTIFICATION"

    fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Whether a reminder would be seen: the app may post, and the user has not blocked the reminders' channel. */
    fun allowed(context: Context): Boolean {
        val manager = manager(context)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } != false
    }

    /** Creates the channel (again after a language change, so its name follows it). Sound and vibration are the system's to set (SPEC §14.6). */
    fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_reminders_description)
            },
        )
    }

    /** The notification's id for an occurrence; the summary's is kept free. */
    fun id(eventId: Long, begin: Long): Int = ("$eventId|$begin".hashCode() and Int.MAX_VALUE).coerceAtLeast(ID_SUMMARY + 1)

    /** "14:00–16:00, place"; an all-day event "All day"; on another day than today the day first: "Tomorrow, 08:00" (SPEC §14.2). */
    fun text(context: Context, begin: Long, end: Long, allDay: Boolean, location: String, now: Long, zone: ZoneId): String {
        val formats = CalendarFormats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context), zone)
        val day = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = when {
            allDay -> context.getString(R.string.label_all_day)
            end > begin -> context.getString(R.string.label_time_range, formats.time(begin), formats.time(end))
            else -> formats.time(begin)
        }
        val dayName = when (day) {
            today -> null
            today.plusDays(1) -> context.getString(R.string.label_tomorrow)
            else -> formats.dayOfMonth(day)
        }
        val whenText = if (dayName == null) time else context.getString(R.string.notification_day_and_time, dayName, time)
        val place = location.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return if (place == null) whenText else context.getString(R.string.notification_time_and_place, whenText, place)
    }

    fun post(context: Context, eventId: Long, begin: Long, end: Long, allDay: Boolean, title: String, location: String, now: Long, zone: ZoneId) {
        val id = id(eventId, begin)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_EVENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_BEGIN, begin).putExtra(EXTRA_END, end),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SNOOZE)
                .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_BEGIN, begin).putExtra(EXTRA_END, end).putExtra(EXTRA_NOTIFICATION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Swiped away: the summary counts anew
        val dismissed = PendingIntent.getBroadcast(context, id, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DISMISSED), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val start = ReminderStart.of(begin, allDay, zone)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { context.getString(R.string.label_no_title) })
            .setContentText(text(context, begin, end, allDay, location, now, zone))
            .setWhen(start)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setGroup(GROUP)
            .addAction(Notification.Action.Builder(null as Icon?, context.getString(R.string.notification_action_snooze), snooze).build())
        directions(context, location, id)?.let { builder.addAction(Notification.Action.Builder(null as Icon?, context.getString(R.string.notification_action_directions), it).build()) }
        manager(context).notify(id, builder.build())
        refreshSummary(context)
    }

    /** The place handed to a maps app, when the event has one and the phone has such an app (SPEC §14.3). */
    private fun directions(context: Context, location: String, id: Int): PendingIntent? {
        if (location.isBlank()) return null
        val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(location)}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return null
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(context: Context, id: Int) {
        manager(context).cancel(id)
        refreshSummary(context, gone = id)
    }

    /**
     * Two reminders and more stand under one summary, "Reminders: 3", a line for each (SPEC §14.4);
     * one alone needs none. A summary that is there stays until the last reminder has gone, though:
     * **the system takes a group's notifications away with its summary**, so canceling it at one
     * left would take that one too (found by the owner, 16 Nov 2026). [gone] is a notification
     * just canceled, which the system may still list.
     */
    fun refreshSummary(context: Context, gone: Int = 0) {
        val manager = manager(context)
        val active = manager.activeNotifications
        val shown = active.filter { it.id != ID_SUMMARY && it.id != gone && it.notification.group == GROUP }
        if (shown.isEmpty()) {
            manager.cancel(ID_SUMMARY)
            return
        }
        if (shown.size < 2 && active.none { it.id == ID_SUMMARY }) return
        val title = context.getString(R.string.notification_group_summary, shown.size)
        val lines = Notification.InboxStyle().setBigContentTitle(title)
        for (one in shown.sortedBy { it.notification.`when` }) {
            val extras = one.notification.extras
            lines.addLine(context.getString(R.string.notification_summary_line, extras.getCharSequence(Notification.EXTRA_TITLE) ?: "", extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""))
        }
        manager.notify(
            ID_SUMMARY,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setStyle(lines)
                .setGroup(GROUP)
                .setGroupSummary(true)
                // The reminders make their sound themselves
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build(),
        )
    }
}

/** When an occurrence starts on this phone, for the notification's time. */
private object ReminderStart {
    fun of(begin: Long, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) LocalDate.ofEpochDay(Math.floorDiv(begin, 86_400_000L)).atStartOfDay(zone).toInstant().toEpochMilli() else begin
}
