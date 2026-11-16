package sh.hnet.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.calendar.about.WhatsNew
import sh.hnet.calendar.ui.CalendarApp
import sh.hnet.calendar.ui.theme.CalendarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        setContent {
            CalendarTheme { CalendarApp() }
        }
    }

    /** The app leaves the screen: a delete still offering Undo is carried out, as nobody is left to undo it (the owner, 15 Nov 2026). */
    override fun onStop() {
        super.onStop()
        (application as CalendarApplication).deletes.commit()
    }
}
