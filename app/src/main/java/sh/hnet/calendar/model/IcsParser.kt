package sh.hnet.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a file turned out to hold (SPEC §13.8). */
sealed interface IcsRead {
    /**
     * @param name The calendar's name, when the file gives one
     * @param cancels Whether the file as a whole takes its events back (METHOD:CANCEL)
     * @param events Sorted by their start; an occurrence changed on its own names its series ([IcsEvent.originalUid])
     */
    data class Events(val name: String?, val cancels: Boolean, val events: List<IcsEvent>) : IcsRead

    /** A calendar file without events: only tasks, journal entries or availability. */
    data object NoEvents : IcsRead

    /** Not an iCalendar file, or one too damaged to read. */
    data object Unreadable : IcsRead
}

/**
 * Reads iCalendar files (RFC 5545) for the import (SPEC §13): the events with their times,
 * repetition, what was taken out of it, reminders and states. Guests and organizers are left out
 * for now (the owner, 16 Nov 2026). A file is read leniently – what one event has wrong costs that
 * event, not the file.
 */
object IcsParser {
    private const val DAY = 86_400_000L
    /** The character that escapes the next one in a text value. */
    private val BACKSLASH = 92.toChar()
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val UNTIL = Regex("""UNTIL=([0-9TZ]+)""")

    /** A content line: its name, its parameters and its value. */
    private class Line(val name: String, val params: Map<String, String>, val value: String)

    /** A date or a time of the file: [date] for a day without a time; [zone] null for a time in UTC or in no zone at all ([utc] tells which). */
    private class Moment(val millis: Long, val date: Boolean, val zone: ZoneId?, val utc: Boolean)

    /** A VTIMEZONE as far as it is needed to find the zone it means: what the clock is ahead of UTC in standard and in daylight time, in seconds. */
    private class ZoneDefinition(var standard: Int? = null, var daylight: Int? = null)

    /**
     * @param phone The phone's zone, in which times without a zone are meant
     * @param now For telling zones apart by the offsets they have today
     */
    fun read(text: String, phone: ZoneId, now: Instant = Instant.now()): IcsRead {
        val lines = unfold(text).mapNotNull(::line)
        if (lines.none { it.name == "BEGIN" && it.value.equals("VCALENDAR", ignoreCase = true) }) return IcsRead.Unreadable
        var name: String? = null
        var cancels = false
        var others = 0
        val definitions = HashMap<String, ZoneDefinition>()
        val blocks = mutableListOf<List<Line>>()
        // Which component the lines are in, innermost last
        val inside = ArrayList<String>()
        var block: MutableList<Line>? = null
        var zone: ZoneDefinition? = null
        for (line in lines) {
            when (line.name) {
                "BEGIN" -> {
                    val component = line.value.uppercase()
                    inside += component
                    when (component) {
                        "VEVENT" -> if (block == null) block = mutableListOf()
                        "VTIMEZONE" -> zone = ZoneDefinition()
                        "VTODO", "VJOURNAL", "VFREEBUSY" -> others++
                    }
                    if (component != "VEVENT") block?.add(line)
                }
                "END" -> {
                    val component = line.value.uppercase()
                    if (inside.lastOrNull() == component) inside.removeAt(inside.lastIndex)
                    when (component) {
                        "VEVENT" -> if (inside.none { it == "VEVENT" }) {
                            block?.let { blocks += it }
                            block = null
                        }
                        "VTIMEZONE" -> zone = null
                        else -> block?.add(line)
                    }
                }
                else -> when {
                    block != null -> block.add(line)
                    zone != null -> when (line.name) {
                        "TZID" -> definitions[line.value] = zone
                        "TZOFFSETTO" -> offset(line.value)?.let { if (inside.lastOrNull() == "DAYLIGHT") zone.daylight = it else zone.standard = it }
                    }
                    inside.size == 1 -> when (line.name) {
                        "METHOD" -> cancels = line.value.equals("CANCEL", ignoreCase = true)
                        "X-WR-CALNAME" -> name = unescape(line.value).trim().takeIf { it.isNotEmpty() }
                    }
                }
            }
        }
        val zones = HashMap<String, ZoneId>()
        fun resolve(id: String): ZoneId = zones.getOrPut(id) { zone(id, definitions[id], phone, now) }
        val events = blocks.mapNotNull { runCatching { event(it, phone, ::resolve) }.getOrNull() }.sortedBy { it.start }
        return when {
            events.isNotEmpty() -> IcsRead.Events(name, cancels, if (cancels) events.map { it.copy(status = EventStatus.CANCELED) } else events)
            // Blocks that could not be read are a damaged file, not an empty one
            blocks.isEmpty() && others > 0 -> IcsRead.NoEvents
            blocks.isEmpty() -> IcsRead.NoEvents
            else -> IcsRead.Unreadable
        }
    }

    /** The file's lines with the continued ones joined: a line that starts with a space or a tab goes on the one before. */
    private fun unfold(text: String): List<String> {
        val result = ArrayList<StringBuilder>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && result.isNotEmpty()) result.last().append(raw, 1, raw.length) else result += StringBuilder(raw)
        }
        return result.map { it.toString() }.filter { it.isNotBlank() }
    }

    /** "NAME;PARAM=value;PARAM="quoted:value":value" taken apart; null for a line without a colon. */
    private fun line(raw: String): Line? {
        var quoted = false
        var colon = -1
        val breaks = ArrayList<Int>()
        for ((index, char) in raw.withIndex()) {
            if (char == '"') quoted = !quoted
            if (quoted) continue
            if (char == ';') breaks += index
            if (char == ':') {
                colon = index
                break
            }
        }
        if (colon <= 0) return null
        val head = raw.substring(0, colon)
        val cuts = breaks.filter { it < colon }
        val parts = (listOf(-1) + cuts + head.length).zipWithNext { from, to -> head.substring(from + 1, to) }
        val params = parts.drop(1).mapNotNull { part ->
            val at = part.indexOf('=')
            if (at > 0) part.substring(0, at).trim().uppercase() to part.substring(at + 1).trim().removeSurrounding("\"") else null
        }.toMap()
        // A group before the name ("item1.DTSTART") is not part of it
        return Line(parts.first().substringAfterLast('.').trim().uppercase(), params, raw.substring(colon + 1))
    }

    /** A text value as it was meant: "\n" a line break, any other escaped character itself. */
    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == BACKSLASH && index + 1 < value.length) {
                val next = value[index + 1]
                append(if (next == 'n' || next == 'N') '\n' else next)
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    /** "+0100", "-053000" in seconds. */
    private fun offset(value: String): Int? {
        val text = value.trim()
        if (text.length < 5 || (text[0] != '+' && text[0] != '-')) return null
        val hours = text.substring(1, 3).toIntOrNull() ?: return null
        val minutes = text.substring(3, 5).toIntOrNull() ?: return null
        val seconds = if (text.length >= 7) text.substring(5, 7).toIntOrNull() ?: 0 else 0
        return (if (text[0] == '-') -1 else 1) * (hours * 3600 + minutes * 60 + seconds)
    }

    /**
     * The zone a TZID means: the zone of that name; else the place at the end of a longer name
     * ("/freeassociation.sourceforge.net/Europe/Warsaw"); else Windows' name for it; else a zone
     * with the offsets its definition in the file gives – the phone's own when it fits, so the
     * times look as the user expects –; else the phone's zone.
     */
    private fun zone(id: String, definition: ZoneDefinition?, phone: ZoneId, now: Instant): ZoneId {
        fun known(name: String): ZoneId? = runCatching { ZoneId.of(name) }.getOrNull()
        known(id)?.let { return it }
        val parts = id.trim('/').split('/')
        for (count in listOf(3, 2)) if (parts.size >= count) known(parts.takeLast(count).joinToString("/"))?.let { return it }
        WINDOWS[id]?.let(::known)?.let { return it }
        val standard = definition?.standard ?: return phone
        val daylight = definition.daylight
        fun fits(zone: ZoneId): Boolean {
            val rules = zone.rules
            if (rules.getStandardOffset(now).totalSeconds != standard) return false
            val changes = rules.transitionRules
            return if (daylight == null || daylight == standard) changes.isEmpty() else changes.any { it.offsetAfter.totalSeconds == daylight }
        }
        if (fits(phone)) return phone
        (COMMON.asSequence() + ZoneId.getAvailableZoneIds().asSequence().sorted()).mapNotNull(::known).firstOrNull(::fits)?.let { return it }
        return runCatching { ZoneOffset.ofTotalSeconds(standard) as ZoneId }.getOrDefault(phone)
    }

    private fun moment(line: Line, phone: ZoneId, resolve: (String) -> ZoneId): Moment? = moment(line.value.trim(), line.params, phone, resolve)

    private fun moment(value: String, params: Map<String, String>, phone: ZoneId, resolve: (String) -> ZoneId): Moment? = runCatching {
        when {
            value.length == 8 -> Moment(LocalDate.parse(value, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, null, false)
            value.endsWith("Z", ignoreCase = true) -> Moment(LocalDateTime.parse(value.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli(), false, null, true)
            else -> {
                val zone = params["TZID"]?.let(resolve)
                Moment(LocalDateTime.parse(value, LOCAL).atZone(zone ?: phone).toInstant().toEpochMilli(), false, zone, false)
            }
        }
    }.getOrNull()

    /** A duration with its sign, in milliseconds: "-PT15M" is a quarter of an hour back. */
    private fun signed(duration: String): Long? {
        val text = duration.trim()
        val back = text.startsWith("-")
        return IcsCalendar.millis(text.removePrefix("-").removePrefix("+"))?.let { if (back) -it else it }
    }

    private fun event(lines: List<Line>, phone: ZoneId, resolve: (String) -> ZoneId): IcsEvent? {
        // The event's own lines, and those of each of its reminders
        val own = ArrayList<Line>()
        val alarms = ArrayList<List<Line>>()
        var alarm: MutableList<Line>? = null
        for (line in lines) {
            when {
                line.name == "BEGIN" && line.value.equals("VALARM", ignoreCase = true) -> alarm = mutableListOf()
                line.name == "END" && line.value.equals("VALARM", ignoreCase = true) -> {
                    alarm?.let { alarms += it }
                    alarm = null
                }
                line.name == "BEGIN" || line.name == "END" -> Unit
                alarm != null -> alarm.add(line)
                else -> own += line
            }
        }
        fun first(name: String) = own.firstOrNull { it.name == name }
        fun text(name: String) = first(name)?.let { unescape(it.value).trim() }.orEmpty()

        val begins = first("DTSTART")?.let { moment(it, phone, resolve) } ?: return null
        val allDay = begins.date
        val start = begins.millis
        var end = first("DTEND")?.let { moment(it, phone, resolve) }?.millis ?: first("DURATION")?.let { signed(it.value) }?.let { start + it } ?: (start + if (allDay) DAY else 0L)
        // An all-day event lasts a day at least; nothing ends before it starts
        end = maxOf(end, start + if (allDay) DAY else 0L)
        val zone = begins.zone ?: if (begins.utc) ZoneOffset.UTC else phone
        val rule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { until(it, allDay, zone) }
        val exdates = own.filter { it.name == "EXDATE" }.flatMap { line -> line.value.split(',').mapNotNull { moment(it.trim(), line.params, phone, resolve)?.millis } }
        val original = first("RECURRENCE-ID")?.let { moment(it, phone, resolve) }?.millis
        val uid = text("UID")
        val reminders = alarms.mapNotNull { lines ->
            val trigger = lines.firstOrNull { it.name == "TRIGGER" } ?: return@mapNotNull null
            val before = if (trigger.params["VALUE"].equals("DATE-TIME", ignoreCase = true)) {
                moment(trigger.value.trim(), trigger.params, phone, resolve)?.let { start - it.millis }
            } else {
                // Counted from the start, or from the end when the file says so; before it is a negative trigger
                signed(trigger.value)?.let { offset -> if (trigger.params["RELATED"].equals("END", ignoreCase = true)) start - (end + offset) else -offset }
            } ?: return@mapNotNull null
            Math.floorDiv(before, 60_000L).toInt()
        }.distinct().sorted()
        return IcsEvent(
            uid = uid,
            title = text("SUMMARY"),
            location = text("LOCATION"),
            description = text("DESCRIPTION"),
            start = start,
            // A repeating event is kept by its length
            end = if (rule == null) end else null,
            duration = if (rule == null) null else if (allDay) "P${(end - start) / DAY}D" else "PT${(end - start) / 1000}S",
            allDay = allDay,
            // A time in UTC that doesn't repeat is shown in the phone's zone like any other
            zone = if (allDay) "UTC" else if (begins.zone == null && begins.utc && rule == null) null else zone.id,
            rrule = rule,
            exdates = exdates,
            status = when (text("STATUS").uppercase()) {
                "CANCELLED" -> EventStatus.CANCELED
                "TENTATIVE" -> EventStatus.TENTATIVE
                else -> EventStatus.CONFIRMED
            },
            busy = !text("TRANSP").equals("TRANSPARENT", ignoreCase = true),
            private = text("CLASS").uppercase().let { it == "PRIVATE" || it == "CONFIDENTIAL" },
            reminders = reminders,
            originalUid = if (original != null && uid.isNotEmpty()) uid else null,
            originalStart = if (uid.isNotEmpty()) original else null,
        )
    }

    /** The rule with its end as the provider wants it: a date for an all-day event, else a time in UTC, the whole of the day a bare date names. */
    private fun until(rule: String, allDay: Boolean, zone: ZoneId): String = UNTIL.replace(rule.removePrefix("RRULE:")) { match ->
        val value = match.groupValues[1]
        val fixed = runCatching {
            when {
                allDay -> value.take(8)
                value.endsWith("Z") -> value
                value.length == 8 -> UTC.format(LocalDateTime.of(LocalDate.parse(value, DATE), LocalTime.of(23, 59, 59)).atZone(zone).withZoneSameInstant(ZoneOffset.UTC))
                else -> UTC.format(LocalDateTime.parse(value, LOCAL).atZone(zone).withZoneSameInstant(ZoneOffset.UTC))
            }
        }.getOrDefault(value)
        "UNTIL=$fixed"
    }

    /** The zones tried first for a definition without a known name: where a match is most likely meant. */
    private val COMMON = listOf(
        "Europe/Warsaw", "Europe/London", "Europe/Berlin", "Europe/Paris", "Europe/Kyiv", "Europe/Moscow", "America/New_York", "America/Chicago",
        "America/Denver", "America/Los_Angeles", "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney",
    )

    /** Windows' names for the zones a file from Outlook or Exchange most often has. */
    private val WINDOWS = mapOf(
        "Central European Standard Time" to "Europe/Warsaw", "W. Europe Standard Time" to "Europe/Berlin", "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest", "GMT Standard Time" to "Europe/London", "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "E. Europe Standard Time" to "Europe/Chisinau", "FLE Standard Time" to "Europe/Kyiv", "GTB Standard Time" to "Europe/Bucharest",
        "Russian Standard Time" to "Europe/Moscow", "Turkey Standard Time" to "Europe/Istanbul", "Israel Standard Time" to "Asia/Jerusalem",
        "Eastern Standard Time" to "America/New_York", "Central Standard Time" to "America/Chicago", "Mountain Standard Time" to "America/Denver",
        "Pacific Standard Time" to "America/Los_Angeles", "India Standard Time" to "Asia/Kolkata", "China Standard Time" to "Asia/Shanghai",
        "Tokyo Standard Time" to "Asia/Tokyo", "AUS Eastern Standard Time" to "Australia/Sydney", "UTC" to "UTC",
    )
}
