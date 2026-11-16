package sh.hnet.calendar.reminders

import android.content.Context
import androidx.core.content.edit

/** An occurrence whose reminder the user put off, to be shown again at [until]. */
data class Snoozed(val eventId: Long, val begin: Long, val end: Long, val until: Long)

/**
 * What the reminders remember between two alarms: which were shown already, so none comes twice,
 * and which the user put off. App-private, kept through Reset and out of the backup: it says
 * nothing about the user's choices.
 */
class ReminderStore(context: Context) {
    private val store = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** False until the first look at the calendar: what was due before the app reminded of anything is not brought up. */
    var started: Boolean
        get() = store.getBoolean(STARTED, false)
        set(value) = store.edit { putBoolean(STARTED, value) }

    /** The keys of the reminders shown, each with the time its event ends. */
    private fun entries(): Map<String, Long> = store.getStringSet(SHOWN, emptySet()).orEmpty().mapNotNull { entry ->
        val at = entry.lastIndexOf(SEPARATOR)
        entry.substring(at + 1).toLongOrNull()?.takeIf { at > 0 }?.let { entry.substring(0, at) to it }
    }.toMap()

    fun shown(): Set<String> = entries().keys

    /** Remembers the reminders with [keys] as shown, each until its event has been over for a while. */
    fun markShown(keys: Map<String, Long>) {
        if (keys.isEmpty()) return
        save(entries() + keys)
    }

    /** Forgets what ended more than two days before [now]: the calendar no longer offers it. */
    fun prune(now: Long) {
        val entries = entries()
        val kept = entries.filterValues { it > now - KEEP }
        if (kept.size != entries.size) save(kept)
    }

    private fun save(entries: Map<String, Long>) = store.edit { putStringSet(SHOWN, entries.mapTo(HashSet()) { "${it.key}$SEPARATOR${it.value}" }) }

    fun snoozed(): List<Snoozed> = store.getStringSet(SNOOZED, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split(SEPARATOR).mapNotNull { it.toLongOrNull() }
        if (parts.size == 4) Snoozed(parts[0], parts[1], parts[2], parts[3]) else null
    }

    fun setSnoozed(snoozed: List<Snoozed>) = store.edit {
        putStringSet(SNOOZED, snoozed.mapTo(HashSet()) { listOf(it.eventId, it.begin, it.end, it.until).joinToString(SEPARATOR.toString()) })
    }

    private companion object {
        const val STARTED = "started"
        const val SHOWN = "shown"
        const val SNOOZED = "snoozed"
        const val SEPARATOR = '\t'
        const val KEEP = 2 * 86_400_000L
    }
}
