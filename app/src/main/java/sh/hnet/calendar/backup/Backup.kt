package sh.hnet.calendar.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calendar's backup file (SPEC §16.6; the content agreed with the owner, 16 Nov 2026), shaped as
 * Music's, Clock's, News's and Calculator's: the settings, and the color and visibility the user
 * gave each calendar. Not in it: the events – they are the system's, synced by the accounts' apps
 * or exported from a local calendar –, the view the app was left on, the calendar the last event
 * went to, which reminders were shown already, and the app language. Pure Kotlin, unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
    val calendars: List<BackupCalendar> = emptyList(),
) {
    companion object {
        const val FORMAT = "sh.hnet.calendar.backup"
        const val FORMAT_VERSION = 1
    }
}

/** Every setting may be absent (a backup from before it existed, or a choice left to the app): a restore then leaves the default. */
@Serializable
data class BackupSettings(
    /** "month", "week", "day" or "agenda"; absent for the view last used. */
    val startView: String? = null,
    /** 1 (Monday) to 7 (Sunday); absent for the system's. */
    val weekStart: Int? = null,
    val firstHour: Int? = null,
    val weekNumbers: Boolean? = null,
    val dimPastEvents: Boolean? = null,
    val showDeclinedEvents: Boolean? = null,
    /** The calendar's key; absent for the one last used. */
    val defaultCalendar: String? = null,
    val defaultLengthMinutes: Int? = null,
    /** Minutes before the start; an empty list is "none". */
    val remindersTimed: List<Int>? = null,
    val remindersAllDay: List<Int>? = null,
    val snoozeMinutes: Int? = null,
)

/**
 * What the user chose for a calendar. [key] is account type, account name and the calendar's own
 * name there, so the choice finds its calendar again on another phone once the account is back.
 */
@Serializable
data class BackupCalendar(val key: String, val color: String? = null, val shown: Boolean? = null)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}
