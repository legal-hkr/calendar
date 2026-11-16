package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** Where a repetition ends (SPEC §10.5). */
sealed interface RuleEnd {
    data object Never : RuleEnd
    /** The last day an occurrence may start on. */
    data class Until(val date: LocalDate) : RuleEnd
    data class Count(val times: Int) : RuleEnd
}

/**
 * A repetition the editor can show and change (SPEC §10.5). What a rule doesn't say it takes from
 * the day the event starts on: the day of the month, the weekday of "the third Wednesday", the date
 * of a yearly one.
 *
 * @param interval Every how many days, weeks, months or years
 * @param weekdays The days of a weekly rule, never empty there; empty otherwise
 * @param nth A monthly rule on a weekday: which one of the month, 1–4 or -1 for the last; null = on the day of the month
 */
data class Rule(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val nth: Int? = null,
    val end: RuleEnd = RuleEnd.Never,
) {
    /** The same repetition without its end: what tells whether the pattern changed. */
    val pattern: Rule get() = copy(end = RuleEnd.Never)

    /**
     * The rule as the provider keeps it (RRULE, RFC 5545) for an event starting at [start] in [zone].
     * "Until" takes in the whole of its day; an all-day event names it as a date.
     */
    fun toRRule(start: LocalDateTime, zone: ZoneId, allDay: Boolean): String = buildList {
        add("FREQ=${frequency.name}")
        if (interval > 1) add("INTERVAL=$interval")
        when (frequency) {
            Frequency.WEEKLY -> add("BYDAY=" + weekdays.ifEmpty { setOf(start.dayOfWeek) }.sorted().joinToString(",") { code(it) })
            Frequency.MONTHLY -> add(if (nth != null) "BYDAY=$nth${code(start.dayOfWeek)}" else "BYMONTHDAY=${start.dayOfMonth}")
            else -> Unit
        }
        when (val end = end) {
            is RuleEnd.Until -> add("UNTIL=" + if (allDay) DATE.format(end.date) else UTC.format(LocalDateTime.of(end.date, LocalTime.of(23, 59, 59)).atZone(zone).withZoneSameInstant(ZoneOffset.UTC)))
            is RuleEnd.Count -> add("COUNT=${end.times}")
            RuleEnd.Never -> Unit
        }
    }.joinToString(";")

    /** The rule after the event's start was put from the day [from] on the day [to]: the weekday that was moved changes, "the third Wednesday" becomes what the new day is. */
    fun movedTo(from: LocalDate, to: LocalDate): Rule = when {
        from == to -> this
        frequency == Frequency.WEEKLY && from.dayOfWeek in weekdays -> copy(weekdays = weekdays - from.dayOfWeek + to.dayOfWeek)
        // A fifth weekday of a month is its last: no rule of the editor says "the fifth", which most months lack
        frequency == Frequency.MONTHLY && nth != null -> copy(nth = if ((nth == -1 && isLast(to)) || ordinal(to) > 4) -1 else ordinal(to))
        else -> this
    }

    /** Whether the day [date] is one the rule names, for an event whose pattern is the day [like]. */
    fun names(date: LocalDate, like: LocalDate): Boolean = when (frequency) {
        Frequency.DAILY -> true
        Frequency.WEEKLY -> date.dayOfWeek in weekdays
        Frequency.MONTHLY -> date == dayIn(YearMonth.from(date), like)
        Frequency.YEARLY -> date.month == like.month && date.dayOfMonth == like.dayOfMonth
    }

    /**
     * The first occurrence on or after [earliest] of the series in which [anchor] is an occurrence:
     * where a series starts once all of it takes on this rule (the owner, 16 Nov 2026). The steps
     * of an interval are counted back from [anchor], so that it stays an occurrence.
     */
    fun firstFrom(earliest: LocalDate, anchor: LocalDate): LocalDate {
        if (!earliest.isBefore(anchor)) return anchor
        val step = interval.coerceAtLeast(1).toLong()
        when (frequency) {
            Frequency.DAILY -> return anchor.minusDays(ChronoUnit.DAYS.between(earliest, anchor) / step * step)
            Frequency.WEEKLY -> {
                // Weeks start on Monday, as a rule without WKST counts them
                val days = weekdays.ifEmpty { setOf(anchor.dayOfWeek) }.sorted()
                val anchorWeek = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val earliestWeek = earliest.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var week = anchorWeek.minusWeeks(ChronoUnit.WEEKS.between(earliestWeek, anchorWeek) / step * step)
                while (week <= anchorWeek) {
                    days.map { week.plusDays(it.value - 1L) }.firstOrNull { !it.isBefore(earliest) }?.let { return it }
                    week = week.plusWeeks(step)
                }
            }
            Frequency.MONTHLY -> {
                val last = YearMonth.from(anchor)
                var month = last.minusMonths(ChronoUnit.MONTHS.between(YearMonth.from(earliest), last) / step * step)
                while (month <= last) {
                    dayIn(month, anchor)?.takeIf { !it.isBefore(earliest) }?.let { return it }
                    month = month.plusMonths(step)
                }
            }
            Frequency.YEARLY -> {
                var year = anchor.year - (anchor.year - earliest.year) / step.toInt() * step.toInt()
                while (year <= anchor.year) {
                    // February 29 exists only in some years
                    runCatching { LocalDate.of(year, anchor.month, anchor.dayOfMonth) }.getOrNull()?.takeIf { !it.isBefore(earliest) }?.let { return it }
                    year += step.toInt()
                }
            }
        }
        return anchor
    }

    /** The day of [month] a monthly rule names for an event like the day [like]; null when the month has no such day (the 31st). */
    private fun dayIn(month: YearMonth, like: LocalDate): LocalDate? = when (nth) {
        null -> if (like.dayOfMonth <= month.lengthOfMonth()) month.atDay(like.dayOfMonth) else null
        -1 -> month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(like.dayOfWeek))
        else -> month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(nth, like.dayOfWeek)).takeIf { YearMonth.from(it) == month }
    }

    companion object {
        private val CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        const val MAX_INTERVAL = 999
        const val MAX_COUNT = 999

        private fun code(day: DayOfWeek): String = CODES[day.value - 1]

        private fun day(code: String): DayOfWeek? = CODES.indexOf(code).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }

        /** Which weekday of its month [date] is: 1–5. */
        fun ordinal(date: LocalDate): Int = (date.dayOfMonth - 1) / 7 + 1

        /** Whether [date] is the last of its weekday in its month. */
        fun isLast(date: LocalDate): Boolean = date.plusWeeks(1).month != date.month

        /** The choices of the Repetition dialog for an event starting on [start] (SPEC §10.5): daily, weekly on its weekday, monthly on its day, yearly on its date. */
        fun presets(start: LocalDate): List<Rule> = listOf(Rule(Frequency.DAILY), Rule(Frequency.WEEKLY, weekdays = setOf(start.dayOfWeek)), Rule(Frequency.MONTHLY), Rule(Frequency.YEARLY))

        /**
         * The stored rule [rrule] of an event starting on [start], whose times are in [zone]; null
         * when it says more than the editor can show – then it is kept as it is (SPEC §10.5).
         */
        fun parse(rrule: String, start: LocalDate, zone: ZoneId): Rule? {
            val parts = HashMap<String, String>()
            for (part in rrule.removePrefix("RRULE:").split(';')) {
                val at = part.indexOf('=')
                if (at > 0) parts[part.substring(0, at).trim().uppercase()] = part.substring(at + 1).trim().uppercase()
            }
            val named = parts.remove("FREQ")
            val frequency = Frequency.entries.firstOrNull { it.name == named } ?: return null
            val interval = parts.remove("INTERVAL")?.let { it.toIntOrNull() ?: return null } ?: 1
            if (interval !in 1..MAX_INTERVAL) return null
            val count = parts.remove("COUNT")?.let { it.toIntOrNull() ?: return null }
            val until = parts.remove("UNTIL")?.let { until(it, zone) ?: return null }
            if (count != null && (until != null || count !in 1..MAX_COUNT)) return null
            val end = when {
                count != null -> RuleEnd.Count(count)
                until != null -> RuleEnd.Until(until)
                else -> RuleEnd.Never
            }
            // Weeks counted from another day than Monday fall differently once they are skipped
            val weekStart = parts.remove("WKST")
            val byDay = parts.remove("BYDAY")
            val byMonthDay = parts.remove("BYMONTHDAY")
            val byMonth = parts.remove("BYMONTH")
            val position = parts.remove("BYSETPOS")
            if (parts.isNotEmpty()) return null
            return when (frequency) {
                Frequency.DAILY -> if (byDay == null && byMonthDay == null && byMonth == null && position == null) Rule(frequency, interval, end = end) else null
                Frequency.WEEKLY -> {
                    if (byMonthDay != null || byMonth != null || position != null) return null
                    if (interval > 1 && weekStart != null && weekStart != "MO") return null
                    val days = if (byDay == null) setOf(start.dayOfWeek) else byDay.split(',').map { day(it.trim()) ?: return null }.toSet()
                    if (days.isEmpty()) null else Rule(frequency, interval, days, end = end)
                }
                Frequency.MONTHLY -> {
                    if (byMonth != null) return null
                    when {
                        byDay == null && position == null -> if (byMonthDay == null || byMonthDay == start.dayOfMonth.toString()) Rule(frequency, interval, end = end) else null
                        byDay != null && byMonthDay == null -> {
                            // "3WE", or "WE" with the position apart
                            val match = Regex("([+-]?\\d)?([A-Z]{2})").matchEntire(byDay) ?: return null
                            if (day(match.groupValues[2]) != start.dayOfWeek) return null
                            val nth = when {
                                match.groupValues[1].isNotEmpty() && position == null -> match.groupValues[1].toInt()
                                match.groupValues[1].isEmpty() && position != null -> position.toIntOrNull() ?: return null
                                else -> return null
                            }
                            val fits = if (nth == -1) isLast(start) else nth in 1..4 && nth == ordinal(start)
                            if (fits) Rule(frequency, interval, nth = nth, end = end) else null
                        }
                        else -> null
                    }
                }
                Frequency.YEARLY -> {
                    if (byDay != null || position != null) return null
                    val sameMonth = byMonth == null || byMonth == start.monthValue.toString()
                    val sameDay = byMonthDay == null || byMonthDay == start.dayOfMonth.toString()
                    if (sameMonth && sameDay) Rule(frequency, interval, end = end) else null
                }
            }
        }

        /** The day an UNTIL value falls on in [zone]: a date, a UTC time, or a time without a zone. */
        private fun until(value: String, zone: ZoneId): LocalDate? = runCatching {
            when {
                value.length == 8 -> LocalDate.parse(value, DATE)
                value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), LOCAL).atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate()
                else -> LocalDateTime.parse(value, LOCAL).toLocalDate()
            }
        }.getOrNull()
    }
}

/** How an event repeats, as the editor holds it. */
sealed interface Repetition {
    data object None : Repetition
    data class Simple(val rule: Rule) : Repetition
    /** A stored rule that says more than the editor can show: kept as it is unless it is replaced (SPEC §10.5). */
    data class Other(val rrule: String) : Repetition

    /** What becomes of it when the event's start is put from the day [from] on the day [to]. */
    fun movedTo(from: LocalDate, to: LocalDate): Repetition = if (this is Simple) Simple(rule.movedTo(from, to)) else this

    companion object {
        fun of(rrule: String?, start: LocalDate, zone: ZoneId): Repetition = when {
            rrule.isNullOrBlank() -> None
            else -> Rule.parse(rrule, start, zone)?.let { Simple(it) } ?: Other(rrule)
        }
    }
}

/** Reminders as the editor holds them (SPEC §10.6): minutes before the start; for an all-day event before the midnight its first day begins with, so "on the day at 9:00" is below zero. */
object ReminderTimes {
    const val MAX = 5
    const val HOUR = 60
    const val DAY = 1440
    const val WEEK = 10080
    /** When the reminders of an all-day event appear: 9:00. */
    const val MORNING = 9 * HOUR

    val timedPresets = listOf(0, 5, 15, 30, HOUR, DAY)
    /** On the day at 9:00, the day before at 18:00, 2 days before at 9:00, a week before at 9:00. */
    val allDayPresets = listOf(-MORNING, DAY - 18 * HOUR, 2 * DAY - MORNING, WEEK - MORNING)

    /** Until Settings › Events has them (stage 8): 15 minutes before a timed event, none for an all-day one. */
    fun defaults(allDay: Boolean): List<Int> = if (allDay) emptyList() else listOf(15)

    /** [minutes] added to [reminders]: each once, in rising order, at most [MAX]. */
    fun add(reminders: List<Int>, minutes: Int): List<Int> = (reminders + minutes).distinct().sorted().take(MAX)

    /** An all-day reminder as days before and the minute of that day: 2340 is 2 days before at 9:00. */
    fun allDayParts(minutes: Int): Pair<Int, Int> {
        val days = if (minutes <= 0) 0 else (minutes + DAY - 1) / DAY
        return days to (days * DAY - minutes).coerceIn(0, DAY - 1)
    }

    /** An all-day reminder [days] before at 9:00. */
    fun allDay(days: Int): Int = days * DAY - MORNING
}
