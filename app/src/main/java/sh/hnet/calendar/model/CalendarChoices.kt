package sh.hnet.calendar.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user chose for one calendar; null where nothing was chosen and the provider's value stands. */
@Serializable
data class CalendarChoice(val color: String? = null, val shown: Boolean? = null)

/**
 * The user's choices per calendar (SPEC §4.5): the app's own, kept by the app under
 * [CalendarInfo.key] and never written to the provider, where a sync could overwrite them and
 * other calendar apps would see them.
 */
object CalendarChoices {
    private val json = Json { ignoreUnknownKeys = true }

    /** The calendars with the choices laid over what the provider says. A color key this version doesn't know is ignored. */
    fun apply(calendars: List<CalendarInfo>, choices: Map<String, CalendarChoice>): List<CalendarInfo> = calendars.map { calendar ->
        val choice = choices[calendar.key] ?: return@map calendar
        calendar.copy(
            color = choice.color?.let { key -> CalendarColor.entries.firstOrNull { it.key == key } } ?: calendar.color,
            shown = choice.shown ?: calendar.shown,
        )
    }

    fun encode(choices: Map<String, CalendarChoice>): String = json.encodeToString(choices)

    /** Empty for text that can't be read: the choices are then the provider's again. */
    fun decode(text: String?): Map<String, CalendarChoice> =
        if (text.isNullOrEmpty()) emptyMap() else runCatching { json.decodeFromString<Map<String, CalendarChoice>>(text) }.getOrDefault(emptyMap())
}
