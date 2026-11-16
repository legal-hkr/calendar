package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What saving and deleting need of a repetition rule (RRULE, RFC 5545) without understanding all
 * of it: where it ends, how many times it still repeats, and the day it names. The editor of rules
 * comes with its stage.
 */
object Recurrence {
    private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun parts(rule: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in rule.removePrefix("RRULE:").split(';')) {
            val at = part.indexOf('=')
            if (at > 0) result[part.substring(0, at).trim().uppercase()] = part.substring(at + 1).trim()
        }
        return result
    }

    private fun join(parts: Map<String, String>): String = parts.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** [rule] ending just before the occurrence that starts at [millis]: "this and following" leaves the earlier ones to the old series. */
    fun endingBefore(rule: String, millis: Long, allDay: Boolean = false): String {
        val parts = parts(rule)
        parts.remove("COUNT")
        // An all-day series is counted in dates: its end is the day before, not a moment of UTC that is another day elsewhere
        parts["UNTIL"] = if (allDay) DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).minusDays(1)) else UNTIL.format(Instant.ofEpochMilli(millis - 1000))
        return join(parts)
    }

    /** [rule] for the series that goes on after [passed] occurrences stayed with the old one: a count is lowered by them, anything else stands. */
    fun continuing(rule: String, passed: Int): String {
        val parts = parts(rule)
        val count = parts["COUNT"]?.toIntOrNull() ?: return join(parts)
        parts["COUNT"] = (count - passed).coerceAtLeast(1).toString()
        return join(parts)
    }

    /**
     * [rule] after the series was moved from the day [from] to the day [to]: a weekly rule gets the
     * new weekday in place of the old one, a rule that names just that day of the month, that
     * weekday of the month or that date follows the move; a richer one stands as it is.
     */
    fun moved(rule: String, from: LocalDate, to: LocalDate): String {
        if (from == to) return rule
        val parts = parts(rule)
        val day = parts["BYDAY"]
        val monthDay = parts["BYMONTHDAY"]
        val month = parts["BYMONTH"]
        when (parts["FREQ"]?.uppercase()) {
            "WEEKLY" -> if (day != null) {
                // Of several weekdays only the one that was moved changes; the others stand
                val days = day.uppercase().split(',').map { it.trim() }
                val old = code(from.dayOfWeek)
                val new = code(to.dayOfWeek)
                if (old != new && old in days) parts["BYDAY"] = days.map { if (it == old) new else it }.distinct().sortedBy { DAYS.indexOf(it) }.joinToString(",")
            }
            "MONTHLY" -> when {
                day == null && monthDay == from.dayOfMonth.toString() -> parts["BYMONTHDAY"] = to.dayOfMonth.toString()
                // "The third Wednesday" becomes what the new day is, as [Rule.movedTo] has it – the two must agree, the form shows the one and this is saved
                day != null && monthDay == null && parts["BYSETPOS"] == null -> {
                    val last = day.uppercase() == "-1" + code(from.dayOfWeek)
                    if (last || day.uppercase() == Rule.ordinal(from).toString() + code(from.dayOfWeek)) {
                        parts["BYDAY"] = (if ((last && Rule.isLast(to)) || Rule.ordinal(to) > 4) "-1" else Rule.ordinal(to).toString()) + code(to.dayOfWeek)
                    }
                }
            }
            "YEARLY" -> if (day == null) {
                if (monthDay == from.dayOfMonth.toString()) parts["BYMONTHDAY"] = to.dayOfMonth.toString()
                if (month == from.monthValue.toString()) parts["BYMONTH"] = to.monthValue.toString()
            }
        }
        return join(parts)
    }

    /** Whether [rule] repeats on several days of the week, so that a move of one of them leaves the others where they are. */
    fun severalWeekdays(rule: String): Boolean {
        val parts = parts(rule)
        return parts["FREQ"]?.uppercase() == "WEEKLY" && (parts["BYDAY"]?.count { it == ',' } ?: 0) > 0
    }

    /**
     * The starts of the occurrences an EXDATE value takes out of a series: a list of UTC times
     * ("20261120T070000Z"), of dates for an all-day event ("20261120"), or of local times after a
     * zone ("Europe/Warsaw;20261120T080000"). What can't be read is left out.
     */
    fun excluded(exdate: String?): List<Long> {
        if (exdate.isNullOrBlank()) return emptyList()
        val at = exdate.indexOf(';')
        val zone = if (at > 0) runCatching { ZoneId.of(exdate.substring(0, at).removePrefix("TZID=")) }.getOrNull() ?: ZoneOffset.UTC else ZoneOffset.UTC
        return exdate.substring(at + 1).split(',').mapNotNull { raw ->
            val text = raw.trim()
            runCatching {
                when {
                    text.length == 8 -> LocalDate.parse(text, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    text.endsWith("Z") -> LocalDateTime.parse(text.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
                    else -> LocalDateTime.parse(text, LOCAL).atZone(zone).toInstant().toEpochMilli()
                }
            }.getOrNull()
        }
    }

    /** The EXDATE value for [starts]: dates for an all-day event, else UTC times; null for none. */
    fun exdate(starts: List<Long>, allDay: Boolean): String? = starts.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(",") {
        if (allDay) DATE.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) else UNTIL.format(Instant.ofEpochMilli(it))
    }

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun code(day: DayOfWeek): String = DAYS[day.value - 1]
}
