package sh.hnet.calendar.ui.event

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import sh.hnet.calendar.R
import sh.hnet.calendar.model.Frequency
import sh.hnet.calendar.model.ReminderTimes
import sh.hnet.calendar.model.Repetition
import sh.hnet.calendar.model.Rule
import sh.hnet.calendar.model.RuleEnd

/**
 * Repetition rules and reminders in words (SPEC §10.5, §10.6), built from parts as the revised
 * string list has them: weekdays after a colon in Polish, quantity strings for the numbers.
 */
class EventWords(private val context: Context, private val locale: Locale, hours24: Boolean, private val weekStart: DayOfWeek) {
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)
    private val dateWithYear: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMdy"), locale)
    private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdy"), locale)
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (hours24) "Hm" else "hm"), locale)
    private val resources get() = context.resources

    /** The days of a week in the order the week has here. */
    val week: List<DayOfWeek> = (0L..6L).map { weekStart.plus(it) }

    /** "Mar 31, 2027", for the pill of a date. */
    fun shortDate(date: LocalDate): String = shortDate.format(date)

    /** [rule] without its end: "Every 2 weeks on Monday and Wednesday", for an event starting on [start]. */
    fun rule(rule: Rule, start: LocalDate): String {
        val every = rule.interval
        return when (rule.frequency) {
            Frequency.DAILY -> if (every == 1) context.getString(R.string.rule_daily) else resources.getQuantityString(R.plurals.rule_every_n_days, every, every)
            Frequency.WEEKLY -> {
                val days = list(week.filter { it in rule.weekdays.ifEmpty { setOf(start.dayOfWeek) } }.map { it.getDisplayName(TextStyle.FULL, locale) })
                if (every == 1) context.getString(R.string.rule_weekly, days) else resources.getQuantityString(R.plurals.rule_every_n_weeks, every, every, days)
            }
            Frequency.MONTHLY -> {
                val nth = rule.nth
                when {
                    nth == null && every == 1 -> context.getString(R.string.rule_monthly_day, start.dayOfMonth)
                    nth == null -> resources.getQuantityString(R.plurals.rule_every_n_months_day, every, every, start.dayOfMonth)
                    every == 1 -> context.getString(R.string.rule_monthly_nth, ordinal(nth, start.dayOfWeek), weekdayOn(start.dayOfWeek))
                    else -> resources.getQuantityString(R.plurals.rule_every_n_months_nth, every, every, ordinal(nth, start.dayOfWeek), weekdayOn(start.dayOfWeek))
                }
            }
            Frequency.YEARLY -> if (every == 1) context.getString(R.string.rule_yearly, date.format(start)) else resources.getQuantityString(R.plurals.rule_every_n_years, every, every, date.format(start))
        }
    }

    /** The end of a rule: "until March 31, 2027" or "10 times"; null for one that never ends. */
    fun end(end: RuleEnd, capitalized: Boolean): String? = when (end) {
        RuleEnd.Never -> null
        is RuleEnd.Until -> context.getString(if (capitalized) R.string.rule_until_capitalized else R.string.rule_until, dateWithYear.format(end.date))
        is RuleEnd.Count -> resources.getQuantityString(R.plurals.rule_count, end.times, end.times)
    }

    /** The whole rule in one line: "Every 2 weeks on Monday and Wednesday, until March 31, 2027". */
    fun ruleWithEnd(rule: Rule, start: LocalDate): String = end(rule.end, capitalized = false)?.let { context.getString(R.string.rule_with_end, rule(rule, start), it) } ?: rule(rule, start)

    /** What the Repetition row says, and the line under it. */
    fun repetition(repetition: Repetition, start: LocalDate): Pair<String, String?> = when (repetition) {
        Repetition.None -> context.getString(R.string.label_repeat_none) to null
        is Repetition.Simple -> rule(repetition.rule, start) to end(repetition.rule.end, capitalized = true)
        is Repetition.Other -> context.getString(R.string.label_repeat_other) to null
    }

    /** "On day 18" – the choice of a monthly rule by the day of the month. */
    fun onDay(start: LocalDate): String = context.getString(R.string.label_on_day_of_month, start.dayOfMonth)

    /** "On the third Wednesday" – the choice of a monthly rule by the weekday. */
    fun onNth(nth: Int, start: LocalDate): String = context.getString(R.string.label_on_nth_weekday, ordinal(nth, start.dayOfWeek), weekdayOn(start.dayOfWeek))

    /** A weekday's letter for its toggle, and its name for a screen reader. */
    fun weekdayLetter(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)

    fun weekdayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)

    /** "5 minutes before"; for an all-day event "The day before at 18:00". */
    fun reminder(minutes: Int, allDay: Boolean): String {
        if (allDay) {
            val (days, minute) = ReminderTimes.allDayParts(minutes)
            val at = time.format(LocalTime.of(minute / 60, minute % 60))
            return when (days) {
                0 -> context.getString(R.string.label_reminder_on_day_at, at)
                1 -> context.getString(R.string.label_reminder_day_before_at, at)
                7 -> context.getString(R.string.label_reminder_week_before_at, at)
                else -> resources.getQuantityString(R.plurals.label_reminder_days_before_at, days, days, at)
            }
        }
        // A reminder after the start, which another app may have set, is told as one at the start
        val before = minutes.coerceAtLeast(0)
        return when {
            before == 0 -> context.getString(R.string.label_reminder_at_start)
            before % ReminderTimes.WEEK == 0 -> quantity(R.plurals.label_reminder_weeks_before, before / ReminderTimes.WEEK)
            before % ReminderTimes.DAY == 0 -> quantity(R.plurals.label_reminder_days_before, before / ReminderTimes.DAY)
            before % ReminderTimes.HOUR == 0 -> quantity(R.plurals.label_reminder_hours_before, before / ReminderTimes.HOUR)
            else -> quantity(R.plurals.label_reminder_minutes_before, before)
        }
    }

    /** A unit after its number: "day" for 1, "days" for 5. */
    fun unit(plurals: Int, count: Int): String = resources.getQuantityString(plurals, count)

    private fun quantity(plurals: Int, count: Int): String = resources.getQuantityString(plurals, count, count)

    /** "Monday", "Monday and Wednesday", "Monday, Wednesday and Friday". */
    private fun list(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> context.getString(R.string.rule_list_and, names.dropLast(1).reduce { all, name -> context.getString(R.string.rule_list_comma, all, name) }, names.last())
    }

    /** "third" before a weekday; in Polish środa, sobota and niedziela are feminine and take "trzecią". */
    private fun ordinal(nth: Int, day: DayOfWeek): String {
        val feminine = day == DayOfWeek.WEDNESDAY || day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        return context.getString(
            when (nth) {
                1 -> if (feminine) R.string.label_ordinal_first_feminine else R.string.label_ordinal_first
                2 -> if (feminine) R.string.label_ordinal_second_feminine else R.string.label_ordinal_second
                3 -> if (feminine) R.string.label_ordinal_third_feminine else R.string.label_ordinal_third
                4 -> if (feminine) R.string.label_ordinal_fourth_feminine else R.string.label_ordinal_fourth
                else -> if (feminine) R.string.label_ordinal_last_feminine else R.string.label_ordinal_last
            },
        )
    }

    /** A weekday as it stands after "on the third": the platform has no accusative ("w trzecią środę"), hence strings. */
    private fun weekdayOn(day: DayOfWeek): String = context.getString(
        when (day) {
            DayOfWeek.MONDAY -> R.string.label_on_monday
            DayOfWeek.TUESDAY -> R.string.label_on_tuesday
            DayOfWeek.WEDNESDAY -> R.string.label_on_wednesday
            DayOfWeek.THURSDAY -> R.string.label_on_thursday
            DayOfWeek.FRIDAY -> R.string.label_on_friday
            DayOfWeek.SATURDAY -> R.string.label_on_saturday
            DayOfWeek.SUNDAY -> R.string.label_on_sunday
        },
    )
}

@Composable
fun rememberEventWords(): EventWords {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val hours24 = DateFormat.is24HourFormat(context)
    val weekStart = sh.hnet.calendar.ui.calendar.rememberWeekStart()
    return remember(context, locale, hours24, weekStart) { EventWords(context, locale, hours24, weekStart) }
}
