package sh.hnet.calendar.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.model.CalendarChoices
import sh.hnet.calendar.model.ReminderPlan

/**
 * The app posts the reminders itself (the owner's decision; the logic agreed 16 Nov 2026): it
 * reads them from the provider, shows what is due, and sets one exact alarm for the nearest one
 * to come. [sync] does all of it and is run after the alarm, after any change of the calendar –
 * by a sync too, with the app closed ([ChangeJob]) –, after a restart of the phone, a change of
 * the clock or the time zone, an update of the app, and at least twice a day.
 */
class ReminderScheduler(private val app: CalendarApplication) {
    private val store = ReminderStore(app)
    private val lock = Mutex()
    private var watching: Job? = null

    /** Works the reminders out anew, off the caller's thread; while the app runs it also follows the calendar's changes. */
    fun request() {
        watch()
        app.appScope.launch { runCatching { sync() } }
    }

    /** With the app alive its own observer tells of changes sooner than the system's job would. */
    private fun watch() {
        if (watching != null || !app.calendars.hasAccess()) return
        watching = app.appScope.launch {
            launch {
                // A sync writes many rows: wait for it to settle
                app.calendars.changes.collectLatest {
                    delay(SETTLE)
                    runCatching { sync() }
                }
            }
            // A calendar hidden or shown again: hidden ones don't remind (the owner, 16 Nov 2026)
            app.calendarChoices.choices.drop(1).collectLatest { runCatching { sync() } }
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        lock.withLock {
            val alarms = app.getSystemService(AlarmManager::class.java)
            val alarm = PendingIntent.getBroadcast(app, 0, Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (!app.calendars.hasAccess()) {
                alarms.cancel(alarm)
                return@withLock
            }
            ChangeJob.ensure(app)
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val calendars = CalendarChoices.apply(app.calendars.calendars(), app.calendarChoices.choices.first()).filter { it.shown }.mapTo(HashSet()) { it.id }
            val reminders = try {
                app.calendars.reminders(now, calendars)
            } catch (_: SecurityException) {
                alarms.cancel(alarm)
                return@withLock
            }
            store.prune(now)
            val shown = store.shown()
            val due = ReminderPlan.due(reminders, now, zone, shown)
            val done = due + ReminderPlan.missed(reminders, now, zone, shown)
            // On the first look nothing is brought up: those reminders were due before the app reminded of anything
            val allowed = ReminderNotifications.allowed(app)
            if (store.started && allowed) {
                // One notification for an occurrence, however many of its reminders have come
                for (reminder in due.distinctBy { it.occurrence }) {
                    ReminderNotifications.post(app, reminder.eventId, reminder.begin, reminder.end, reminder.allDay, reminder.title, reminder.location, now, zone)
                }
            }
            store.markShown(done.associate { it.key to ReminderPlan.endsAt(it, zone) })
            store.started = true

            // Put off earlier and due again
            val snoozed = store.snoozed()
            val (back, waiting) = snoozed.partition { it.until <= now }
            for (one in back) {
                val event = app.calendars.event(one.eventId, one.begin, one.end) ?: continue
                if (allowed) ReminderNotifications.post(app, one.eventId, one.begin, one.end, event.allDay, event.title, event.location, now, zone)
            }
            if (back.isNotEmpty()) store.setSnoozed(waiting)

            // The nearest of: the next reminder, the next one put off, and the look that comes anyway
            val next = listOfNotNull(ReminderPlan.next(reminders, now, zone, store.shown()), waiting.minOfOrNull { it.until }, now + FALLBACK).min()
            set(alarms, next, alarm)
        }
    }

    /**
     * With `USE_EXACT_ALARM` exact alarms are the app's from install and can't be switched off (lint
     * knows only the permission the user grants, as in Clock); should the system say otherwise, a
     * late reminder is better than none.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun set(alarms: AlarmManager, at: Long, alarm: PendingIntent) {
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
    }

    /** Snooze (SPEC §14.3): the notification goes and comes back after the length set in Settings › Reminders. */
    suspend fun snooze(eventId: Long, begin: Long, end: Long, notification: Int) {
        val minutes = app.preferences.snoozeMinutes.first()
        lock.withLock {
            store.setSnoozed(store.snoozed().filterNot { it.eventId == eventId && it.begin == begin } + Snoozed(eventId, begin, end, System.currentTimeMillis() + minutes * 60_000L))
        }
        ReminderNotifications.cancel(app, notification)
        sync()
    }

    private companion object {
        const val SETTLE = 2_000L
        const val FALLBACK = 12 * 3_600_000L
    }
}
