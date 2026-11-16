package sh.hnet.calendar.reminders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import kotlinx.coroutines.launch
import sh.hnet.calendar.CalendarApplication

/** The alarm set for the next reminder, and the notifications' Snooze and dismissal; reached only by the app's own pending intents. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CalendarApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> app.reminders.snooze(
                        intent.getLongExtra(ReminderNotifications.EXTRA_EVENT_ID, 0), intent.getLongExtra(ReminderNotifications.EXTRA_BEGIN, 0),
                        intent.getLongExtra(ReminderNotifications.EXTRA_END, 0), intent.getIntExtra(ReminderNotifications.EXTRA_NOTIFICATION, 0),
                    )
                    ACTION_DISMISSED -> ReminderNotifications.refreshSummary(app)
                    else -> app.reminders.sync()
                }
            } catch (_: Exception) {
                // Access taken away meanwhile, or the provider refused: the next look tries again
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "sh.hnet.calendar.action.REMINDERS_DUE"
        const val ACTION_SNOOZE = "sh.hnet.calendar.action.SNOOZE"
        const val ACTION_DISMISSED = "sh.hnet.calendar.action.DISMISSED"
    }
}

/** After a restart of the phone, an update of the app, or a change of the clock, the time zone or the language: everything is worked out anew. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CalendarApplication
        // The channel's name follows the language
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) ReminderNotifications.ensureChannel(app)
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.reminders.sync()
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Tells the reminders of a change in the calendar while the app is closed – a sync by the
 * account's app above all. The system runs the job when the provider's content changes; such a
 * job runs once, so it is set again each time.
 */
class ChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as CalendarApplication
        app.appScope.launch {
            try {
                app.reminders.sync()
            } catch (_: Exception) {
            } finally {
                // Set again before this run is given back, as the platform's own sample does: a job that is still
                // running counts as waiting, so [ensure] would leave it be and the next change would wake nobody
                schedule(app)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val ID = 1

        /** Sets the job unless it is waiting already. */
        fun ensure(context: Context) {
            if (context.getSystemService(JobScheduler::class.java).getPendingJob(ID) == null) schedule(context)
        }

        private fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(ID, ComponentName(context, ChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // A sync writes many rows: wait for it to settle, but not longer than ten seconds
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            scheduler.schedule(job)
        }
    }
}
