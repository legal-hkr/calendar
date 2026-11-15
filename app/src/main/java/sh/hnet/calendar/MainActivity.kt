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
}
