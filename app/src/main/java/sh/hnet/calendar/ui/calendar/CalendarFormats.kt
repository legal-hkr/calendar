package sh.hnet.calendar.ui.calendar

import android.app.LocaleManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import sh.hnet.calendar.CalendarApplication
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Dates and times in the app's language and the system's clock (SPEC §2.8). */
class CalendarFormats(val locale: Locale, val hours24: Boolean, val zone: ZoneId) {
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (hours24) "Hm" else "hm"), locale)
    private val hour: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "h"), locale)
    private val day: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    private val dayWithYear: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMdy"), locale)
    private val dayOfMonth: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)
    private val month: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "LLLLy"), locale)

    fun time(millis: Long): String = time.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The start time on a tile, where every character counts: a 12-hour clock leaves out the minutes of a full hour ("9 AM"). */
    fun tileTime(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        return if (!hours24 && at.minute == 0) hour.format(at) else time.format(at)
    }

    /** "Wednesday, November 18"; with the year outside [thisYear]. */
    fun day(date: LocalDate, thisYear: Int): String = (if (date.year == thisYear) day else dayWithYear).format(date)

    /** The top bar's period: "November 2026". */
    fun month(date: LocalDate): String = capitalize(month.format(date))

    /** The top bar's period in Day: "November 18". */
    fun dayOfMonth(date: LocalDate): String = capitalize(dayOfMonth.format(date))

    /** An hour's label beside the time grid: "08:00", or "8 AM". */
    fun hour(hour: Int): String = (if (hours24) time else this.hour).format(LocalTime.of(hour, 0))

    fun weekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)

    fun capitalize(text: String): String = text.replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun rememberCalendarFormats(zone: ZoneId): CalendarFormats {
    val locale = LocalConfiguration.current.locales[0]
    val hours24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(locale, hours24, zone) { CalendarFormats(locale, hours24, zone) }
}

/**
 * The day weeks start with (SPEC §2.8): the one set in the system's regional preferences, else the
 * custom of the region – the system's, when the app's language names none. Settings › View can name another ([rememberWeekStart]).
 */
fun weekStart(app: Locale, system: Locale): DayOfWeek {
    val chosen = app.getUnicodeLocaleType("fw") ?: system.getUnicodeLocaleType("fw")
    WEEKDAYS[chosen]?.let { return it }
    val regional = if (app.country.isEmpty() && system.country.isNotEmpty()) Locale.Builder().setLocale(app).setRegion(system.country).build() else app
    return WeekFields.of(regional).firstDayOfWeek
}

/** The day weeks start with by the system and the language alone: what "System default" in Settings › View stands for. */
@Composable
fun rememberSystemWeekStart(): DayOfWeek {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { systemWeekStart(context, locale) }
}

/** The day weeks start with by the system and the app's language [locale] alone ("System default" in Settings › View); the widgets use it too. */
fun systemWeekStart(context: Context, locale: Locale): DayOfWeek {
    val system = context.getSystemService(LocaleManager::class.java).systemLocales
    return weekStart(locale, if (system.isEmpty) locale else system[0])
}

/** The day weeks start with: the one chosen in Settings › View, else the system's. */
@Composable
fun rememberWeekStart(): DayOfWeek {
    val preferences = (LocalContext.current.applicationContext as CalendarApplication).preferences
    val settings by preferences.viewSettings.collectAsStateWithLifecycle(preferences.latest)
    return settings?.weekStart ?: rememberSystemWeekStart()
}

private val WEEKDAYS = mapOf(
    "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
)
