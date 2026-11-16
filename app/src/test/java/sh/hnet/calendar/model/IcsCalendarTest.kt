package sh.hnet.calendar.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsCalendarTest {
    private val stamp = Instant.parse("2026-11-16T10:00:00Z")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun lines(events: List<IcsEvent>) = IcsCalendar.write("Dom; rodzina", stamp, events).split("\r\n")

    private fun plain(title: String = "Fryzjer") = IcsEvent("1@x", title, "", "", at("2026-11-18T16:00:00Z"), at("2026-11-18T16:45:00Z"), null, false, "Europe/Warsaw", null)

    @Test
    fun aPlainEventIsWrittenInUtcWithoutAZone() {
        val lines = lines(listOf(plain()))
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue("X-WR-CALNAME:Dom\\; rodzina" in lines)
        assertTrue("DTSTART:20261118T160000Z" in lines)
        assertTrue("DTEND:20261118T164500Z" in lines)
        assertFalse(lines.any { it.startsWith("BEGIN:VTIMEZONE") })
        assertEquals("END:VCALENDAR", lines[lines.lastIndex - 1])
    }

    @Test
    fun aSeriesKeepsItsZoneItsRuleAndWhatWasTakenOut() {
        // Every weekday at 08:00 in Warsaw, 15 minutes, one occurrence taken out
        val series = IcsEvent(
            "2@x", "Leki rano", "", "", at("2026-11-16T07:00:00Z"), null, "PT15M", false, "Europe/Warsaw", "FREQ=DAILY;COUNT=10",
            exdates = listOf(at("2026-11-18T07:00:00Z")), reminders = listOf(15, 0),
        )
        val lines = lines(listOf(series))
        assertTrue("DTSTART;TZID=Europe/Warsaw:20261116T080000" in lines)
        assertTrue("DTEND;TZID=Europe/Warsaw:20261116T081500" in lines)
        assertTrue("RRULE:FREQ=DAILY;COUNT=10" in lines)
        assertTrue("EXDATE;TZID=Europe/Warsaw:20261118T080000" in lines)
        assertTrue("TRIGGER:-PT15M" in lines)
        assertTrue("TRIGGER:-PT0M" in lines)
        // The zone is defined once, before the events
        assertEquals(1, lines.count { it == "TZID:Europe/Warsaw" })
        assertTrue(lines.indexOf("END:VTIMEZONE") < lines.indexOf("BEGIN:VEVENT"))
    }

    @Test
    fun anAllDayEventIsWrittenByItsDates() {
        val birthday = IcsEvent("3@x", "Urodziny Marty", "", "", at("2026-11-21T00:00:00Z"), null, "P1D", true, "UTC", "FREQ=YEARLY", exdates = listOf(at("2027-11-21T00:00:00Z")), reminders = listOf(-540))
        val lines = lines(listOf(birthday))
        assertTrue("DTSTART;VALUE=DATE:20261121" in lines)
        assertTrue("DTEND;VALUE=DATE:20261122" in lines)
        assertTrue("EXDATE;VALUE=DATE:20271121" in lines)
        // On the day at 9:00: after the start
        assertTrue("TRIGGER:PT540M" in lines)
        assertFalse(lines.any { it.startsWith("BEGIN:VTIMEZONE") })
        // Without a proper end it still lasts its day
        val endless = lines(listOf(IcsEvent("4@x", "Dzień", "", "", at("2026-11-21T00:00:00Z"), at("2026-11-21T00:00:00Z"), null, true, "UTC", null)))
        assertTrue("DTEND;VALUE=DATE:20261122" in endless)
    }

    @Test
    fun statesAndAChangedOccurrenceAreWritten() {
        val changed = plain("Zmienione").copy(uid = "9@x", originalUid = "2@x", originalStart = at("2026-11-18T07:00:00Z"), status = EventStatus.CANCELED, busy = false, private = true)
        val lines = lines(listOf(changed))
        assertTrue("UID:2@x" in lines)
        assertTrue("RECURRENCE-ID:20261118T070000Z" in lines)
        assertTrue("STATUS:CANCELLED" in lines)
        assertTrue("TRANSP:TRANSPARENT" in lines)
        assertTrue("CLASS:PRIVATE" in lines)
    }

    @Test
    fun aZoneThatChangesItsClockHasBothChanges() {
        val warsaw = IcsCalendar.timeZone(ZoneId.of("Europe/Warsaw"))
        // The last Sunday of March at 02:00 from +01:00 to +02:00, the last of October at 03:00 back
        val daylight = warsaw.subList(warsaw.indexOf("BEGIN:DAYLIGHT"), warsaw.indexOf("END:DAYLIGHT"))
        assertTrue("DTSTART:19700329T020000" in daylight)
        assertTrue("TZOFFSETFROM:+0100" in daylight)
        assertTrue("TZOFFSETTO:+0200" in daylight)
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU" in daylight)
        val standard = warsaw.subList(warsaw.indexOf("BEGIN:STANDARD"), warsaw.indexOf("END:STANDARD"))
        assertTrue("DTSTART:19701025T030000" in standard)
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU" in standard)
        // The second Sunday of March and the first of November
        val newYork = IcsCalendar.timeZone(ZoneId.of("America/New_York"))
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU" in newYork)
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU" in newYork)
        assertTrue("TZOFFSETFROM:-0500" in newYork)
    }

    @Test
    fun aZoneWithoutChangesHasStandardTimeAlone() {
        val tokyo = IcsCalendar.timeZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(listOf("BEGIN:VTIMEZONE", "TZID:Asia/Tokyo", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0900", "TZOFFSETTO:+0900", "END:STANDARD", "END:VTIMEZONE"), tokyo)
    }

    @Test
    fun storedDurationsAreRead() {
        assertEquals(5_400_000L, IcsCalendar.millis("PT1H30M"))
        assertEquals(86_400_000L, IcsCalendar.millis("P1D"))
        assertEquals(1_209_600_000L, IcsCalendar.millis("P2W"))
        // The provider writes seconds without the "T"
        assertEquals(3_600_000L, IcsCalendar.millis("P3600S"))
        assertEquals(900_000L, IcsCalendar.millis("+PT900S"))
        assertEquals(null, IcsCalendar.millis("P"))
        assertEquals(null, IcsCalendar.millis("soon"))
    }
}
