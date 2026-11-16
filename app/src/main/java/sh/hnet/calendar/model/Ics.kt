package sh.hnet.calendar.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Writes an event as an iCalendar file (RFC 5545), for sharing; reading files comes with the import stage. */
object Ics {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    /**
     * One occurrence as a file of its own, without repetition: times in UTC, an all-day event by
     * its dates. [stamp] is when the file is written.
     */
    fun event(uid: String, stamp: Instant, event: EventDetails): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//hnet.sh//Calendar//EN", "BEGIN:VEVENT")
        lines += "UID:" + escape(uid)
        lines += "DTSTAMP:" + STAMP.format(stamp)
        if (event.allDay) {
            lines += "DTSTART;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(event.begin))
            lines += "DTEND;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(maxOf(event.end, event.begin)))
        } else {
            lines += "DTSTART:" + STAMP.format(Instant.ofEpochMilli(event.begin))
            lines += "DTEND:" + STAMP.format(Instant.ofEpochMilli(maxOf(event.end, event.begin)))
        }
        if (event.title.isNotEmpty()) lines += "SUMMARY:" + escape(event.title)
        if (event.location.isNotEmpty()) lines += "LOCATION:" + escape(event.location)
        if (event.description.isNotEmpty()) lines += "DESCRIPTION:" + escape(event.description)
        if (event.status == EventStatus.CANCELED) lines += "STATUS:CANCELLED"
        if (!event.busy) lines += "TRANSP:TRANSPARENT"
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    /** Text as a property's value: backslash, semicolon and comma escaped, a line break as "\n". */
    fun escape(text: String): String = buildString {
        for (char in text.replace("\r\n", "\n")) {
            when (char) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n', '\r' -> append("\\n")
                else -> append(char)
            }
        }
    }

    /** A line of more than 75 octets goes on in the next, which starts with a space; never inside a character. */
    fun fold(line: String): String {
        val result = StringBuilder()
        var octets = 0
        var index = 0
        while (index < line.length) {
            val point = line.codePointAt(index)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (octets + size > 75) {
                result.append("\r\n ")
                octets = 1
            }
            result.appendCodePoint(point)
            octets += size
            index += Character.charCount(point)
        }
        return result.toString()
    }
}
