package sh.hnet.calendar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import sh.hnet.calendar.model.CalendarView

private val Context.settings by preferencesDataStore("settings")

/**
 * @param view The view the main screen was left on, which it opens with (SPEC §3.8)
 * @param eventList Month's "Event list" switch (SPEC §3.4): the compact grid with the chosen day's list instead of the full grid
 * @param hourHeight The height of an hour in Week and Day in dp, set by pinching (SPEC §7.9)
 */
data class ViewSettings(val view: CalendarView, val eventList: Boolean, val hourHeight: Float) {
    companion object {
        const val HOUR_HEIGHT = 60f
        const val HOUR_HEIGHT_MIN = 40f
        const val HOUR_HEIGHT_MAX = 120f
    }
}

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    /** How the main screen was left, read together so it is drawn once and right. */
    val viewSettings: Flow<ViewSettings> = context.settings.data.map {
        ViewSettings(CalendarView.of(it[VIEW]), it[EVENT_LIST] ?: false, (it[HOUR_HEIGHT] ?: ViewSettings.HOUR_HEIGHT).coerceIn(ViewSettings.HOUR_HEIGHT_MIN, ViewSettings.HOUR_HEIGHT_MAX))
    }.distinctUntilChanged()

    suspend fun setView(view: CalendarView) {
        context.settings.edit { it[VIEW] = view.key }
    }

    suspend fun setEventList(on: Boolean) {
        context.settings.edit { it[EVENT_LIST] = on }
    }

    /** The calendar the last new event went to ([sh.hnet.calendar.model.CalendarInfo.key]), where the next one starts. */
    val lastCalendar: Flow<String?> = context.settings.data.map { it[LAST_CALENDAR] }.distinctUntilChanged()

    suspend fun setLastCalendar(key: String) {
        context.settings.edit { it[LAST_CALENDAR] = key }
    }

    suspend fun setHourHeight(dp: Float) {
        context.settings.edit { it[HOUR_HEIGHT] = dp }
    }

    /** How long Snooze puts a reminder off, in minutes (SPEC §16.4). */
    val snoozeMinutes: Flow<Int> = context.settings.data.map { (it[SNOOZE] ?: SNOOZE_DEFAULT).takeIf { minutes -> minutes in SNOOZE_CHOICES } ?: SNOOZE_DEFAULT }.distinctUntilChanged()

    suspend fun setSnoozeMinutes(minutes: Int) {
        context.settings.edit { it[SNOOZE] = minutes }
    }

    /** Restore defaults (Settings › Reset): every setting of this store back to its default. */
    suspend fun restoreDefaults() = context.settings.edit { it.clear() }

    companion object {
        const val SNOOZE_DEFAULT = 10
        val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)

        private val SNOOZE = intPreferencesKey("snooze_minutes")
        private val VIEW = stringPreferencesKey("view")
        private val EVENT_LIST = booleanPreferencesKey("event_list")
        private val HOUR_HEIGHT = floatPreferencesKey("hour_height")
        private val LAST_CALENDAR = stringPreferencesKey("last_calendar")
    }
}
