package sh.hnet.calendar

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.calendar.data.CalendarChoiceStore
import sh.hnet.calendar.data.CalendarSource
import sh.hnet.calendar.data.DeleteQueue
import sh.hnet.calendar.data.EventWriter
import sh.hnet.calendar.data.LocalCalendars
import sh.hnet.calendar.reminders.ReminderNotifications
import sh.hnet.calendar.reminders.ReminderScheduler

class CalendarApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    /** The system's calendar provider. */
    val calendars: CalendarSource by lazy { CalendarSource(this) }

    /** Everything the app writes to the provider. */
    val writer: EventWriter by lazy { EventWriter(this) }

    /** Deletes waiting for their Undo snackbar to go. */
    val deletes: DeleteQueue by lazy { DeleteQueue(appScope, writer) }

    /** Creating, renaming and deleting the calendars that live only on the phone. */
    val localCalendars: LocalCalendars by lazy { LocalCalendars(this) }

    /** The user's color and visibility per calendar. */
    val calendarChoices: CalendarChoiceStore by lazy { CalendarChoiceStore(this) }

    /** Works out and posts the reminders of events. */
    val reminders: ReminderScheduler by lazy { ReminderScheduler(this) }

    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.ensureChannel(this)
        // Every start of the process, whatever started it, leaves the next reminder set
        reminders.request()
    }
}
