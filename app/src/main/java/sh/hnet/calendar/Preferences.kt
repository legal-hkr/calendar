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
import kotlinx.coroutines.flow.onEach
import java.time.DayOfWeek
import sh.hnet.calendar.model.CalendarView
import sh.hnet.calendar.model.ReminderTimes

private val Context.settings by preferencesDataStore("settings")

/**
 * @param view The view the main screen was left on, which it opens with (SPEC §3.8)
 * @param eventList Month's "Event list" switch (SPEC §3.4): the compact grid with the chosen day's list instead of the full grid
 * @param hourHeight The height of an hour in Week and Day in dp, set by pinching (SPEC §7.9)
 */
data class ViewSettings(
    val view: CalendarView,
    val eventList: Boolean,
    val hourHeight: Float,
    /** Settings › View (SPEC §16.2): the view the app opens with, null for the one it was left on. */
    val startView: CalendarView? = null,
    /** The day weeks start with; null for the system's. */
    val weekStart: DayOfWeek? = null,
    /** The hour Week and Day open at on days other than today. */
    val firstHour: Int = FIRST_HOUR,
    val weekNumbers: Boolean = false,
    val dimPast: Boolean = true,
    val showDeclined: Boolean = false,
) {
    companion object {
        const val FIRST_HOUR = 7
        const val HOUR_HEIGHT = 60f
        const val HOUR_HEIGHT_MIN = 40f
        const val HOUR_HEIGHT_MAX = 120f
    }
}

/**
 * Settings › Events (SPEC §16.3): what a new event starts with.
 *
 * @param calendar The calendar's key ([sh.hnet.calendar.model.CalendarInfo.key]); null for the one the last new event went to
 * @param length In minutes
 */
data class EventDefaults(val calendar: String? = null, val length: Int = LENGTH, val remindersTimed: List<Int> = ReminderTimes.defaults(false), val remindersAllDay: List<Int> = ReminderTimes.defaults(true)) {
    fun reminders(allDay: Boolean): List<Int> = if (allDay) remindersAllDay else remindersTimed

    companion object {
        const val LENGTH = 60
        val LENGTHS = listOf(15, 30, 45, 60, 90, 120)
    }
}

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    /** How the main screen was left, read together so it is drawn once and right. */
    val viewSettings: Flow<ViewSettings> = context.settings.data.map {
        val start = it[START_VIEW]?.let { key -> CalendarView.entries.firstOrNull { view -> view.key == key } }
        ViewSettings(
            view = start ?: CalendarView.of(it[VIEW]),
            eventList = it[EVENT_LIST] ?: false,
            hourHeight = (it[HOUR_HEIGHT] ?: ViewSettings.HOUR_HEIGHT).coerceIn(ViewSettings.HOUR_HEIGHT_MIN, ViewSettings.HOUR_HEIGHT_MAX),
            startView = start,
            weekStart = it[WEEK_START]?.takeIf { day -> day in 1..7 }?.let(DayOfWeek::of),
            firstHour = (it[FIRST_HOUR] ?: ViewSettings.FIRST_HOUR).coerceIn(0, 23),
            weekNumbers = it[WEEK_NUMBERS] ?: false,
            dimPast = it[DIM_PAST] ?: true,
            showDeclined = it[SHOW_DECLINED] ?: false,
        )
    }.distinctUntilChanged().onEach { latest = it }

    /** The settings as last read, for what can't wait for the store; null before the first reading. */
    @Volatile
    var latest: ViewSettings? = null
        private set

    suspend fun setStartView(view: CalendarView?) = context.settings.edit { if (view == null) it.remove(START_VIEW) else it[START_VIEW] = view.key }

    suspend fun setWeekStart(day: DayOfWeek?) = context.settings.edit { if (day == null) it.remove(WEEK_START) else it[WEEK_START] = day.value }

    suspend fun setFirstHour(hour: Int) = context.settings.edit { it[FIRST_HOUR] = hour }

    suspend fun setWeekNumbers(on: Boolean) = context.settings.edit { it[WEEK_NUMBERS] = on }

    suspend fun setDimPast(on: Boolean) = context.settings.edit { it[DIM_PAST] = on }

    suspend fun setShowDeclined(on: Boolean) = context.settings.edit { it[SHOW_DECLINED] = on }

    val eventDefaults: Flow<EventDefaults> = context.settings.data.map {
        EventDefaults(
            calendar = it[DEFAULT_CALENDAR],
            length = (it[DEFAULT_LENGTH] ?: EventDefaults.LENGTH).takeIf { minutes -> minutes in EventDefaults.LENGTHS } ?: EventDefaults.LENGTH,
            remindersTimed = it[REMINDERS_TIMED]?.let(::minutes) ?: ReminderTimes.defaults(false),
            remindersAllDay = it[REMINDERS_ALL_DAY]?.let(::minutes) ?: ReminderTimes.defaults(true),
        )
    }.distinctUntilChanged()

    private fun minutes(text: String): List<Int> = text.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted().take(ReminderTimes.MAX)

    suspend fun setDefaultCalendar(key: String?) = context.settings.edit { if (key == null) it.remove(DEFAULT_CALENDAR) else it[DEFAULT_CALENDAR] = key }

    suspend fun setDefaultLength(minutes: Int) = context.settings.edit { it[DEFAULT_LENGTH] = minutes }

    /** The usual reminders of new events, timed or [allDay]; an empty list is "none", which is not the same as never chosen. */
    suspend fun setDefaultReminders(allDay: Boolean, minutes: List<Int>) = context.settings.edit { it[if (allDay) REMINDERS_ALL_DAY else REMINDERS_TIMED] = minutes.joinToString(",") }

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
        private val START_VIEW = stringPreferencesKey("start_view")
        private val WEEK_START = intPreferencesKey("week_start")
        private val FIRST_HOUR = intPreferencesKey("first_hour")
        private val WEEK_NUMBERS = booleanPreferencesKey("week_numbers")
        private val DIM_PAST = booleanPreferencesKey("dim_past")
        private val SHOW_DECLINED = booleanPreferencesKey("show_declined")
        private val DEFAULT_CALENDAR = stringPreferencesKey("default_calendar")
        private val DEFAULT_LENGTH = intPreferencesKey("default_length")
        private val REMINDERS_TIMED = stringPreferencesKey("reminders_timed")
        private val REMINDERS_ALL_DAY = stringPreferencesKey("reminders_all_day")
        private val VIEW = stringPreferencesKey("view")
        private val EVENT_LIST = booleanPreferencesKey("event_list")
        private val HOUR_HEIGHT = floatPreferencesKey("hour_height")
        private val LAST_CALENDAR = stringPreferencesKey("last_calendar")
    }
}
