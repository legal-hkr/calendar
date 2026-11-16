package sh.hnet.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTest {
    private val stamp = Instant.parse("2026-11-15T20:00:00Z")

    private fun details(title: String, begin: Long, end: Long, allDay: Boolean = false, location: String = "", description: String = "", status: EventStatus = EventStatus.CONFIRMED) = EventDetails(
        1, 1, title, location, description, begin, end, allDay, null, "FREQ=DAILY", false, true, false, status, "", emptyList(), emptyList(), false, Answer.NONE,
    )

    @Test
    fun aTimedEventIsWrittenInUtc() {
        val begin = Instant.parse("2026-11-18T13:00:00Z").toEpochMilli()
        val text = Ics.event("7@sh.hnet.calendar", stamp, details("Workshop; part 1, intro", begin, begin + 7_200_000, location = "Room A\nFloor 2"))
        val lines = text.split("\r\n")
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue("DTSTAMP:20261115T200000Z" in lines)
        assertTrue("DTSTART:20261118T130000Z" in lines)
        assertTrue("DTEND:20261118T150000Z" in lines)
        assertTrue("SUMMARY:Workshop\\; part 1\\, intro" in lines)
        assertTrue("LOCATION:Room A\\nFloor 2" in lines)
        assertTrue("UID:7@sh.hnet.calendar" in lines)
        // One occurrence, not the series
        assertFalse(text.contains("RRULE"))
        assertTrue(text.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"))
    }

    @Test
    fun anAllDayEventIsWrittenByItsDates() {
        val first = LocalDate.of(2026, 11, 18)
        val text = Ics.event("1", stamp, details("Trip", first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), first.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true, status = EventStatus.CANCELED))
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261118\r\n"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261121\r\n"))
        assertTrue(text.contains("STATUS:CANCELLED\r\n"))
    }

    @Test
    fun longLinesAreFoldedBetweenCharacters() {
        val folded = Ics.fold("DESCRIPTION:" + "ż".repeat(60))
        val parts = folded.split("\r\n ")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("DESCRIPTION:" + "ż".repeat(60), parts.joinToString(""))
        assertEquals("SHORT", Ics.fold("SHORT"))
    }
}
