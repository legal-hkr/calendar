package sh.hnet.calendar.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val backup = BackupFile(
        appVersion = "0.1.0", appVersionCode = 1, createdAt = "2026-11-16T10:00:00Z",
        settings = BackupSettings(startView = "week", weekStart = 7, firstHour = 6, weekNumbers = true, dimPastEvents = false, defaultLengthMinutes = 30, remindersTimed = listOf(5, 60), remindersAllDay = emptyList(), snoozeMinutes = 15),
        calendars = listOf(BackupCalendar("LOCAL|Local|local-1", "teal", false), BackupCalendar("bitfire.at.davdroid|jan@chmura.example|https://chmura.example/dav/praca/", color = "red")),
    )

    @Test
    fun aBackupReadsBackAsItWasWritten() {
        val check = Backup.check(Backup.encode(backup))
        assertEquals(BackupCheck.Valid(backup), check)
    }

    @Test
    fun whatWasLeftToTheAppIsAbsentAndStaysSo() {
        val text = Backup.encode(BackupFile(appVersion = "0.1.0", appVersionCode = 1, createdAt = "2026-11-16T10:00:00Z"))
        // No nulls are written: an absent setting is the default on restoring
        assertTrue(!text.contains("null"))
        val read = (Backup.check(text) as BackupCheck.Valid).backup
        assertEquals(BackupSettings(), read.settings)
        assertEquals(emptyList<BackupCalendar>(), read.calendars)
    }

    @Test
    fun anEmptyListOfRemindersIsNotAnAbsentOne() {
        val read = (Backup.check(Backup.encode(backup)) as BackupCheck.Valid).backup
        assertEquals(emptyList<Int>(), read.settings.remindersAllDay)
        assertEquals(null, BackupSettings().remindersAllDay)
    }

    @Test
    fun otherFilesAreToldApart() {
        assertTrue(Backup.check("BEGIN:VCALENDAR") is BackupCheck.NotOurs)
        assertTrue(Backup.check("[1, 2]") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.calculator.backup", "formatVersion": 1}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.calendar.backup"}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format": "sh.hnet.calendar.backup", "formatVersion": 2}"""))
        // Our format, but without what every backup has
        assertTrue(Backup.check("""{"format": "sh.hnet.calendar.backup", "formatVersion": 1}""") is BackupCheck.NotOurs)
    }

    @Test
    fun aLaterVersionsExtraFieldsAreIgnored() {
        val text = Backup.encode(backup).replaceFirst("{", "{\n  \"somethingNew\": true,")
        assertEquals(BackupCheck.Valid(backup), Backup.check(text))
    }
}
