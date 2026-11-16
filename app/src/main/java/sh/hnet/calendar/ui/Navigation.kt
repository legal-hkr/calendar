package sh.hnet.calendar.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Pages of the one back stack: the calendar at its root and what opens over it. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Calendar : Screen
    /** One occurrence of an event: the event's id in the provider and the occurrence's start and end. */
    @Serializable data class Event(val eventId: Long, val begin: Long, val end: Long) : Screen
    /**
     * The editor: a new event ([eventId] 0) on the Julian [day] at the [minute] of the day – 0 and -1
     * for today at the next full hour –, or the occurrence of [eventId] from [begin] to [end], which
     * with [copy] is only the pattern for a new one. [session] tells one opening of the form from
     * the next, so each starts with a draft of its own.
     */
    @Serializable data class Editor(val eventId: Long = 0, val begin: Long = 0, val end: Long = 0, val day: Int = 0, val minute: Int = -1, val copy: Boolean = false, val session: Long = 0) : Screen
    @Serializable data object Search : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Calendars : Screen
    /** A calendar that lives only on the phone, by its id in the provider. */
    @Serializable data class LocalCalendar(val id: Long) : Screen
    @Serializable data object SettingsView : Screen
    @Serializable data object SettingsEvents : Screen
    @Serializable data object SettingsReminders : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
    @Serializable data object SettingsLicenses : Screen
}
