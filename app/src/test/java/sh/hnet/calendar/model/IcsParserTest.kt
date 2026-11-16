package sh.hnet.calendar.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun file(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("CRLF").replace("CRLF", "\r\n")
    private fun events(vararg lines: String, phone: ZoneId = warsaw) = (IcsParser.read(file(*lines), phone, now) as IcsRead.Events).events

    @Test
    fun aTicketIsRead() {
        val read = IcsParser.read(
            file(
                "X-WR-CALNAME:Bilety", "BEGIN:VEVENT", "UID:ticket-1@kolej.example", "DTSTAMP:20261110T120000Z", "DTSTART:20261118T131500Z", "DTEND:20261118T160500Z",
                "SUMMARY:Warszawa Centralna – Kraków Główny", "LOCATION:Peron 3\\, wagon 7", "DESCRIPTION:Miejsce 45\\nBilet nr 123\\; ulgowy", "END:VEVENT",
            ),
            warsaw, now,
        ) as IcsRead.Events
        assertEquals("Bilety", read.name)
        val event = read.events.single()
        assertEquals("ticket-1@kolej.example", event.uid)
        assertEquals("Warszawa Centralna – Kraków Główny", event.title)
        assertEquals("Peron 3, wagon 7", event.location)
        assertEquals("Miejsce 45\nBilet nr 123; ulgowy", event.description)
        assertEquals(at("2026-11-18T13:15:00Z"), event.start)
        assertEquals(at("2026-11-18T16:05:00Z"), event.end)
        // A time in UTC that doesn't repeat is the phone's to show
        assertEquals(null, event.zone)
        assertEquals(false, event.allDay)
        assertEquals(null, event.rrule)
    }

    @Test
    fun foldedLinesAndPlainLineEndsAreRead() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:1\nDTSTART:20261118T130000Z\nSUMMARY:Bardzo dlu\n gi tytul\nDESCRIPTION:pierwsza\n\tdruga\nEND:VEVENT\nEND:VCALENDAR\n"
        val event = (IcsParser.read(text, warsaw, now) as IcsRead.Events).events.single()
        assertEquals("Bardzo dlugi tytul", event.title)
        assertEquals("pierwszadruga", event.description)
    }

    @Test
    fun timesInAZoneWithoutOneAndByDateAreRead() {
        val list = events(
            "BEGIN:VEVENT", "UID:a", "DTSTART;TZID=America/New_York:20261118T090000", "DTEND;TZID=America/New_York:20261118T100000", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b", "DTSTART:20261119T090000", "DURATION:PT45M", "END:VEVENT",
            "BEGIN:VEVENT", "UID:c", "DTSTART;VALUE=DATE:20261120", "DTEND;VALUE=DATE:20261123", "END:VEVENT",
            "BEGIN:VEVENT", "UID:d", "DTSTART;VALUE=DATE:20261121", "END:VEVENT",
        )
        val (a, b, c, d) = list
        assertEquals(at("2026-11-18T14:00:00Z"), a.start)
        assertEquals("America/New_York", a.zone)
        // No zone at all: the phone's
        assertEquals(at("2026-11-19T08:00:00Z"), b.start)
        assertEquals(at("2026-11-19T08:45:00Z"), b.end)
        assertEquals("Europe/Warsaw", b.zone)
        assertTrue(c.allDay)
        assertEquals(at("2026-11-20T00:00:00Z"), c.start)
        assertEquals(at("2026-11-23T00:00:00Z"), c.end)
        assertEquals("UTC", c.zone)
        // Without an end a day lasts a day
        assertEquals(at("2026-11-22T00:00:00Z"), d.end)
    }

    @Test
    fun aSeriesKeepsItsRuleLengthAndWhatWasTakenOut() {
        val event = events(
            "BEGIN:VEVENT", "UID:joga", "DTSTART;TZID=Europe/Warsaw:20261116T180000", "DTEND;TZID=Europe/Warsaw:20261116T190000",
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270331", "EXDATE;TZID=Europe/Warsaw:20261118T180000,20261130T180000", "EXDATE:20261214T170000Z", "END:VEVENT",
        ).single()
        // A bare date as the end takes in the whole of that day, in UTC
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270331T215959Z", event.rrule)
        assertEquals("PT3600S", event.duration)
        assertEquals(null, event.end)
        assertEquals("Europe/Warsaw", event.zone)
        assertEquals(listOf(at("2026-11-18T17:00:00Z"), at("2026-11-30T17:00:00Z"), at("2026-12-14T17:00:00Z")), event.exdates)
        val birthday = events("BEGIN:VEVENT", "UID:u", "DTSTART;VALUE=DATE:20261121", "RRULE:FREQ=YEARLY;UNTIL=20301121T000000Z", "END:VEVENT").single()
        assertEquals("FREQ=YEARLY;UNTIL=20301121", birthday.rrule)
        assertEquals("P1D", birthday.duration)
    }

    @Test
    fun aChangedOccurrenceNamesItsSeries() {
        val list = events(
            "BEGIN:VEVENT", "UID:leki", "DTSTART:20261116T070000Z", "DURATION:PT15M", "RRULE:FREQ=DAILY;COUNT=10", "END:VEVENT",
            "BEGIN:VEVENT", "UID:leki", "RECURRENCE-ID:20261118T070000Z", "DTSTART:20261118T060000Z", "DTEND:20261118T061500Z", "SUMMARY:Leki wczesniej", "END:VEVENT",
        )
        assertEquals(null, list.first { it.rrule != null }.originalUid)
        val changed = list.first { it.rrule == null }
        assertEquals("leki", changed.originalUid)
        assertEquals(at("2026-11-18T07:00:00Z"), changed.originalStart)
    }

    @Test
    fun remindersAndStatesAreRead() {
        val event = events(
            "BEGIN:VEVENT", "UID:x", "DTSTART:20261118T130000Z", "DTEND:20261118T140000Z", "STATUS:TENTATIVE", "TRANSP:TRANSPARENT", "CLASS:PRIVATE",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
            "BEGIN:VALARM", "TRIGGER;VALUE=DATE-TIME:20261117T130000Z", "END:VALARM",
            "BEGIN:VALARM", "TRIGGER;RELATED=END:-PT90M", "END:VALARM",
            "BEGIN:VALARM", "TRIGGER:PT0S", "END:VALARM",
            "END:VEVENT",
        ).single()
        // Before the end by an hour and a half is half an hour before the start
        assertEquals(listOf(0, 15, 30, 1440), event.reminders)
        assertEquals(EventStatus.TENTATIVE, event.status)
        assertEquals(false, event.busy)
        assertEquals(true, event.private)
        // On the day at 9:00 of an all-day event: after its start
        val allDay = events("BEGIN:VEVENT", "UID:y", "DTSTART;VALUE=DATE:20261121", "BEGIN:VALARM", "TRIGGER:PT9H", "END:VALARM", "END:VEVENT").single()
        assertEquals(listOf(-540), allDay.reminders)
    }

    @Test
    fun aCancellationMarksItsEvents() {
        val read = IcsParser.read(file("METHOD:CANCEL", "BEGIN:VEVENT", "UID:z", "DTSTART:20261118T130000Z", "END:VEVENT"), warsaw, now) as IcsRead.Events
        assertTrue(read.cancels)
        assertEquals(EventStatus.CANCELED, read.events.single().status)
        assertEquals(EventStatus.CANCELED, events("BEGIN:VEVENT", "UID:z", "DTSTART:20261118T130000Z", "STATUS:CANCELLED", "END:VEVENT").single().status)
    }

    @Test
    fun aZoneUnknownByNameIsFoundByItsDefinition() {
        val definition = arrayOf(
            "BEGIN:VTIMEZONE", "TZID:Strefa firmowa", "BEGIN:STANDARD", "DTSTART:19701025T030000", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:19700329T020000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU", "END:DAYLIGHT", "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:e", "DTSTART;TZID=Strefa firmowa:20261118T090000", "RRULE:FREQ=DAILY", "END:VEVENT",
        )
        // The phone's own zone fits: the times look as the user expects
        assertEquals("Europe/Warsaw", events(*definition).single().zone)
        assertEquals(at("2026-11-18T08:00:00Z"), events(*definition).single().start)
        // On a phone elsewhere a zone with the same offsets is taken
        val elsewhere = events(*definition, phone = ZoneId.of("America/New_York")).single()
        assertEquals(at("2026-11-18T08:00:00Z"), elsewhere.start)
        assertEquals("Europe/Warsaw", elsewhere.zone)
        // Names of other makers
        assertEquals("Europe/Warsaw", events("BEGIN:VEVENT", "UID:w", "DTSTART;TZID=Central European Standard Time:20261118T090000", "RRULE:FREQ=DAILY", "END:VEVENT").single().zone)
        assertEquals("Europe/Warsaw", events("BEGIN:VEVENT", "UID:f", "DTSTART;TZID=/freeassociation.sourceforge.net/Europe/Warsaw:20261118T090000", "RRULE:FREQ=DAILY", "END:VEVENT").single().zone)
        assertEquals("America/Argentina/Buenos_Aires", events("BEGIN:VEVENT", "UID:g", "DTSTART;TZID=\"/x/America/Argentina/Buenos_Aires\":20261118T090000", "RRULE:FREQ=DAILY", "END:VEVENT").single().zone)
    }

    @Test
    fun otherFilesAreToldApart() {
        assertEquals(IcsRead.Unreadable, IcsParser.read("{\"format\": \"sh.hnet.calendar.backup\"}", warsaw, now))
        assertEquals(IcsRead.Unreadable, IcsParser.read("", warsaw, now))
        assertEquals(IcsRead.NoEvents, IcsParser.read(file("BEGIN:VTODO", "UID:t", "SUMMARY:Kupic mleko", "END:VTODO"), warsaw, now))
        assertEquals(IcsRead.NoEvents, IcsParser.read(file(), warsaw, now))
        // Tasks beside events are left out without a word
        assertEquals(1, events("BEGIN:VTODO", "UID:t", "END:VTODO", "BEGIN:VEVENT", "UID:v", "DTSTART:20261118T130000Z", "END:VEVENT").size)
        // An event without its start costs that event, not the file
        assertEquals(1, events("BEGIN:VEVENT", "UID:bad", "SUMMARY:Bez daty", "END:VEVENT", "BEGIN:VEVENT", "UID:v", "DTSTART:20261118T130000Z", "END:VEVENT").size)
        assertEquals(IcsRead.Unreadable, IcsParser.read(file("BEGIN:VEVENT", "UID:bad", "SUMMARY:Bez daty", "END:VEVENT"), warsaw, now))
    }

    @Test
    fun whatTheExportWritesIsReadBack() {
        val series = IcsEvent(
            "2@x", "Leki; rano", "Dom, kuchnia", "Dwie tabletki\npo jedzeniu", at("2026-11-16T07:00:00Z"), null, "PT900S", false, "Europe/Warsaw", "FREQ=DAILY;COUNT=10",
            exdates = listOf(at("2026-11-18T07:00:00Z")), status = EventStatus.TENTATIVE, busy = false, private = true, reminders = listOf(0, 15),
        )
        val day = IcsEvent("3@x", "Urodziny", "", "", at("2026-11-21T00:00:00Z"), null, "P1D", true, "UTC", "FREQ=YEARLY", reminders = listOf(-540))
        val plain = IcsEvent("4@x", "Fryzjer", "", "", at("2026-11-18T16:00:00Z"), at("2026-11-18T16:45:00Z"), null, false, null, null)
        val text = IcsCalendar.write("Osobisty", now, listOf(series, day, plain))
        val read = IcsParser.read(text, ZoneId.of("America/New_York"), now) as IcsRead.Events
        assertEquals("Osobisty", read.name)
        assertEquals(listOf(series, plain, day), read.events)
    }

    @Test
    fun changedOccurrencesAreTakenOutOfTheirSeriesAndStandAlone() {
        val list = IcsImport.prepare(
            events(
                "BEGIN:VEVENT", "UID:leki", "DTSTART:20261116T070000Z", "DURATION:PT15M", "RRULE:FREQ=DAILY;COUNT=10", "EXDATE:20261117T070000Z", "END:VEVENT",
                "BEGIN:VEVENT", "UID:leki", "RECURRENCE-ID:20261118T070000Z", "DTSTART:20261118T060000Z", "DTEND:20261118T061500Z", "SUMMARY:Wczesniej", "END:VEVENT",
                "BEGIN:VEVENT", "UID:leki", "RECURRENCE-ID:20261119T070000Z", "DTSTART:20261119T070000Z", "STATUS:CANCELLED", "END:VEVENT",
                "BEGIN:VEVENT", "UID:sierota", "RECURRENCE-ID:20261120T070000Z", "DTSTART:20261120T090000Z", "SUMMARY:Bez serii", "END:VEVENT",
            ),
        )
        val series = list.single { it.rrule != null }
        assertEquals(listOf(at("2026-11-17T07:00:00Z"), at("2026-11-18T07:00:00Z"), at("2026-11-19T07:00:00Z")), series.exdates)
        // The changed one and the one without its series stand alone; the canceled one is only left out
        assertEquals(listOf("Wczesniej", "Bez serii"), list.filter { it.rrule == null }.map { it.title })
        assertTrue(list.filter { it.rrule == null }.all { it.uid.isEmpty() && it.originalUid == null })
        assertEquals("leki", series.uid)
    }
}
