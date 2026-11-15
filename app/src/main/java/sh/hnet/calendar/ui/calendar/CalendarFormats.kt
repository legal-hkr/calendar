package sh.hnet.calendar.ui.calendar

import android.app.LocaleManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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
 * custom of the region – the system's, when the app's language names none. Settings › View gets its own choice with stage 8.
 */
fun weekStart(app: Locale, system: Locale): DayOfWeek {
    val chosen = app.getUnicodeLocaleType("fw") ?: system.getUnicodeLocaleType("fw")
    WEEKDAYS[chosen]?.let { return it }
    val regional = if (app.country.isEmpty() && system.country.isNotEmpty()) Locale.Builder().setLocale(app).setRegion(system.country).build() else app
    return WeekFields.of(regional).firstDayOfWeek
}

@Composable
fun rememberWeekStart(): DayOfWeek {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val system = context.getSystemService(LocaleManager::class.java).systemLocales
        weekStart(locale, if (system.isEmpty) locale else system[0])
    }
}

private val WEEKDAYS = mapOf(
    "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
)
