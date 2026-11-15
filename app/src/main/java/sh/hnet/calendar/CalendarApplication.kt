package sh.hnet.calendar

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class CalendarApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }
}
