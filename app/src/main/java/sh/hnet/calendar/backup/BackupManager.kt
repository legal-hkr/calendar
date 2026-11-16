package sh.hnet.calendar.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.hnet.calendar.BuildConfig
import sh.hnet.calendar.CalendarApplication
import sh.hnet.calendar.EventDefaults
import sh.hnet.calendar.Preferences
import sh.hnet.calendar.ViewSettings
import sh.hnet.calendar.model.CalendarChoice
import sh.hnet.calendar.model.CalendarColor
import sh.hnet.calendar.model.CalendarView
import sh.hnet.calendar.model.ReminderTimes

/**
 * Backup and restore (SPEC §16.6), as Music's, Clock's, News's and Calculator's: writes a file the
 * user picked and reads one back; a restore replaces the settings and the calendars' colors and
 * visibility with the file's. Nothing is logged.
 */
class BackupManager(private val app: CalendarApplication, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet. */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /**
     * Replaces the settings and the calendars' choices with the backup's. What the backup leaves
     * out returns to its default; a value this version doesn't know (a view, a color) counts as
     * left out. The number of calendars the backup has choices for.
     */
    suspend fun restore(backup: BackupFile): Int {
        val s = backup.settings
        app.preferences.replace(
            ViewSettings(
                view = CalendarView.MONTH, eventList = false, hourHeight = ViewSettings.HOUR_HEIGHT,
                startView = s.startView?.let { key -> CalendarView.entries.firstOrNull { it.key == key } },
                weekStart = s.weekStart?.takeIf { it in 1..7 }?.let(DayOfWeek::of),
                firstHour = s.firstHour?.takeIf { it in 0..23 } ?: ViewSettings.FIRST_HOUR,
                weekNumbers = s.weekNumbers ?: false,
                dimPast = s.dimPastEvents ?: true,
                showDeclined = s.showDeclinedEvents ?: false,
            ),
            EventDefaults(
                calendar = s.defaultCalendar,
                length = s.defaultLengthMinutes?.takeIf { it in EventDefaults.LENGTHS } ?: EventDefaults.LENGTH,
                remindersTimed = s.remindersTimed?.let(::reminders) ?: ReminderTimes.defaults(false),
                remindersAllDay = s.remindersAllDay?.let(::reminders) ?: ReminderTimes.defaults(true),
            ),
            s.snoozeMinutes?.takeIf { it in Preferences.SNOOZE_CHOICES } ?: Preferences.SNOOZE_DEFAULT,
        )
        val choices = backup.calendars.filter { it.key.isNotBlank() }.associate { calendar ->
            calendar.key to CalendarChoice(calendar.color?.takeIf { key -> CalendarColor.entries.any { it.key == key } }, calendar.shown)
        }
        app.calendarChoices.replace(choices)
        // A calendar shown or hidden again changes what reminds
        app.reminders.request()
        return choices.size
    }

    private fun reminders(minutes: List<Int>): List<Int> = minutes.distinct().sorted().take(ReminderTimes.MAX)

    private suspend fun snapshot(): BackupFile {
        val view = app.preferences.viewSettings.first()
        val events = app.preferences.eventDefaults.first()
        return BackupFile(
            appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = Instant.now().toString(),
            settings = BackupSettings(
                startView = view.startView?.key,
                weekStart = view.weekStart?.value,
                firstHour = view.firstHour,
                weekNumbers = view.weekNumbers,
                dimPastEvents = view.dimPast,
                showDeclinedEvents = view.showDeclined,
                defaultCalendar = events.calendar,
                defaultLengthMinutes = events.length,
                remindersTimed = events.remindersTimed,
                remindersAllDay = events.remindersAllDay,
                snoozeMinutes = app.preferences.snoozeMinutes.first(),
            ),
            calendars = app.calendarChoices.choices.first().map { (key, choice) -> BackupCalendar(key, choice.color, choice.shown) }.sortedBy { it.key },
        )
    }

    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        /** Far above any real backup: a few settings and a line per calendar. */
        const val MAX_BYTES = 1_048_576
    }
}
