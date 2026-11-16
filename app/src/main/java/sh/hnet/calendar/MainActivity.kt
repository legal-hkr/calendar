package sh.hnet.calendar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.hnet.calendar.about.WhatsNew
import sh.hnet.calendar.reminders.ReminderNotifications
import sh.hnet.calendar.ui.Screen
import sh.hnet.calendar.ui.CalendarApp
import sh.hnet.calendar.ui.theme.CalendarTheme

class MainActivity : ComponentActivity() {
    /** What the app was started for – the event of a tapped reminder, a file to import –, until it has opened that page. */
    private var asked by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            WhatsNew.recordStart(this)
            asked = event(intent) ?: file(intent)
        }
        setContent {
            CalendarTheme { CalendarApp(asked, onOpened = { asked = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (event(intent) ?: file(intent))?.let { asked = it }
    }

    /** A calendar file opened with the app or shared to it (SPEC §13.1). The permission to read it lasts as long as this activity. */
    private fun file(intent: Intent?): Screen.Import? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        return Screen.Import(uri.toString(), System.nanoTime())
    }

    /** The occurrence a reminder's notification names (SPEC §14.3). */
    private fun event(intent: Intent?): Screen.Event? {
        if (intent?.action != ReminderNotifications.ACTION_OPEN_EVENT) return null
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_EVENT_ID, 0)
        // The tapped reminder goes by itself; once it has gone, the summary counts anew
        window.decorView.postDelayed({ ReminderNotifications.refreshSummary(applicationContext) }, 1_000)
        return if (id > 0) Screen.Event(id, intent.getLongExtra(ReminderNotifications.EXTRA_BEGIN, 0), intent.getLongExtra(ReminderNotifications.EXTRA_END, 0)) else null
    }

    /** Back on screen: access may have been given meanwhile, and the reminders follow the calendar while the app runs. */
    override fun onStart() {
        super.onStart()
        (application as CalendarApplication).reminders.request()
    }

    /** The app leaves the screen: a delete still offering Undo is carried out, as nobody is left to undo it (the owner, 15 Nov 2026). */
    override fun onStop() {
        super.onStop()
        (application as CalendarApplication).deletes.commit()
    }
}
