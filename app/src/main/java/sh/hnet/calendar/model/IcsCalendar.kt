package sh.hnet.calendar.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule

/**
 * An event as a calendar's export writes it (RFC 5545): the stored row, not one occurrence.
 *
 * @param end The end of an event that doesn't repeat; a repeating one has [duration] instead
 * @param duration The provider's duration ("PT1H", "P1D")
 * @param zone The zone the event is kept in; null or unknown is written in UTC
 * @param exdates The starts of the occurrences taken out of the series
 * @param originalUid For an occurrence changed on its own: the series it belongs to, with [originalStart] the start it would have had
 * @param reminders Minutes before the start; below zero after it
 */
data class IcsEvent(
    val uid: String,
    val title: String,
    val location: String,
    val description: String,
    val start: Long,
    val end: Long?,
    val duration: String?,
    val allDay: Boolean,
    val zone: String?,
    val rrule: String?,
    val exdates: List<Long> = emptyList(),
    val status: EventStatus = EventStatus.CONFIRMED,
    val busy: Boolean = true,
    val private: Boolean = false,
    val reminders: List<Int> = emptyList(),
    val originalUid: String? = null,
    val originalStart: Long? = null,
)

/**
 * A whole calendar as an iCalendar file (SPEC §12.3): the only copy of a local calendar's events,
 * so nothing of an event is left out – repetition with what was taken out of it, reminders, and
 * for repeating events the time zone with its definition, so that they keep their hour across the
 * changes of the clock wherever the file is read.
 */
object IcsCalendar {
    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private const val DAY = 86_400_000L

    fun write(name: String, stamp: Instant, events: List<IcsEvent>): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//hnet.sh//Calendar//EN", "CALSCALE:GREGORIAN", "X-WR-CALNAME:" + Ics.escape(name))
        // A repeating event with times is written in its own zone, which the file then has to define
        val zones = events.filter { it.rrule != null && !it.allDay }.mapNotNull { zoneOf(it) }.distinct().sortedBy { it.id }
        for (zone in zones) lines += timeZone(zone)
        for (event in events) lines += event(event, stamp)
        lines += "END:VCALENDAR"
        return lines.joinToString("") { Ics.fold(it) + "\r\n" }
    }

    private fun zoneOf(event: IcsEvent): ZoneId? = event.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() }?.takeIf { it != ZoneOffset.UTC && it.id != "UTC" }

    private fun event(event: IcsEvent, stamp: Instant): List<String> {
        val lines = mutableListOf("BEGIN:VEVENT", "UID:" + Ics.escape(event.originalUid ?: event.uid), "DTSTAMP:" + UTC.format(stamp))
        val zone = if (event.rrule != null) zoneOf(event) else null
        fun moment(name: String, millis: Long): String = when {
            event.allDay -> "$name;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(millis))
            zone != null -> "$name;TZID=${zone.id}:" + LOCAL.format(Instant.ofEpochMilli(millis).atZone(zone))
            else -> "$name:" + UTC.format(Instant.ofEpochMilli(millis))
        }
        // A changed occurrence names the start it would have had; an all-day series is counted in dates
        if (event.originalUid != null && event.originalStart != null) {
            lines += if (event.allDay) "RECURRENCE-ID;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(event.originalStart)) else "RECURRENCE-ID:" + UTC.format(Instant.ofEpochMilli(event.originalStart))
        }
        lines += moment("DTSTART", event.start)
        // The end: as stored, or the start and the length of a repeating event; an all-day event lasts a day at least
        val length = event.duration?.let { millis(it) }
        val end = maxOf(event.end ?: (event.start + (length ?: 0L)), event.start + if (event.allDay) DAY else 0L)
        lines += moment("DTEND", end)
        if (event.rrule != null) {
            lines += "RRULE:" + event.rrule.removePrefix("RRULE:")
            if (event.exdates.isNotEmpty()) {
                val taken = event.exdates.distinct().sorted()
                lines += when {
                    event.allDay -> "EXDATE;VALUE=DATE:" + taken.joinToString(",") { DATE.format(Instant.ofEpochMilli(it)) }
                    zone != null -> "EXDATE;TZID=${zone.id}:" + taken.joinToString(",") { LOCAL.format(Instant.ofEpochMilli(it).atZone(zone)) }
                    else -> "EXDATE:" + taken.joinToString(",") { UTC.format(Instant.ofEpochMilli(it)) }
                }
            }
        }
        if (event.title.isNotEmpty()) lines += "SUMMARY:" + Ics.escape(event.title)
        if (event.location.isNotEmpty()) lines += "LOCATION:" + Ics.escape(event.location)
        if (event.description.isNotEmpty()) lines += "DESCRIPTION:" + Ics.escape(event.description)
        when (event.status) {
            EventStatus.CANCELED -> lines += "STATUS:CANCELLED"
            EventStatus.TENTATIVE -> lines += "STATUS:TENTATIVE"
            EventStatus.CONFIRMED -> Unit
        }
        if (!event.busy) lines += "TRANSP:TRANSPARENT"
        if (event.private) lines += "CLASS:PRIVATE"
        for (minutes in event.reminders.distinct().sorted()) {
            // Before the start the trigger is negative; "on the day at 9:00" of an all-day event is after it
            val trigger = if (minutes >= 0) "-PT${minutes}M" else "PT${-minutes}M"
            lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + Ics.escape(event.title.ifEmpty { "Reminder" }), "TRIGGER:$trigger", "END:VALARM")
        }
        lines += "END:VEVENT"
        return lines
    }

    private val LENGTH = Regex("[+]?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

    /** A stored duration in milliseconds: "PT1H30M", "P1D", "P2W", and the provider's own "P3600S" without its "T"; null for anything else. */
    fun millis(duration: String): Long? {
        val parts = LENGTH.matchEntire(duration.trim().uppercase())?.groupValues ?: return null
        if (parts.drop(1).all { it.isEmpty() }) return null
        fun part(index: Int) = parts[index].toLongOrNull() ?: 0L
        return (((part(1) * 7 + part(2)) * 24 + part(3)) * 60 + part(4)) * 60_000L + part(5) * 1000L
    }

    /**
     * The definition of [zone] (VTIMEZONE) as it stands today: standard time, and for a zone that
     * changes its clock the two yearly changes. Earlier rules of the zone are left out – a file's
     * reader that knows the zone by its name uses its own data anyway.
     */
    fun timeZone(zone: ZoneId): List<String> {
        val rules = zone.rules
        val lines = mutableListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
        val yearly = rules.transitionRules
        if (yearly.isEmpty()) {
            val offset = offset(rules.getOffset(Instant.now()).totalSeconds)
            lines += listOf("BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:STANDARD")
        } else {
            for (rule in yearly) {
                val first = rule.createTransition(1970)
                val part = if (rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
                lines += listOf(
                    "BEGIN:$part", "DTSTART:" + LOCAL.format(first.dateTimeBefore), "TZOFFSETFROM:" + offset(rule.offsetBefore.totalSeconds),
                    "TZOFFSETTO:" + offset(rule.offsetAfter.totalSeconds), "RRULE:" + yearly(rule, first.dateTimeBefore), "END:$part",
                )
            }
        }
        lines += "END:VTIMEZONE"
        return lines
    }

    /** "+0100", "-0530". */
    private fun offset(seconds: Int): String {
        val minutes = Math.abs(seconds) / 60
        return (if (seconds < 0) "-" else "+") + "%02d%02d".format(minutes / 60, minutes % 60)
    }

    /** A yearly change of the clock as a rule: "the last Sunday of March", "the second Sunday", or the weekday within the seven days from a date. */
    private fun yearly(rule: ZoneOffsetTransitionRule, wall: LocalDateTime): String {
        // The rule counts in its own kind of time; the day the wall clock shows may differ, so the weekday is taken from there
        val day: DayOfWeek? = rule.dayOfWeek?.let { wall.dayOfWeek }
        val code = day?.let { listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[it.value - 1] }
        val month = wall.monthValue
        val indicator = rule.dayOfMonthIndicator
        return when {
            code == null -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=${wall.dayOfMonth}"
            // "The last Sunday" is stored as the Sunday on or after the 25th of a month of 31 days
            indicator == -1 || indicator == wall.month.maxLength() - 6 -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=-1$code"
            indicator > 0 && (indicator - 1) % 7 == 0 -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=${(indicator - 1) / 7 + 1}$code"
            indicator > 0 -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=" + (indicator..minOf(indicator + 6, 31)).joinToString(",") + ";BYDAY=$code"
            // "The weekday on or before a day counted from the month's end": the seven days up to it
            else -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=" + ((indicator - 6)..indicator).joinToString(",") + ";BYDAY=$code"
        }
    }
}

/** Getting a file's events ready for the calendar (SPEC §13; the logic agreed with the owner, 16 Nov 2026). */
object IcsImport {
    /**
     * The events as they are written to the provider. An occurrence of a series that the file
     * changed on its own becomes an event of its own, and the series leaves that occurrence out –
     * as "only this" does for a series the account's app has not sent on yet, which every imported
     * one is at first. A canceled occurrence is only left out. An occurrence whose series the file
     * doesn't have stands alone.
     */
    fun prepare(events: List<IcsEvent>): List<IcsEvent> {
        val series = events.filter { it.originalUid == null && it.rrule != null && it.uid.isNotEmpty() }.associateBy { it.uid }
        val taken = HashMap<String, MutableList<Long>>()
        val result = ArrayList<IcsEvent>()
        for (event in events) {
            val original = event.originalUid
            if (original == null) continue
            val known = series.containsKey(original)
            if (known && event.originalStart != null) taken.getOrPut(original) { mutableListOf() } += event.originalStart
            // The series' id with the occurrence's start: two events may not share an id, and the same file read again has to give the same one
            if (!(known && event.status == EventStatus.CANCELED)) result += event.copy(uid = original + "/" + (event.originalStart ?: event.start), originalUid = null, originalStart = null)
        }
        for (event in events) {
            if (event.originalUid != null) continue
            result += taken[event.uid]?.takeIf { event.rrule != null }?.let { event.copy(exdates = (event.exdates + it).distinct().sorted()) } ?: event
        }
        return result.sortedBy { it.start }
    }

    /** What of an event a file can change (SPEC §13.5). */
    enum class Field { TITLE, TIME, REPETITION, PLACE, DESCRIPTION, STATE }

    /** When the first occurrence ends. */
    fun firstEnd(event: IcsEvent): Long = event.end ?: (event.start + (event.duration?.let(IcsCalendar::millis) ?: 0L))

    /**
     * In what the [file]'s version of an event differs from the [saved] one; empty when the file
     * brings nothing new. Reminders are not compared – an update leaves them –, nor the zone a time
     * is written in, as long as it is the same moment.
     */
    fun changes(file: IcsEvent, saved: IcsEvent): Set<Field> = buildSet {
        if (file.title != saved.title) add(Field.TITLE)
        if (file.allDay != saved.allDay || file.start != saved.start || firstEnd(file) != firstEnd(saved)) add(Field.TIME)
        if (rule(file.rrule) != rule(saved.rrule) || (file.rrule != null && file.exdates.toSet() != saved.exdates.toSet())) add(Field.REPETITION)
        if (file.location != saved.location) add(Field.PLACE)
        if (file.description != saved.description) add(Field.DESCRIPTION)
        if (file.status != saved.status || file.busy != saved.busy || file.private != saved.private) add(Field.STATE)
    }

    /** A rule's parts in one order and one case, so the same rule written differently compares equal. */
    private fun rule(rrule: String?): Set<String>? = rrule?.removePrefix("RRULE:")?.split(';')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet()
}
